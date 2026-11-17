"""Black-box test harness for Auri Gateway integration and security tests."""
from __future__ import annotations

import hashlib
import json
import os
import pathlib
import socket
import subprocess
import sys
import time
import uuid
from typing import Any

import httpx
import pytest
from jsonschema import Draft202012Validator
from referencing import Registry, Resource

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
    """Manages a real Auri Gateway subprocess for black-box HTTP & CLI tests."""

    def __init__(self, data_dir: pathlib.Path, port: int | None = None) -> None:
        self.data_dir = data_dir
        self.port = port or find_free_port()
        self.base_url = f"http://127.0.0.1:{self.port}"
        self._proc: subprocess.Popen[str] | None = None

    def start(self, timeout_seconds: float = 10.0) -> None:
        self._proc = subprocess.Popen(
            [
                sys.executable,
                "-m",
                "auri_gateway",
                "serve",
                "--data-dir",
                str(self.data_dir),
                "--host",
                "127.0.0.1",
                "--port",
                str(self.port),
            ],
            cwd=str(REPO_ROOT),
            env=subprocess_env(),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        deadline = time.monotonic() + timeout_seconds
        while time.monotonic() < deadline:
            if self._proc.poll() is not None:
                stdout, stderr = self._proc.communicate(timeout=2)
                raise RuntimeError(
                    f"Gateway exited prematurely ({self._proc.returncode}): stdout={stdout!r} stderr={stderr!r}"
                )
            try:
                resp = httpx.get(f"{self.base_url}/health", timeout=0.5)
                if resp.status_code == 200:
                    return
            except httpx.HTTPError:
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
                stdout, stderr = self._proc.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                self._proc.kill()
                stdout, stderr = self._proc.communicate(timeout=5)
        else:
            stdout, stderr = self._proc.communicate(timeout=2)
        self._proc = None
        return stdout, stderr

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
