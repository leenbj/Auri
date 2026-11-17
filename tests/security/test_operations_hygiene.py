"""Seam 2 (gateway black box): operational CLI outputs and logs never leak text or credentials (Ticket 07)."""
from __future__ import annotations

import hashlib
import json
import os
import pathlib
import subprocess
import sys
import uuid

_INTEGRATION_DIR = pathlib.Path(__file__).resolve().parents[1] / "integration"
if str(_INTEGRATION_DIR) not in sys.path:
    sys.path.append(str(_INTEGRATION_DIR))

from harness import (  # noqa: E402
    REPO_ROOT,
    GatewayProcess,
    McpAgent,
    find_free_port,
    subprocess_env,
)


def _sha256_hex(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def test_operational_cli_and_server_logs_never_contain_text_credentials_or_hashes(
    tmp_path: pathlib.Path,
) -> None:
    data_dir = tmp_path / "hygiene-gw"
    restored_dir = tmp_path / "hygiene-restored"
    backup_file = tmp_path / "hygiene-backup.tar.gz"

    gw = GatewayProcess(data_dir)
    gw.start()
    cli_outputs: list[str] = []
    try:
        phone = gw.pair_device("Hygiene Phone")
        dest = gw.create_agent_destination(phone["device_token"], "Hygiene Agent")
        agent = McpAgent(gw.base_url, dest.credential)

        secret_text_1 = f"绝密口述正文一-{uuid.uuid4()}"
        secret_text_2 = f"绝密口述正文二-{uuid.uuid4()}"
        secret_note = f"绝密确认备注-{uuid.uuid4()}"

        gw.publish(phone["device_token"], [dest.destination_id], text=secret_text_1)
        gw.publish(phone["device_token"], [dest.destination_id], text=secret_text_2)

        claimed = agent.claim()["deliveries"]
        assert len(claimed) == 2
        agent.ack(claimed[0]["delivery_id"], claimed[0]["lease_token"], note=secret_note)

        # Exercise every operational CLI command (both human-readable and --json).
        for args in (
            ("list-devices", "--data-dir", str(data_dir)),
            ("list-devices", "--data-dir", str(data_dir), "--json"),
        ):
            res = gw.run_cli(*args)
            assert res.returncode == 0, res.stderr
            cli_outputs.append(res.stdout + res.stderr)

        listing = json.loads(
            gw.run_cli("list-devices", "--data-dir", str(data_dir), "--json").stdout
        )
        cred_id = listing["devices"][0]["credentials"][0]["credential_id"]

        for args in (
            ("revoke-credential", "--data-dir", str(data_dir), "--credential-id", cred_id),
            ("revoke-device", "--data-dir", str(data_dir), "--device-id", phone["device_id"]),
            ("delete-device-records", "--data-dir", str(data_dir), "--device-id", phone["device_id"]),
            ("delete-device-records", "--data-dir", str(data_dir), "--device-id", phone["device_id"], "--json"),
            ("backup", "--data-dir", str(data_dir), "--output", str(backup_file)),
            ("restore", "--backup", str(backup_file), "--data-dir", str(restored_dir)),
        ):
            res = gw.run_cli(*args)
            assert res.returncode == 0, res.stderr
            cli_outputs.append(res.stdout + res.stderr)
    finally:
        stdout, stderr = gw.stop()

    # Also test failed migration CLI output.
    real_migrations = REPO_ROOT / "services" / "gateway" / "auri_gateway" / "migrations"
    broken_dir = tmp_path / "broken-mig"
    broken_dir.mkdir()
    for p in sorted(real_migrations.glob("*.sql")):
        (broken_dir / p.name).write_text(p.read_text(encoding="utf-8"), encoding="utf-8")
    (broken_dir / "9999_broken.sql").write_text(
        "ALTER TABLE nonexistent_table ADD COLUMN x TEXT;\n", encoding="utf-8"
    )
    env = subprocess_env()
    env["AURI_GATEWAY_MIGRATIONS_DIR"] = str(broken_dir)
    failed = subprocess.run(
        [
            sys.executable,
            "-m",
            "auri_gateway",
            "serve",
            "--data-dir",
            str(data_dir),
            "--host",
            "127.0.0.1",
            "--port",
            str(find_free_port()),
            "--plaintext-loopback",
            "--no-mdns",
        ],
        cwd=str(REPO_ROOT),
        env=env,
        capture_output=True,
        text=True,
        timeout=10,
        check=False,
    )
    assert failed.returncode != 0
    cli_outputs.append(failed.stdout + failed.stderr)

    all_output = stdout + stderr + "\n".join(cli_outputs)
    assert phone["device_id"] in all_output
    assert cred_id in all_output

    secrets = {
        "secret_text_1": secret_text_1,
        "secret_text_2": secret_text_2,
        "secret_note": secret_note,
        "device_token": phone["device_token"],
        "device_token_hash": _sha256_hex(phone["device_token"]),
        "destination_credential": dest.credential,
        "destination_credential_hash": _sha256_hex(dest.credential),
        "lease_token_0": claimed[0]["lease_token"],
        "lease_token_0_hash": _sha256_hex(claimed[0]["lease_token"]),
        "lease_token_1": claimed[1]["lease_token"],
        "lease_token_1_hash": _sha256_hex(claimed[1]["lease_token"]),
    }
    for label, secret in secrets.items():
        assert secret not in all_output, f"Operational output or log leaked {label}"
