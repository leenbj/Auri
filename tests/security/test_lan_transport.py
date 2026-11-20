"""Black-box security tests for Gateway LAN-only binding and TLS enforcement."""
from __future__ import annotations

import hashlib
import ipaddress
import pathlib
import socket
import ssl
import subprocess
import sys
import threading
import uuid
from datetime import datetime, timedelta, timezone

import httpx
import ifaddr
import pytest
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import Encoding, PrivateFormat, PublicFormat
from cryptography.x509.oid import NameOID

_INTEGRATION_DIR = pathlib.Path(__file__).resolve().parents[1] / "integration"
if str(_INTEGRATION_DIR) not in sys.path:
    sys.path.append(str(_INTEGRATION_DIR))

from harness import (  # noqa: E402
    REPO_ROOT,
    GatewayProcess,
    assert_valid_contract,
    find_free_port,
    make_submission,
    subprocess_env,
)


def _local_non_lan_ips() -> list[str]:
    ips: list[str] = []
    lan_nets = (
        ipaddress.ip_network("10.0.0.0/8"),
        ipaddress.ip_network("172.16.0.0/12"),
        ipaddress.ip_network("192.168.0.0/16"),
        ipaddress.ip_network("169.254.0.0/16"),
        ipaddress.ip_network("fc00::/7"),
    )
    for adapter in ifaddr.get_adapters():
        for info in adapter.ips:
            raw = info.ip[0] if isinstance(info.ip, tuple) else info.ip
            try:
                ip_obj = ipaddress.ip_address(raw)
            except ValueError:
                continue
            if ip_obj.is_loopback or ip_obj.is_link_local:
                continue
            if any(ip_obj in net for net in lan_nets):
                continue
            ips.append(str(ip_obj))
    return ips


def _local_lan_ipv4s() -> list[str]:
    ips: list[str] = []
    lan_nets = (
        ipaddress.ip_network("10.0.0.0/8"),
        ipaddress.ip_network("172.16.0.0/12"),
        ipaddress.ip_network("192.168.0.0/16"),
    )
    for adapter in ifaddr.get_adapters():
        for info in adapter.ips:
            raw = info.ip[0] if isinstance(info.ip, tuple) else info.ip
            try:
                ip_obj = ipaddress.ip_address(raw)
            except ValueError:
                continue
            if ip_obj.version == 4 and any(ip_obj in net for net in lan_nets):
                ips.append(str(ip_obj))
    return ips


@pytest.mark.parametrize(
    "disallowed_host",
    [
        "0.0.0.0",
        "::",
        "8.8.8.8",
        "203.0.113.10",
        "100.69.129.38",
        "2001:db8::1",
    ],
)
def test_gateway_refuses_non_lan_and_wildcard_bind_hosts(
    tmp_path: pathlib.Path, disallowed_host: str
) -> None:
    data_dir = tmp_path / "bad-host-data"
    res = subprocess.run(
        [
            sys.executable,
            "-m",
            "auri_gateway",
            "serve",
            "--data-dir",
            str(data_dir),
            "--host",
            disallowed_host,
            "--port",
            str(find_free_port()),
        ],
        cwd=str(REPO_ROOT),
        env=subprocess_env(),
        capture_output=True,
        text=True,
        timeout=3.0,
        check=False,
    )
    assert res.returncode != 0


def test_default_serve_binds_loopback_and_lan_only_never_vpn_or_public(
    tmp_path: pathlib.Path,
) -> None:
    port = find_free_port()
    gw = GatewayProcess(tmp_path / "default-lan-data", port=port, tls=True, host=None)
    gw.start()
    try:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE

        # Loopback must accept TLS.
        with socket.create_connection(("127.0.0.1", port), timeout=2.0) as sock:
            with ctx.wrap_socket(sock, server_hostname="127.0.0.1") as tls_sock:
                assert tls_sock.getpeercert(binary_form=True)

        # Any detected private LAN IPv4 on this machine must also accept TLS.
        for lan_ip in _local_lan_ipv4s():
            with socket.create_connection((lan_ip, port), timeout=2.0) as sock:
                with ctx.wrap_socket(sock, server_hostname=lan_ip) as tls_sock:
                    assert tls_sock.getpeercert(binary_form=True)

        # Any non-LAN / non-loopback local IP (e.g. Tailscale 100.64.0.0/10) must NOT be listening.
        for other_ip in _local_non_lan_ips():
            with pytest.raises(OSError):
                socket.create_connection((other_ip, port), timeout=1.0).close()
    finally:
        gw.stop()


def test_plaintext_mode_is_rejected_for_non_loopback_hosts(tmp_path: pathlib.Path) -> None:
    # Explicit LAN IP with --plaintext-loopback must fail before binding.
    lan_ip = (_local_lan_ipv4s() or ["192.168.1.20"])[0]
    res_explicit = subprocess.run(
        [
            sys.executable,
            "-m",
            "auri_gateway",
            "serve",
            "--data-dir",
            str(tmp_path / "pt-explicit"),
            "--host",
            lan_ip,
            "--port",
            str(find_free_port()),
            "--plaintext-loopback",
        ],
        cwd=str(REPO_ROOT),
        env=subprocess_env(),
        capture_output=True,
        text=True,
        timeout=3.0,
        check=False,
    )
    assert res_explicit.returncode != 0

    # Default multi-host LAN mode with --plaintext-loopback must also fail if any LAN IP is present.
    if _local_lan_ipv4s():
        res_default = subprocess.run(
            [
                sys.executable,
                "-m",
                "auri_gateway",
                "serve",
                "--data-dir",
                str(tmp_path / "pt-default"),
                "--port",
                str(find_free_port()),
                "--plaintext-loopback",
            ],
            cwd=str(REPO_ROOT),
            env=subprocess_env(),
            capture_output=True,
            text=True,
            timeout=3.0,
            check=False,
        )
        assert res_default.returncode != 0


