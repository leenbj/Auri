"""Black-box integration tests for CLI pairing codes and token hashing."""
from __future__ import annotations

import json
import pathlib
import sqlite3
import time

import httpx
import pytest

from harness import (
    GatewayProcess,
    assert_valid_contract,
    gateway,  # noqa: F401
    make_redeem_request,
    sha256_hex,
)


def test_pairing_code_redeem_is_one_time_and_plain_cli_works(gateway: GatewayProcess) -> None:
    # Plain CLI mode emits a readable pairing code without errors.
    plain = gateway.run_cli("pair", "--data-dir", str(gateway.data_dir))
    assert plain.returncode == 0
    assert "Pairing code" in plain.stdout

    code = gateway.issue_pairing_code(ttl_seconds=60)
    req = make_redeem_request(code, display_name="My FreeClip Phone")
    assert_valid_contract("pairing.schema.json#/$defs/redeem_request", req)

    resp = httpx.post(f"{gateway.base_url}/v1/pairing/redeem", json=req, timeout=5.0)
    assert resp.status_code == 200
    body = resp.json()
    assert_valid_contract("pairing.schema.json#/$defs/redeem_response", body)
    assert len(body["device_token"]) >= 32

    # Second redeem attempt with the same pairing code must fail.
    reuse_resp = httpx.post(f"{gateway.base_url}/v1/pairing/redeem", json=req, timeout=5.0)
    assert reuse_resp.status_code == 401
    reuse_body = reuse_resp.json()
    assert_valid_contract("error.schema.json", reuse_body)
    assert reuse_body["code"] == "PAIRING_CODE_INVALID"


def test_expired_pairing_code_cannot_be_redeemed(gateway: GatewayProcess) -> None:
    code = gateway.issue_pairing_code(ttl_seconds=1)
    time.sleep(1.2)
    req = make_redeem_request(code, display_name="Late Phone")
    assert_valid_contract("pairing.schema.json#/$defs/redeem_request", req)

    resp = httpx.post(f"{gateway.base_url}/v1/pairing/redeem", json=req, timeout=5.0)
    assert resp.status_code == 401
    body = resp.json()
    assert_valid_contract("error.schema.json", body)
    assert body["code"] == "PAIRING_CODE_INVALID"


def test_tokens_and_pairing_codes_are_hashed_at_rest_and_never_logged(
    tmp_path: pathlib.Path,
) -> None:
    data_dir = tmp_path / "hash-check"
    gw = GatewayProcess(data_dir)
    gw.start()
    try:
        code = gw.issue_pairing_code(ttl_seconds=60)
        req = make_redeem_request(code, display_name="Security Audit Phone")
        resp = httpx.post(f"{gw.base_url}/v1/pairing/redeem", json=req, timeout=5.0)
        assert resp.status_code == 200
        device_token = resp.json()["device_token"]
        device_id = resp.json()["device_id"]
    finally:
        stdout, stderr = gw.stop()

    # Plaintext secrets must never appear in gateway stdout/stderr logs.
    combined_logs = stdout + "\n" + stderr
    assert code not in combined_logs
    assert device_token not in combined_logs

    # Plaintext secrets must not exist anywhere in the SQLite file; only SHA-256 hashes are stored.
    db_path = pathlib.Path(data_dir) / "gateway.db"
    raw_db_bytes = db_path.read_bytes()
    assert code.encode("utf-8") not in raw_db_bytes
    assert device_token.encode("utf-8") not in raw_db_bytes

    with sqlite3.connect(db_path) as conn:
        device_row = conn.execute(
            "SELECT token_hash FROM devices WHERE device_id = ?", (device_id,)
        ).fetchone()
        assert device_row is not None
        assert device_row[0] == sha256_hex(device_token)

        code_row = conn.execute(
            "SELECT code_hash FROM pairing_codes WHERE redeemed_device_id = ?", (device_id,)
        ).fetchone()
        assert code_row is not None
        assert code_row[0] == sha256_hex(code)


@pytest.mark.parametrize("bad_ttl", ["0", "-5", "3601", "86400"])
def test_pairing_cli_rejects_out_of_range_ttl_seconds(
    gateway: GatewayProcess, bad_ttl: str
) -> None:
    res = gateway.run_cli(
        "pair",
        "--data-dir",
        str(gateway.data_dir),
        "--ttl-seconds",
        bad_ttl,
        "--json",
    )
    assert res.returncode != 0
    assert res.stdout == ""
    assert "Traceback" not in res.stderr
    assert "3600" in res.stderr


def test_pairing_cli_accepts_upper_bound_ttl_3600(gateway: GatewayProcess) -> None:
    res = gateway.run_cli(
        "pair",
        "--data-dir",
        str(gateway.data_dir),
        "--ttl-seconds",
        "3600",
        "--json",
    )
    assert res.returncode == 0, res.stderr
    payload = json.loads(res.stdout)
    assert_valid_contract("pairing.schema.json#/$defs/payload", payload)
