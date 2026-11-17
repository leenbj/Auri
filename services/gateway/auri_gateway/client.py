"""Reference HTTPS client with strict SPKI SHA-256 pinning and mDNS fallback (ADR-0018)."""
from __future__ import annotations

import hmac
import re
import ssl
from typing import Any, Iterator, Sequence

import httpcore
import httpx

from .error_codes import GATEWAY_KEY_MISMATCH, GATEWAY_UNREACHABLE, build_error_payload
from .mdns import discover_gateways
from .network import MDNS_SERVICE_TYPE
from .tls import spki_sha256_from_cert_der

_SHA256_HEX_RE = re.compile(r"^[0-9a-f]{64}$")


class GatewayKeyMismatchError(Exception):
    """Raised when the peer TLS certificate SPKI SHA-256 does not match the pinned fingerprint."""

    def __init__(self, *, expected_sha256: str, actual_sha256: str) -> None:
        super().__init__(
            "Gateway public key fingerprint does not match the paired fingerprint; connection refused."
        )
        self.expected_sha256 = expected_sha256
        self.actual_sha256 = actual_sha256

    def to_error_payload(self) -> dict[str, Any]:
        return build_error_payload(
            GATEWAY_KEY_MISMATCH,
            "Gateway public key does not match the paired fingerprint; connection refused.",
            retryable=False,
            required_action="Generate a new pairing code on the gateway and re-pair.",
        )


class GatewayUnreachableError(Exception):
    """Raised when neither paired addresses nor mDNS discovery could reach the gateway."""

    def __init__(self, message: str = "Gateway is unreachable on paired addresses and mDNS.") -> None:
        super().__init__(message)

    def to_error_payload(self) -> dict[str, Any]:
        return build_error_payload(
            GATEWAY_UNREACHABLE,
            str(self),
            retryable=True,
            required_action=None,
        )


class _PinnedTLSStream(httpcore.NetworkStream):
    """Wraps a httpcore NetworkStream to verify SPKI SHA-256 right after TLS handshake."""

    def __init__(self, inner: httpcore.NetworkStream, expected_public_key_sha256: str) -> None:
        self._inner = inner
        self._expected_public_key_sha256 = expected_public_key_sha256

    def read(self, max_bytes: int, timeout: float | None = None) -> bytes:
        return self._inner.read(max_bytes, timeout=timeout)

    def write(self, buffer: bytes, timeout: float | None = None) -> None:
        self._inner.write(buffer, timeout=timeout)

    def close(self) -> None:
        self._inner.close()

    def start_tls(
        self,
        ssl_context: ssl.SSLContext,
        server_hostname: str | None = None,
        timeout: float | None = None,
    ) -> httpcore.NetworkStream:
        tls_stream = self._inner.start_tls(
            ssl_context,
            server_hostname=server_hostname,
            timeout=timeout,
        )
        try:
            ssl_obj = tls_stream.get_extra_info("ssl_object")
            if ssl_obj is None:
                raise GatewayKeyMismatchError(
                    expected_sha256=self._expected_public_key_sha256,
                    actual_sha256="",
                )
            der_bytes = ssl_obj.getpeercert(True)
            if not der_bytes:
                raise GatewayKeyMismatchError(
                    expected_sha256=self._expected_public_key_sha256,
                    actual_sha256="",
                )
            actual_fp = spki_sha256_from_cert_der(der_bytes)
            if not hmac.compare_digest(actual_fp, self._expected_public_key_sha256):
                raise GatewayKeyMismatchError(
                    expected_sha256=self._expected_public_key_sha256,
                    actual_sha256=actual_fp,
                )
        except Exception:
            tls_stream.close()
            raise
        return _PinnedTLSStream(tls_stream, self._expected_public_key_sha256)

    def get_extra_info(self, info: str) -> Any:
        return self._inner.get_extra_info(info)


class _PinnedTLSBackend(httpcore.SyncBackend):
    def __init__(self, expected_public_key_sha256: str) -> None:
        super().__init__()
        self._expected_public_key_sha256 = expected_public_key_sha256

    def connect_tcp(
        self,
        host: str,
        port: int,
        timeout: float | None = None,
        local_address: str | None = None,
        socket_options: Any = None,
    ) -> httpcore.NetworkStream:
        raw_stream = super().connect_tcp(
            host,
            port,
            timeout=timeout,
            local_address=local_address,
            socket_options=socket_options,
        )
        return _PinnedTLSStream(raw_stream, self._expected_public_key_sha256)


class _PinnedResponseStream(httpx.SyncByteStream):
    def __init__(self, inner_stream: Any) -> None:
        self._inner_stream = inner_stream

    def __iter__(self) -> Iterator[bytes]:
        yield from self._inner_stream

    def close(self) -> None:
        if hasattr(self._inner_stream, "close"):
            self._inner_stream.close()


