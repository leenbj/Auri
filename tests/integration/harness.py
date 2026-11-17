"""Black-box test harness for Auri Gateway integration and security tests."""
from __future__ import annotations

import asyncio
import hashlib
import json
import os
import pathlib
import socket
import ssl
import subprocess
import sys
import threading
import time
import uuid
from dataclasses import dataclass
from typing import Any, NamedTuple

import httpx
import pytest
from jsonschema import Draft202012Validator
from referencing import Registry, Resource

try:
    BaseExceptionGroup  # Python 3.11+
except NameError:
    from exceptiongroup import BaseExceptionGroup

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
GATEWAY_SRC = REPO_ROOT / "services" / "gateway"
SCHEMA_DIR = REPO_ROOT / "contracts" / "schemas"
FIXTURE_DIR = REPO_ROOT / "contracts" / "fixtures"
BASE_URI = "https://contracts.auri.invalid/1.1/"

_SCHEMAS = {
    path.name: json.loads(path.read_text(encoding="utf-8"))
    for path in sorted(SCHEMA_DIR.glob("*.schema.json"))
}
_REGISTRY = Registry().with_resources(
    (schema["$id"], Resource.from_contents(schema)) for schema in _SCHEMAS.values()
)


def validator_for(schema_ref: str) -> Draft202012Validator:
    return Draft202012Validator({"$ref": BASE_URI + schema_ref}, registry=_REGISTRY)


def assert_valid_contract(schema_ref: str, instance: Any) -> None:
    errors = list(validator_for(schema_ref).iter_errors(instance))
    assert not errors, [f"{list(e.absolute_path)}: {e.message}" for e in errors]


def load_fixture(rel_path: str) -> dict[str, Any]:
    return json.loads((FIXTURE_DIR / rel_path).read_text(encoding="utf-8"))


