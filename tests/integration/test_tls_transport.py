"""Black-box integration tests for Gateway TLS, pairing payload, mDNS, and reference client."""
from __future__ import annotations

import hashlib
import json
import pathlib
import socket
import ssl
import sys
import threading

from cryptography import x509
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
from zeroconf import InterfaceChoice, ServiceBrowser, ServiceListener, Zeroconf

from harness import (
    REPO_ROOT,
    GatewayProcess,
    assert_valid_contract,
    find_free_port,
    make_redeem_request,
    make_submission,
)

if str(REPO_ROOT / "services" / "gateway") not in sys.path:
    sys.path.insert(0, str(REPO_ROOT / "services" / "gateway"))


def _fetch_server_cert(host: str, port: int) -> tuple[x509.Certificate, str]:
    """Perform a raw TLS handshake and return (parsed X.509 cert, SPKI SHA-256 hex)."""
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    with socket.create_connection((host, port), timeout=5.0) as raw_sock:
        with ctx.wrap_socket(raw_sock, server_hostname=host) as tls_sock:
            der_bytes = tls_sock.getpeercert(binary_form=True)
            assert der_bytes is not None
    cert = x509.load_der_x509_certificate(der_bytes)
    spki_der = cert.public_key().public_bytes(Encoding.DER, PublicFormat.SubjectPublicKeyInfo)
    spki_sha256 = hashlib.sha256(spki_der).hexdigest()
    return cert, spki_sha256


def test_tls_key_reused_across_restarts_and_cert_renewal_preserves_fingerprint(
    tmp_path: pathlib.Path,
) -> None:
    data_dir = tmp_path / "tls-data"

    # First startup generates key and self-signed cert.
    gw1 = GatewayProcess(data_dir, tls=True)
    gw1.start()
    try:
        cert1, fp1 = _fetch_server_cert("127.0.0.1", gw1.port)
    finally:
        stdout1, stderr1 = gw1.stop()

    key_path = data_dir / "tls" / "gateway.key.pem"
    cert_path = data_dir / "tls" / "gateway.cert.pem"
    assert key_path.exists()
    assert cert_path.exists()
    assert key_path.read_text(encoding="utf-8") not in (stdout1 + stderr1)
    assert "BEGIN PRIVATE KEY" not in (stdout1 + stderr1)
    key_bytes_1 = key_path.read_bytes()

    # Second startup reuses the same key and cert.
    gw2 = GatewayProcess(data_dir, tls=True)
    gw2.start()
    try:
        cert2, fp2 = _fetch_server_cert("127.0.0.1", gw2.port)
    finally:
        gw2.stop()

    assert fp2 == fp1
    assert cert2.serial_number == cert1.serial_number
    assert key_path.read_bytes() == key_bytes_1

    # Renew certificate using the same private key -> new cert, identical SPKI fingerprint.
    renew_res = gw2.run_cli("renew-cert", "--data-dir", str(data_dir), "--validity-days", "180", "--json")
    assert renew_res.returncode == 0, renew_res.stderr
    renew_info = json.loads(renew_res.stdout)
    assert renew_info["public_key_sha256"] == fp1
    assert key_path.read_bytes() == key_bytes_1

    gw3 = GatewayProcess(data_dir, tls=True)
    gw3.start()
    try:
        cert3, fp3 = _fetch_server_cert("127.0.0.1", gw3.port)
    finally:
        stdout3, stderr3 = gw3.stop()

    assert cert3.serial_number != cert1.serial_number
    assert fp3 == fp1
    assert "BEGIN PRIVATE KEY" not in (stdout3 + stderr3)