class _PinnedHTTPTransport(httpx.BaseTransport):
    """httpx transport backed by httpcore ConnectionPool with mandatory SPKI pinning."""

    def __init__(self, *, expected_public_key_sha256: str) -> None:
        # ADR-0018: Self-signed cert verified strictly by SPKI SHA-256 pin inside _PinnedTLSStream;
        # CA-chain / hostname checks are disabled because identity is the pinned public key,
        # and no opt-out or bypass path exists.
        ssl_context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ssl_context.check_hostname = False
        ssl_context.verify_mode = ssl.CERT_NONE
        ssl_context.minimum_version = ssl.TLSVersion.TLSv1_2

        self._pool = httpcore.ConnectionPool(
            ssl_context=ssl_context,
            network_backend=_PinnedTLSBackend(expected_public_key_sha256),
        )

    def handle_request(self, request: httpx.Request) -> httpx.Response:
        if request.url.scheme != "https":
            raise ValueError("PinnedGatewayClient only permits https:// connections.")
        req = httpcore.Request(
            method=request.method,
            url=httpcore.URL(
                scheme=request.url.raw_scheme,
                host=request.url.raw_host,
                port=request.url.port,
                target=request.url.raw_path,
            ),
            headers=request.headers.raw,
            content=request.stream,
        )
        resp = self._pool.handle_request(req)
        return httpx.Response(
            status_code=resp.status,
            headers=resp.headers,
            stream=_PinnedResponseStream(resp.stream),
            extensions=resp.extensions,
        )

    def close(self) -> None:
        self._pool.close()


class PinnedGatewayClient:
    """Reference Auri Gateway HTTPS client enforcing mandatory public key pinning (no bypass)."""

    def __init__(
        self,
        *,
        gateway_id: str,
        addresses: Sequence[dict[str, Any]],
        expected_public_key_sha256: str,
        mdns_service_type: str = MDNS_SERVICE_TYPE,
        timeout_seconds: float = 5.0,
    ) -> None:
        if not _SHA256_HEX_RE.match(expected_public_key_sha256):
            raise ValueError(
                "expected_public_key_sha256 must be a 64-character lowercase SHA-256 hex string."
            )
        if not addresses:
            raise ValueError("At least one gateway address is required.")

        self.gateway_id = gateway_id
        self.addresses = [{"host": str(a["host"]), "port": int(a["port"])} for a in addresses]
        self.expected_public_key_sha256 = expected_public_key_sha256
        self.mdns_service_type = mdns_service_type
        self.timeout_seconds = timeout_seconds

        self._client = httpx.Client(
            transport=_PinnedHTTPTransport(
                expected_public_key_sha256=expected_public_key_sha256
            ),
            timeout=timeout_seconds,
        )

    @classmethod
    def from_pairing_payload(
        cls, payload: dict[str, Any], *, timeout_seconds: float = 5.0
    ) -> "PinnedGatewayClient":
        return cls(
            gateway_id=str(payload["gateway_id"]),
            addresses=payload["addresses"],
            expected_public_key_sha256=str(payload["public_key_sha256"]),
            mdns_service_type=str(payload.get("mdns_service_type", MDNS_SERVICE_TYPE)),
            timeout_seconds=timeout_seconds,
        )

    def close(self) -> None:
        self._client.close()

    def __enter__(self) -> "PinnedGatewayClient":
        return self

    def __exit__(self, *_args: object) -> None:
        self.close()

    def _candidate_base_urls_from_mdns(self) -> list[str]:
        discovered = discover_gateways(
            gateway_id=self.gateway_id,
            service_type=self.mdns_service_type,
            timeout_seconds=min(self.timeout_seconds, 3.0),
        )
        urls: list[str] = []
        for entry in discovered:
            for addr in entry.addresses:
                host = f"[{addr}]" if ":" in addr else addr
                urls.append(f"https://{host}:{entry.port}")
        return urls

    def _try_urls(
        self, urls: list[str], method: str, path: str, **kwargs: Any
    ) -> tuple[httpx.Response | None, Exception | None]:
        last_exc: Exception | None = None
        for base_url in urls:
            try:
                return self._client.request(method, f"{base_url}{path}", **kwargs), None
            except GatewayKeyMismatchError:
                raise
            except (
                httpx.ConnectError,
                httpx.ConnectTimeout,
                httpcore.ConnectError,
                httpcore.ConnectTimeout,
                OSError,
            ) as exc:
                last_exc = exc
        return None, last_exc

    def _request(self, method: str, path: str, **kwargs: Any) -> httpx.Response:
        remembered_urls = [
            f"https://[{addr['host']}]:{addr['port']}"
            if ":" in addr["host"]
            else f"https://{addr['host']}:{addr['port']}"
            for addr in self.addresses
        ]
        resp, last_exc = self._try_urls(remembered_urls, method, path, **kwargs)
        if resp is not None:
            return resp

        mdns_urls = [u for u in self._candidate_base_urls_from_mdns() if u not in remembered_urls]
        resp, mdns_exc = self._try_urls(mdns_urls, method, path, **kwargs)
        if resp is not None:
            return resp

        raise GatewayUnreachableError(
            f"Could not reach gateway {self.gateway_id} via paired addresses or mDNS."
        ) from (mdns_exc or last_exc)

    def health(self) -> dict[str, Any]:
        resp = self._request("GET", "/health")
        resp.raise_for_status()
        return dict(resp.json())

    def redeem_pairing(self, redeem_request: dict[str, Any]) -> dict[str, Any]:
        resp = self._request("POST", "/v1/pairing/redeem", json=redeem_request)
        resp.raise_for_status()
        return dict(resp.json())

    def put_revision(
        self,
        record_id: str,
        revision: int,
        submission: dict[str, Any],
        *,
        device_token: str,
    ) -> httpx.Response:
        return self._request(
            "PUT",
            f"/v1/records/{record_id}/revisions/{revision}",
            json=submission,
            headers={"Authorization": f"Bearer {device_token}"},
        )