def sha256_hex(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def find_free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def subprocess_env() -> dict[str, str]:
    env = os.environ.copy()
    existing = env.get("PYTHONPATH", "")
    env["PYTHONPATH"] = f"{GATEWAY_SRC}:{existing}" if existing else str(GATEWAY_SRC)
    return env


class GatewayProcess:
    """Manages a real Auri Gateway subprocess for black-box HTTP/HTTPS & CLI tests."""

    def __init__(
        self,
        data_dir: pathlib.Path,
        port: int | None = None,
        *,
        tls: bool = False,
        host: str | None = "127.0.0.1",
        mdns: bool = False,
        extra_serve_args: list[str] | None = None,
    ) -> None:
        self.data_dir = data_dir
        self.port = port or find_free_port()
        self.tls = tls
        self.host = host
        self.mdns = mdns
        self.extra_serve_args = list(extra_serve_args or [])
        scheme = "https" if tls else "http"
        connect_host = host if host and host not in ("0.0.0.0", "::") else "127.0.0.1"
        self.base_url = f"{scheme}://{connect_host}:{self.port}"
        self._connect_host = connect_host
        self._proc: subprocess.Popen[str] | None = None
        self._collected: dict[str, list[str]] = {"stdout": [], "stderr": []}
        self._readers: list[threading.Thread] = []
        self._output_changed = threading.Condition()

    def start(self, timeout_seconds: float = 10.0) -> None:
        cmd = [
            sys.executable,
            "-m",
            "auri_gateway",
            "serve",
            "--data-dir",
            str(self.data_dir),
            "--port",
            str(self.port),
        ]
        if self.host is not None:
            cmd.extend(["--host", self.host])
        if not self.tls:
            cmd.append("--plaintext-loopback")
        if not self.mdns:
            cmd.append("--no-mdns")
        cmd.extend(self.extra_serve_args)

        self._proc = subprocess.Popen(
            cmd,
            cwd=str(REPO_ROOT),
            env=subprocess_env(),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        # Drain both pipes while the gateway runs: a full pipe would block it on its next log line.
        self._collected = {"stdout": [], "stderr": []}
        self._readers = [
            threading.Thread(target=self._drain, args=(name, stream), daemon=True)
            for name, stream in (("stdout", self._proc.stdout), ("stderr", self._proc.stderr))
        ]
        for reader in self._readers:
            reader.start()
        deadline = time.monotonic() + timeout_seconds
        while time.monotonic() < deadline:
            if self._proc.poll() is not None:
                self._proc.wait(timeout=2)
                for reader in self._readers:
                    reader.join(timeout=2)
                raise RuntimeError(
                    f"Gateway exited prematurely ({self._proc.returncode}): "
                    f"stdout={self._joined('stdout')!r} stderr={self._joined('stderr')!r}"
                )
            try:
                if self.tls:
                    cert_path = self.data_dir / "tls" / "gateway.cert.pem"
                    if cert_path.exists():
                        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
                        ctx.check_hostname = False
                        ctx.verify_mode = ssl.CERT_NONE
                        with socket.create_connection((self._connect_host, self.port), timeout=0.5) as raw_sock:
                            with ctx.wrap_socket(raw_sock, server_hostname=self._connect_host):
                                return
                else:
                    resp = httpx.get(f"{self.base_url}/health", timeout=0.5)
                    if resp.status_code == 200:
                        return
            except (OSError, httpx.HTTPError, ssl.SSLError):
                pass
            time.sleep(0.05)
        self.stop()
        raise TimeoutError(f"Gateway did not become healthy within {timeout_seconds}s")

    def stop(self) -> tuple[str, str]:
        if self._proc is None:
            return "", ""
        if self._proc.poll() is None:
            self._proc.terminate()
            try:
                self._proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self._proc.kill()
                self._proc.wait(timeout=5)
        for reader in self._readers:
            reader.join(timeout=5)
        self._proc = None
        return self._joined("stdout"), self._joined("stderr")

    def _drain(self, name: str, stream: Any) -> None:
        for line in stream:
            with self._output_changed:
                self._collected[name].append(line)
                self._output_changed.notify_all()

    def _joined(self, name: str) -> str:
        with self._output_changed:
            return "".join(self._collected[name])

    def count_output(self, text: str) -> int:
        """How many times the gateway has written `text` so far (stdout and stderr together)."""
        with self._output_changed:
            return sum(line.count(text) for lines in self._collected.values() for line in lines)

    def wait_for_output(self, text: str, *, occurrences: int, timeout: float = 10.0) -> None:
        """Block until the gateway has written `text` at least `occurrences` times in total."""
        deadline = time.monotonic() + timeout
        with self._output_changed:
            while self.count_output(text) < occurrences:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError(f"the gateway never wrote {text!r} {occurrences} time(s)")
                self._output_changed.wait(remaining)

    def run_cli(self, *args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, "-m", "auri_gateway", *args],
            cwd=str(REPO_ROOT),
            env=subprocess_env(),
            capture_output=True,
            text=True,
            check=False,
        )

    def issue_pairing_code(self, ttl_seconds: int = 300) -> str:
        result = self.run_cli(
            "pair",
            "--data-dir",
            str(self.data_dir),
            "--ttl-seconds",
            str(ttl_seconds),
            "--json",
        )
        assert result.returncode == 0, f"pair CLI failed: {result.stderr}"
        payload = json.loads(result.stdout)
        return payload["pairing_code"]

    def pair_device(self, display_name: str = "Test iPhone") -> dict[str, Any]:
        code = self.issue_pairing_code()
        req = make_redeem_request(code, display_name=display_name)
        assert_valid_contract("pairing.schema.json#/$defs/redeem_request", req)
        resp = httpx.post(f"{self.base_url}/v1/pairing/redeem", json=req, timeout=5.0)
        assert resp.status_code == 200, f"redeem failed: {resp.status_code} {resp.text}"
        body = resp.json()
        assert_valid_contract("pairing.schema.json#/$defs/redeem_response", body)
        return body

    def create_agent_destination(
        self,
        device_token: str,
        display_name: str = "Test Agent",
        *,
        allowed_intents: tuple[str, ...] = ("context",),
    ) -> "AgentDestination":
        """Create an auto-send Destination over HTTP and issue its one-time credential."""
        headers = {"Authorization": f"Bearer {device_token}"}
        create_req = {
            **load_fixture("messages/valid/destination.create_request.auto-send.json")["instance"],
            "display_name": display_name,
            "allowed_intents": list(allowed_intents),
        }
        assert_valid_contract("destination.schema.json#/$defs/create_request", create_req)
        created = httpx.post(
            f"{self.base_url}/v1/destinations", json=create_req, headers=headers, timeout=5.0
        )
        assert created.status_code == 200, created.text
        destination_id = created.json()["destination_id"]
        issued = httpx.post(
            f"{self.base_url}/v1/destinations/{destination_id}/credential",
            headers=headers,
            timeout=5.0,
        )
        assert issued.status_code == 200, issued.text
        assert_valid_contract("destination.schema.json#/$defs/credential_response", issued.json())
        return AgentDestination(destination_id, issued.json()["credential"], display_name)

    def publish(
        self,
        device_token: str,
        destination_ids: list[str],
        *,
        text: str = "帮我记录一下：明天检查小说的记忆模块。",
        record_id: str | None = None,
        revision: int = 1,
        intent: str = "context",
        source: str = "asr",
    ) -> dict[str, Any]:
        """Submit one Revision over HTTP; returns the validated `stored` response."""
        submission = make_submission(
            record_id=record_id,
            revision=revision,
            text=text,
            destination_ids=destination_ids,
            source=source,
        )
        submission["publication"]["intent"] = intent
        assert_valid_contract("revision.schema.json#/$defs/submission", submission)
        resp = httpx.put(
            f"{self.base_url}/v1/records/{submission['record_id']}/revisions/{revision}",
            json=submission,
            headers={"Authorization": f"Bearer {device_token}"},
            timeout=5.0,
        )
        assert resp.status_code == 200, resp.text
        stored = resp.json()
        assert_valid_contract("revision.schema.json#/$defs/stored", stored)
        return stored


@dataclass(frozen=True)
class AgentDestination:
    destination_id: str
    credential: str
    display_name: str


class ToolOutcome(NamedTuple):
    """What an MCP tool call returned: the error flag, the structured payload and the text block."""

    is_error: bool
    payload: dict[str, Any]
    text: str


_TOOL_SCHEMAS = {
    "delivery_claim": ("delivery.schema.json#/$defs/claim_request", "delivery.schema.json#/$defs/claim_response"),
    "delivery_renew_lease": ("delivery.schema.json#/$defs/renew_request", "delivery.schema.json#/$defs/renew_response"),
    "delivery_ack": ("delivery.schema.json#/$defs/ack_request", "delivery.schema.json#/$defs/ack_response"),
    "delivery_nack": ("delivery.schema.json#/$defs/nack_request", "delivery.schema.json#/$defs/nack_response"),
    "record_list": ("record_query.schema.json#/$defs/list_request", "record_query.schema.json#/$defs/list_response"),
    "record_get": ("record_query.schema.json#/$defs/get_request", "record_query.schema.json#/$defs/get_response"),
    "record_search": ("record_query.schema.json#/$defs/search_request", "record_query.schema.json#/$defs/search_response"),
}


class McpAgent:
    """An Agent talking to `/mcp` with a Destination credential through the official MCP SDK client.

    `mode="modern"` negotiates MCP 2026-07-28; `mode="legacy"` negotiates the handshake era
    (2025-11-25). Every call opens its own connection, so calls from several threads are
    genuinely concurrent at the HTTP level.
    """

    def __init__(self, base_url: str, credential: str, *, mode: str = "modern") -> None:
        assert mode in ("modern", "legacy")
        self.url = f"{base_url}/mcp"
        self.credential = credential
        self.mode = mode
        self.protocol_version: str | None = None

    def _run(self, operation):
        import httpx2
        from mcp import Client
        from mcp.client.streamable_http import streamable_http_client

        async def session():
            async with httpx2.AsyncClient(
                headers={"Authorization": f"Bearer {self.credential}"}, timeout=20.0
            ) as http:
                kwargs = {} if self.mode == "modern" else {"mode": "legacy"}
                transport = streamable_http_client(self.url, http_client=http)
                async with Client(transport, **kwargs) as client:
                    self.protocol_version = client.protocol_version
                    return await operation(client)

        try:
            return asyncio.run(session())
        except BaseExceptionGroup as group:
            # The SDK client runs inside anyio task groups, which wrap a single failure (such as
            # the MCPError for a protocol-level refusal) in groups. Hand the caller that failure.
            failure: BaseException = group
            while isinstance(failure, BaseExceptionGroup) and len(failure.exceptions) == 1:
                failure = failure.exceptions[0]
            if failure is group:
                raise
            raise failure from None

    def list_tools(self) -> list[Any]:
        async def operation(client):
            return (await client.list_tools()).tools

        return self._run(operation)

    def call(self, name: str, arguments: dict[str, Any]) -> ToolOutcome:
        """Call a tool with arguments exactly as given (they may be contract-invalid on purpose)."""

        async def operation(client):
            return await client.call_tool(name, arguments)

        result = self._run(operation)
        text = result.content[0].text
        payload = result.structured_content
        if payload is None:
            payload = json.loads(text)
        return ToolOutcome(bool(result.is_error), payload, text)

    def call_ok(self, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
        """Call a contract-valid request; the response must be a contract-valid success."""
        req_schema, resp_schema = _TOOL_SCHEMAS[name]
        assert_valid_contract(req_schema, arguments)
        outcome = self.call(name, arguments)
        assert not outcome.is_error, outcome.payload
        assert_valid_contract(resp_schema, outcome.payload)
        return outcome.payload

    def call_error(self, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
        """Call a tool that must fail with a contract-valid unified error; returns that error."""
        outcome = self.call(name, arguments)
        assert outcome.is_error, outcome.payload
        assert_valid_contract("error.schema.json", outcome.payload)
        return outcome.payload

    def claim(self, **arguments: Any) -> dict[str, Any]:
        return self.call_ok("delivery_claim", arguments)

    def renew(self, delivery_id: str, lease_token: str) -> dict[str, Any]:
        return self.call_ok(
            "delivery_renew_lease", {"delivery_id": delivery_id, "lease_token": lease_token}
        )

    def ack(self, delivery_id: str, lease_token: str, note: str | None = None) -> dict[str, Any]:
        arguments = {"delivery_id": delivery_id, "lease_token": lease_token}
        if note is not None:
            arguments["note"] = note
        return self.call_ok("delivery_ack", arguments)

    def nack(
        self, delivery_id: str, lease_token: str, *, retryable: bool, reason: str = "测试拒收"
    ) -> dict[str, Any]:
        return self.call_ok(
            "delivery_nack",
            {
                "delivery_id": delivery_id,
                "lease_token": lease_token,
                "reason": reason,
                "retryable": retryable,
            },
        )

    def record_list(self, **arguments: Any) -> dict[str, Any]:
        return self.call_ok("record_list", arguments)

    def record_get(self, record_id: str, **arguments: Any) -> dict[str, Any]:
        return self.call_ok("record_get", {"record_id": record_id, **arguments})

    def record_search(self, query: str, **arguments: Any) -> dict[str, Any]:
        return self.call_ok("record_search", {"query": query, **arguments})


def make_redeem_request(
    pairing_code: str, display_name: str = "Test iPhone"
) -> dict[str, Any]:
    return {
        "schema_version": "1.1",
        "pairing_code": pairing_code,
        "device": {
            "platform": "ios",
            "app_version": "0.1.0-test",
            "os_build": "test-build",
            "display_name": display_name,
        },
    }


def make_submission(
    record_id: str | None = None,
    revision: int = 1,
    text: str = "这是一条测试语音记录。",
    destination_ids: list[str] | None = None,
    source: str = "asr",
) -> dict[str, Any]:
    is_asr = source == "asr"
    return {
        "schema_version": "1.1",
        "record_id": record_id or str(uuid.uuid4()),
        "revision": revision,
        "capture": {
            "platform": "ios",
            "app_version": "0.1.0-test",
            "started_at": "2026-11-16T10:00:00Z",
            "capture_duration_ms": 5000,
            "speech_duration_ms": 3000,
            "speech_segments": [{"start_ms": 500, "end_ms": 3500}],
            "end_reason": "screen_control",
            "status": "complete",
            "input_policy": "bluetooth_only",
            "input_verification": "verified",
            "headset_display_name": "HUAWEI FreeClip 2",
            "headset_firmware": None,
            "input_route_type": "bluetooth_hfp",
            "route_evidence_id": str(uuid.uuid4()),
        },
        "content": {
            "text": text,
            "text_sha256": sha256_hex(text),
            "source": source,
            "language": "zh-CN",
            "review_state": "not_reviewed" if is_asr else "reviewed",
            "execution_location": "on_device",
            "engine": "sherpa-onnx" if is_asr else None,
            "model_id": "test-model" if is_asr else None,
            "model_version": "1.0.0" if is_asr else None,
            "confidence": None,
            "segments": [{"start_ms": 500, "end_ms": 3500, "text": text}] if is_asr else [],
        },
        "publication": {
            "intent": "context",
            "destination_ids": destination_ids if destination_ids is not None else [],
        },
    }


@pytest.fixture
def gateway(tmp_path: pathlib.Path):
    gw = GatewayProcess(tmp_path / "gateway-data")
    gw.start()
    try:
        yield gw
    finally:
        gw.stop()


class World:
    """One paired phone publishing to Agent Destinations on a running gateway."""

    def __init__(self, gw: GatewayProcess) -> None:
        self.gw = gw
        self.device_token = gw.pair_device("Publisher Phone")["device_token"]

    def destination(self, display_name: str = "Test Agent", **kwargs: Any) -> AgentDestination:
        return self.gw.create_agent_destination(self.device_token, display_name, **kwargs)

    def agent(self, destination: AgentDestination, *, mode: str = "modern") -> McpAgent:
        return McpAgent(self.gw.base_url, destination.credential, mode=mode)

    def publish(self, *destinations: AgentDestination, **kwargs: Any) -> dict[str, Any]:
        return self.gw.publish(
            self.device_token, [d.destination_id for d in destinations], **kwargs
        )

    def rotate_credential(self, destination: AgentDestination) -> AgentDestination:
        """The phone issues a new credential; the previous one stops working at once."""
        issued = httpx.post(
            f"{self.gw.base_url}/v1/destinations/{destination.destination_id}/credential",
            headers={"Authorization": f"Bearer {self.device_token}"},
            timeout=5.0,
        )
        assert issued.status_code == 200, issued.text
        assert_valid_contract("destination.schema.json#/$defs/credential_response", issued.json())
        assert issued.json()["replaced_previous"] is True
        return AgentDestination(
            destination.destination_id, issued.json()["credential"], destination.display_name
        )

    def revoke(self, destination: AgentDestination) -> None:
        """The phone revokes a Destination over HTTP (`DELETE /v1/destinations/{id}`)."""
        revoked = httpx.delete(
            f"{self.gw.base_url}/v1/destinations/{destination.destination_id}",
            headers={"Authorization": f"Bearer {self.device_token}"},
            timeout=5.0,
        )
        assert revoked.status_code == 200, revoked.text


@pytest.fixture
def world(gateway: GatewayProcess) -> World:
    return World(gateway)