def test_pairing_payload_matches_contract_and_renders_qr(tmp_path: pathlib.Path) -> None:
    gw = GatewayProcess(tmp_path / "pair-payload-data", tls=True)
    gw.start()
    try:
        _, live_fp = _fetch_server_cert("127.0.0.1", gw.port)

        res_json = gw.run_cli("pair", "--data-dir", str(gw.data_dir), "--ttl-seconds", "120", "--json")
        assert res_json.returncode == 0, res_json.stderr
        payload = json.loads(res_json.stdout)

        assert_valid_contract("pairing.schema.json#/$defs/payload", payload)
        assert payload["schema_version"] == "1.2"
        assert payload["mdns_service_type"] == "_auri-gateway._tcp"
        assert payload["public_key_sha256"] == live_fp
        assert {"host": "127.0.0.1", "port": gw.port} in payload["addresses"]

        # QR / plain CLI mode emits pairing code + scannable QR block characters.
        res_qr = gw.run_cli("pair", "--data-dir", str(gw.data_dir), "--qr")
        assert res_qr.returncode == 0, res_qr.stderr
        assert "Pairing code" in res_qr.stdout
        assert live_fp in res_qr.stdout
        assert any(ch in res_qr.stdout for ch in ("█", "▀", "▄"))
    finally:
        gw.stop()


def test_gateway_advertises_mdns_service_and_client_discovers_it(tmp_path: pathlib.Path) -> None:
    port = find_free_port()
    gw = GatewayProcess(tmp_path / "mdns-data", port=port, tls=True, host=None, mdns=True)
    gw.start()
    try:
        pair_res = gw.run_cli("pair", "--data-dir", str(gw.data_dir), "--json")
        assert pair_res.returncode == 0, pair_res.stderr
        payload = json.loads(pair_res.stdout)
        target_gateway_id = payload["gateway_id"]

        discovered: list[tuple[str, int, list[str], dict[bytes, bytes | None]]] = []
        found_event = threading.Event()

        class _Listener(ServiceListener):
            def add_service(self, zc: Zeroconf, type_: str, name: str) -> None:
                info = zc.get_service_info(type_, name, timeout=2000)
                if info is None:
                    return
                gw_id_bytes = (info.properties or {}).get(b"gateway_id")
                if gw_id_bytes and gw_id_bytes.decode("utf-8") == target_gateway_id:
                    discovered.append(
                        (name, int(info.port), info.parsed_addresses(), dict(info.properties))
                    )
                    found_event.set()

            def remove_service(self, zc: Zeroconf, type_: str, name: str) -> None:
                pass

            def update_service(self, zc: Zeroconf, type_: str, name: str) -> None:
                self.add_service(zc, type_, name)

        zc = Zeroconf(interfaces=InterfaceChoice.All)
        try:
            _browser = ServiceBrowser(zc, "_auri-gateway._tcp.local.", _Listener())
            assert found_event.wait(5.0), "Gateway mDNS service was not discovered within 5s"
        finally:
            zc.close()

        svc_name, svc_port, svc_addrs, svc_props = discovered[0]
        assert svc_name.endswith("._auri-gateway._tcp.local.")
        assert svc_port == port
        assert len(svc_addrs) >= 1
        assert svc_props[b"gateway_id"].decode("utf-8") == target_gateway_id

        # Discovered address + port accepts TLS and matches the paired SPKI fingerprint.
        _, discovered_fp = _fetch_server_cert(svc_addrs[0], svc_port)
        assert discovered_fp == payload["public_key_sha256"]
    finally:
        gw.stop()