def test_tls_gateway_rejects_plaintext_http_requests_on_loopback_and_lan(
    tmp_path: pathlib.Path,
) -> None:
    port = find_free_port()
    gw = GatewayProcess(tmp_path / "no-plaintext-data", port=port, tls=True, host=None)
    gw.start()
    try:
        targets = ["127.0.0.1", *_local_lan_ipv4s()]
        for target_ip in targets:
            with pytest.raises(httpx.HTTPError):
                httpx.get(f"http://{target_ip}:{port}/health", trust_env=False, timeout=2.0)

            with socket.create_connection((target_ip, port), timeout=2.0) as raw_sock:
                raw_sock.settimeout(2.0)
                raw_sock.sendall(
                    f"GET /health HTTP/1.1\r\nHost: {target_ip}\r\nConnection: close\r\n\r\n".encode("ascii")
                )
                try:
                    reply = raw_sock.recv(1024)
                except (ConnectionResetError, TimeoutError, OSError):
                    reply = b""
                assert b"200 OK" not in reply
                assert b'"status"' not in reply
    finally:
        gw.stop()


def _create_imposter_tls_files(dir_path: pathlib.Path) -> tuple[pathlib.Path, pathlib.Path, str]:
    dir_path.mkdir(parents=True, exist_ok=True)
    key = ec.generate_private_key(ec.SECP256R1())
    now = datetime.now(timezone.utc)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "imposter.local")])
    cert = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - timedelta(minutes=5))
        .not_valid_after(now + timedelta(days=1))
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .sign(key, hashes.SHA256())
    )
    key_path = dir_path / "imposter.key.pem"
    cert_path = dir_path / "imposter.cert.pem"
    key_path.write_bytes(
        key.private_bytes(
            Encoding.PEM, PrivateFormat.PKCS8, serialization.NoEncryption()
        )
    )
    cert_path.write_bytes(cert.public_bytes(Encoding.PEM))
    spki_fp = hashlib.sha256(
        cert.public_key().public_bytes(Encoding.DER, PublicFormat.SubjectPublicKeyInfo)
    ).hexdigest()
    return key_path, cert_path, spki_fp


def test_reference_client_rejects_mismatched_fingerprint_without_sending_http_and_has_no_bypass(
    tmp_path: pathlib.Path,
) -> None:
    gateway_src = str(REPO_ROOT / "services" / "gateway")
    if gateway_src not in sys.path:
        sys.path.insert(0, gateway_src)

    from auri_gateway.client import GatewayKeyMismatchError, PinnedGatewayClient

    imposter_key, imposter_cert, imposter_fp = _create_imposter_tls_files(tmp_path / "imposter")
    expected_genuine_fp = "0" * 64 if imposter_fp != "0" * 64 else "1" * 64

    received_plaintext: list[bytes] = []
    srv_ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    srv_ctx.load_cert_chain(certfile=str(imposter_cert), keyfile=str(imposter_key))

    listen_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listen_sock.bind(("127.0.0.1", 0))
    listen_sock.listen(5)
    imposter_port = listen_sock.getsockname()[1]

    stop_flag = threading.Event()

    def _serve_imposter() -> None:
        listen_sock.settimeout(0.2)
        while not stop_flag.is_set():
            try:
                conn, _ = listen_sock.accept()
            except TimeoutError:
                continue
            except OSError:
                break
            with conn:
                try:
                    with srv_ctx.wrap_socket(conn, server_side=True) as tls_conn:
                        tls_conn.settimeout(0.5)
                        data = tls_conn.recv(4096)
                        if data:
                            received_plaintext.append(data)
                except (ssl.SSLError, OSError, TimeoutError):
                    pass

    thread = threading.Thread(target=_serve_imposter, daemon=True)
    thread.start()
    try:
        # Must NOT accept any bypass / ignore options.
        for bad_kwarg in ("verify", "allow_mismatch", "ignore_tls_errors", "insecure"):
            with pytest.raises(TypeError):
                PinnedGatewayClient(
                    gateway_id=str(uuid.uuid4()),
                    addresses=[{"host": "127.0.0.1", "port": imposter_port}],
                    expected_public_key_sha256=expected_genuine_fp,
                    **{bad_kwarg: True},  # type: ignore[arg-type]
                )

        sub = make_submission(text="绝不能发给冒充网关的正文。")
        secret_token = "auri_dev_secret_token_must_never_leak_to_imposter"
        with PinnedGatewayClient(
            gateway_id=str(uuid.uuid4()),
            addresses=[{"host": "127.0.0.1", "port": imposter_port}],
            expected_public_key_sha256=expected_genuine_fp,
        ) as client:
            with pytest.raises(GatewayKeyMismatchError) as exc_info:
                client.put_revision(sub["record_id"], 1, sub, device_token=secret_token)

        err_payload = exc_info.value.to_error_payload()
        assert_valid_contract("error.schema.json", err_payload)
        assert err_payload["code"] == "GATEWAY_KEY_MISMATCH"
        assert err_payload["retryable"] is False
        assert secret_token not in err_payload["message"]

        # Zero HTTP bytes (no token, no body) may reach the imposter server.
        assert received_plaintext == []
    finally:
        stop_flag.set()
        listen_sock.close()
        thread.join(timeout=2.0)