def test_reference_client_connects_pairs_submits_and_survives_cert_renewal(
    tmp_path: pathlib.Path,
) -> None:
    from auri_gateway.client import PinnedGatewayClient

    data_dir = tmp_path / "ref-client-data"
    port = find_free_port()
    gw = GatewayProcess(data_dir, port=port, tls=True, host="127.0.0.1")
    gw.start()
    try:
        pair_res = gw.run_cli("pair", "--data-dir", str(data_dir), "--json")
        assert pair_res.returncode == 0, pair_res.stderr
        payload = json.loads(pair_res.stdout)
        assert_valid_contract("pairing.schema.json#/$defs/payload", payload)

        with PinnedGatewayClient.from_pairing_payload(payload) as client:
            assert client.health() == {"status": "ok"}

            redeem_req = make_redeem_request(payload["pairing_code"], display_name="Pinned Phone")
            assert_valid_contract("pairing.schema.json#/$defs/redeem_request", redeem_req)
            redeem_resp = client.redeem_pairing(redeem_req)
            assert_valid_contract("pairing.schema.json#/$defs/redeem_response", redeem_resp)
            token = redeem_resp["device_token"]

            sub1 = make_submission(revision=1, text="TLS 局域网首条记录。")
            resp1 = client.put_revision(sub1["record_id"], 1, sub1, device_token=token)
            assert resp1.status_code == 200
            assert_valid_contract("revision.schema.json#/$defs/stored", resp1.json())
    finally:
        gw.stop()

    # Renew cert (same private key -> same SPKI fingerprint), restart on same port.
    renew_res = gw.run_cli("renew-cert", "--data-dir", str(data_dir), "--json")
    assert renew_res.returncode == 0, renew_res.stderr
    assert json.loads(renew_res.stdout)["public_key_sha256"] == payload["public_key_sha256"]

    gw2 = GatewayProcess(data_dir, port=port, tls=True, host="127.0.0.1")
    gw2.start()
    try:
        with PinnedGatewayClient.from_pairing_payload(payload) as client:
            sub2 = make_submission(record_id=sub1["record_id"], revision=2, text="续签证书后继续同步。", source="user_edit")
            resp2 = client.put_revision(sub2["record_id"], 2, sub2, device_token=token)
            assert resp2.status_code == 200
            body2 = resp2.json()
            assert_valid_contract("revision.schema.json#/$defs/stored", body2)
            assert body2["superseded_revisions"] == [1]
    finally:
        gw2.stop()


def test_reference_client_falls_back_to_mdns_when_remembered_address_is_unreachable(
    tmp_path: pathlib.Path,
) -> None:
    from auri_gateway.client import PinnedGatewayClient

    port = find_free_port()
    dead_port = find_free_port()
    while dead_port == port:
        dead_port = find_free_port()

    gw = GatewayProcess(tmp_path / "mdns-fallback-data", port=port, tls=True, host=None, mdns=True)
    gw.start()
    try:
        pair_res = gw.run_cli("pair", "--data-dir", str(gw.data_dir), "--json")
        assert pair_res.returncode == 0, pair_res.stderr
        payload = json.loads(pair_res.stdout)

        with PinnedGatewayClient(
            gateway_id=payload["gateway_id"],
            addresses=[{"host": "127.0.0.1", "port": dead_port}],
            expected_public_key_sha256=payload["public_key_sha256"],
        ) as client:
            assert client.health() == {"status": "ok"}
    finally:
        gw.stop()


def test_operations_doc_covers_validity_and_renewal_and_custom_validity_works(
    tmp_path: pathlib.Path,
) -> None:
    ops_doc = REPO_ROOT / "services" / "gateway" / "OPERATIONS.md"
    assert ops_doc.exists()
    content = ops_doc.read_text(encoding="utf-8")
    assert "365" in content
    assert "renew-cert" in content
    assert "--validity-days" in content
    assert "public_key_sha256" in content

    data_dir = tmp_path / "ops-renew-data"
    gw = GatewayProcess(data_dir, tls=True)
    gw.start()
    try:
        _, fp_before = _fetch_server_cert("127.0.0.1", gw.port)
    finally:
        gw.stop()

    res = gw.run_cli("renew-cert", "--data-dir", str(data_dir), "--validity-days", "90", "--json")
    assert res.returncode == 0, res.stderr
    info = json.loads(res.stdout)
    assert info["public_key_sha256"] == fp_before

    gw2 = GatewayProcess(data_dir, tls=True)
    gw2.start()
    try:
        cert_after, fp_after = _fetch_server_cert("127.0.0.1", gw2.port)
        assert fp_after == fp_before
        delta_days = (cert_after.not_valid_after_utc - cert_after.not_valid_before_utc).days
        assert 89 <= delta_days <= 91
    finally:
        gw2.stop()


