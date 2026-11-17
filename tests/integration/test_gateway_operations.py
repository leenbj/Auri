"""Black-box integration tests for Gateway CLI operations, backup/restore, and upgrades (Ticket 07)."""
from __future__ import annotations

import asyncio
import contextlib
import json
import os
import pathlib
import sqlite3
import stat
import subprocess
import sys
import uuid

import httpx
from mcp import Client, StdioServerParameters

from harness import (
    REPO_ROOT,
    GatewayProcess,
    McpAgent,
    assert_valid_contract,
    find_free_port,
    gateway,  # noqa: F401
    load_fixture,
    make_redeem_request,
    make_submission,
    subprocess_env,
)

if str(REPO_ROOT / "services" / "gateway") not in sys.path:
    sys.path.insert(0, str(REPO_ROOT / "services" / "gateway"))

BRIDGE = REPO_ROOT / "packages" / "mcp-stdio-bridge" / "bridge.py"


def test_cli_list_devices_and_per_device_credentials_without_secrets(
    gateway: GatewayProcess,
) -> None:
    phone_a = gateway.pair_device("Phone Alpha")
    phone_b = gateway.pair_device("Phone Beta")

    dest_a1 = gateway.create_agent_destination(phone_a["device_token"], "Hermes Alpha")
    dest_a2 = gateway.create_agent_destination(phone_a["device_token"], "Claude Alpha")
    dest_b1 = gateway.create_agent_destination(phone_b["device_token"], "Hermes Beta")

    result = gateway.run_cli("list-devices", "--data-dir", str(gateway.data_dir), "--json")
    assert result.returncode == 0, result.stderr
    payload = json.loads(result.stdout)

    devices_by_id = {d["device_id"]: d for d in payload["devices"]}
    assert set(devices_by_id) == {phone_a["device_id"], phone_b["device_id"]}

    entry_a = devices_by_id[phone_a["device_id"]]
    assert entry_a["display_name"] == "Phone Alpha"
    assert entry_a["state"] == "active"
    assert entry_a["paired_at"]
    assert entry_a["revoked_at"] is None
    creds_a = {c["destination_id"]: c for c in entry_a["credentials"]}
    assert set(creds_a) == {dest_a1.destination_id, dest_a2.destination_id}
    assert creds_a[dest_a1.destination_id]["destination_display_name"] == "Hermes Alpha"
    assert creds_a[dest_a1.destination_id]["state"] == "active"
    assert creds_a[dest_a1.destination_id]["issued_at"]
    assert creds_a[dest_a1.destination_id]["revoked_at"] is None
    assert creds_a[dest_a1.destination_id]["credential_id"]

    entry_b = devices_by_id[phone_b["device_id"]]
    assert entry_b["display_name"] == "Phone Beta"
    assert [c["destination_id"] for c in entry_b["credentials"]] == [dest_b1.destination_id]

    # Human-readable output also lists devices and credentials without secrets.
    text_result = gateway.run_cli("list-devices", "--data-dir", str(gateway.data_dir))
    assert text_result.returncode == 0, text_result.stderr
    combined = result.stdout + text_result.stdout
    for id_or_name in (
        phone_a["device_id"],
        phone_b["device_id"],
        "Phone Alpha",
        "Phone Beta",
        creds_a[dest_a1.destination_id]["credential_id"],
        dest_a1.destination_id,
    ):
        assert id_or_name in combined
    for secret in (
        phone_a["device_token"],
        phone_b["device_token"],
        dest_a1.credential,
        dest_a2.credential,
        dest_b1.credential,
    ):
        assert secret not in combined
    assert "token_hash" not in combined


def test_cli_revoke_device_leaves_credentials_active_and_rejects_device_token(
    gateway: GatewayProcess,
) -> None:
    phone_a = gateway.pair_device("Lost Phone")
    phone_b = gateway.pair_device("Kept Phone")
    dest_a = gateway.create_agent_destination(phone_a["device_token"], "Agent From Lost")
    published = gateway.publish(
        phone_a["device_token"],
        [dest_a.destination_id],
        text="手机丢失前发给 Agent 的内容。",
    )

    revoke_res = gateway.run_cli(
        "revoke-device",
        "--data-dir",
        str(gateway.data_dir),
        "--device-id",
        phone_a["device_id"],
        "--json",
    )
    assert revoke_res.returncode == 0, revoke_res.stderr
    revoked_info = json.loads(revoke_res.stdout)
    assert revoked_info["device_id"] == phone_a["device_id"]
    assert revoked_info["state"] == "revoked"
    assert revoked_info["revoked_at"]

    # Repeating revoke-device is idempotent and keeps the original timestamp.
    repeat_res = gateway.run_cli(
        "revoke-device",
        "--data-dir",
        str(gateway.data_dir),
        "--device-id",
        phone_a["device_id"],
        "--json",
    )
    assert repeat_res.returncode == 0, repeat_res.stderr
    assert json.loads(repeat_res.stdout)["revoked_at"] == revoked_info["revoked_at"]

    # Revoked device token is rejected with GATEWAY_AUTH_REVOKED on the next HTTP request.
    sub = make_submission(destination_ids=[dest_a.destination_id], text="丢失后不能再提交。")
    put_resp = httpx.put(
        f"{gateway.base_url}/v1/records/{sub['record_id']}/revisions/1",
        json=sub,
        headers={"Authorization": f"Bearer {phone_a['device_token']}"},
        timeout=5.0,
    )
    assert put_resp.status_code == 401
    assert_valid_contract("error.schema.json", put_resp.json())
    assert put_resp.json()["code"] == "GATEWAY_AUTH_REVOKED"

    # Revoking the device did NOT revoke the Destination credential it created.
    listing = json.loads(
        gateway.run_cli("list-devices", "--data-dir", str(gateway.data_dir), "--json").stdout
    )
    by_id = {d["device_id"]: d for d in listing["devices"]}
    assert by_id[phone_a["device_id"]]["state"] == "revoked"
    assert by_id[phone_a["device_id"]]["credentials"][0]["state"] == "active"
    assert by_id[phone_b["device_id"]]["state"] == "active"

    agent_a = McpAgent(gateway.base_url, dest_a.credential)
    claimed = agent_a.claim()["deliveries"]
    assert len(claimed) == 1
    assert claimed[0]["delivery_id"] == published["deliveries"][0]["delivery_id"]
    ack_res = agent_a.ack(claimed[0]["delivery_id"], claimed[0]["lease_token"])
    assert ack_res["state"] == "ACKNOWLEDGED"

    # Kept phone still works normally.
    kept_sub = gateway.publish(phone_b["device_token"], [], text="另一台手机正常。")
    assert kept_sub["duplicate"] is False

    # Unknown device id is rejected cleanly.
    bad_res = gateway.run_cli(
        "revoke-device",
        "--data-dir",
        str(gateway.data_dir),
        "--device-id",
        str(uuid.uuid4()),
    )
    assert bad_res.returncode != 0


def test_cli_revoke_credential_blocks_only_that_credential(
    gateway: GatewayProcess,
) -> None:
    phone = gateway.pair_device("Owner Phone")
    dest = gateway.create_agent_destination(phone["device_token"], "Target Agent")
    gateway.publish(phone["device_token"], [dest.destination_id], text="等待新凭证领取。")

    listing = json.loads(
        gateway.run_cli("list-devices", "--data-dir", str(gateway.data_dir), "--json").stdout
    )
    cred_id = listing["devices"][0]["credentials"][0]["credential_id"]

    revoke_res = gateway.run_cli(
        "revoke-credential",
        "--data-dir",
        str(gateway.data_dir),
        "--credential-id",
        cred_id,
        "--json",
    )
    assert revoke_res.returncode == 0, revoke_res.stderr
    cred_info = json.loads(revoke_res.stdout)
    assert cred_info["credential_id"] == cred_id
    assert cred_info["state"] == "revoked"
    assert cred_info["revoked_at"]

    # Repeating revoke-credential is idempotent.
    repeat_res = gateway.run_cli(
        "revoke-credential",
        "--data-dir",
        str(gateway.data_dir),
        "--credential-id",
        cred_id,
        "--json",
    )
    assert repeat_res.returncode == 0
    assert json.loads(repeat_res.stdout)["revoked_at"] == cred_info["revoked_at"]

    # Revoked credential is rejected on /mcp with GATEWAY_AUTH_REVOKED.
    mcp_resp = httpx.post(
        f"{gateway.base_url}/mcp",
        json={"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "delivery_claim", "arguments": {}}},
        headers={
            "Authorization": f"Bearer {dest.credential}",
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
        },
        timeout=5.0,
    )
    assert mcp_resp.status_code == 401
    assert_valid_contract("error.schema.json", mcp_resp.json())
    assert mcp_resp.json()["code"] == "GATEWAY_AUTH_REVOKED"

    # The device and Destination are NOT revoked: device can issue a replacement credential and claim!
    rotated = httpx.post(
        f"{gateway.base_url}/v1/destinations/{dest.destination_id}/credential",
        headers={"Authorization": f"Bearer {phone['device_token']}"},
        timeout=5.0,
    )
    assert rotated.status_code == 200, rotated.text
    new_cred = rotated.json()["credential"]
    claims = McpAgent(gateway.base_url, new_cred).claim()["deliveries"]
    assert len(claims) == 1

    # Unknown credential id fails cleanly.
    bad_res = gateway.run_cli(
        "revoke-credential",
        "--data-dir",
        str(gateway.data_dir),
        "--credential-id",
        str(uuid.uuid4()),
    )
    assert bad_res.returncode != 0


def test_cli_delete_device_records_requires_revoked_device_and_matches_device_delete(
    gateway: GatewayProcess,
) -> None:
    lost_phone = gateway.pair_device("Lost Phone")
    other_phone = gateway.pair_device("Other Phone")

    acked_dest = gateway.create_agent_destination(lost_phone["device_token"], "Acked Agent")
    leased_dest = gateway.create_agent_destination(lost_phone["device_token"], "Leased Agent")
    pending_dest = gateway.create_agent_destination(lost_phone["device_token"], "Pending Agent")
    rejected_dest = gateway.create_agent_destination(lost_phone["device_token"], "Rejected Agent")

    acked_agent = McpAgent(gateway.base_url, acked_dest.credential)
    leased_agent = McpAgent(gateway.base_url, leased_dest.credential)
    pending_agent = McpAgent(gateway.base_url, pending_dest.credential)
    rejected_agent = McpAgent(gateway.base_url, rejected_dest.credential)

    record_1 = str(uuid.uuid4())
    pub_1 = gateway.publish(
        lost_phone["device_token"],
        [
            acked_dest.destination_id,
            leased_dest.destination_id,
            pending_dest.destination_id,
            rejected_dest.destination_id,
        ],
        record_id=record_1,
        revision=1,
        text="丢失手机第一条口述。",
    )
    by_dest_1 = {d["destination_id"]: d["delivery_id"] for d in pub_1["deliveries"]}

    (acked_claim,) = acked_agent.claim()["deliveries"]
    first_ack = acked_agent.ack(acked_claim["delivery_id"], acked_claim["lease_token"], note="已收到")

    (rejected_claim,) = rejected_agent.claim()["deliveries"]
    rejected_agent.nack(
        rejected_claim["delivery_id"],
        rejected_claim["lease_token"],
        retryable=False,
        reason="不需要",
    )

    (leased_claim,) = leased_agent.claim()["deliveries"]

    # Record 2 has two revisions so rev 1 delivery is already cancelled/superseded, rev 2 is pending.
    record_2 = str(uuid.uuid4())
    gateway.publish(
        lost_phone["device_token"],
        [pending_dest.destination_id],
        record_id=record_2,
        revision=1,
        text="丢失手机第二条初版。",
    )
    pub_2_v2 = gateway.publish(
        lost_phone["device_token"],
        [pending_dest.destination_id],
        record_id=record_2,
        revision=2,
        text="丢失手机第二条修订版。",
    )
    rev2_pending_id = next(
        d["delivery_id"] for d in pub_2_v2["deliveries"] if d["revision"] == 2
    )

    # Other phone's record must stay untouched.
    other_pub = gateway.publish(
        other_phone["device_token"],
        [pending_dest.destination_id],
        text="另一台正常手机的记录。",
    )
    other_delivery_id = other_pub["deliveries"][0]["delivery_id"]

    # Refused while the device is still active.
    refused = gateway.run_cli(
        "delete-device-records",
        "--data-dir",
        str(gateway.data_dir),
        "--device-id",
        lost_phone["device_id"],
    )
    assert refused.returncode != 0
    assert "revoked" in refused.stderr.lower()

    # Revoke the device, then delete its records.
    assert (
        gateway.run_cli(
            "revoke-device",
            "--data-dir",
            str(gateway.data_dir),
            "--device-id",
            lost_phone["device_id"],
        ).returncode
        == 0
    )

    deleted_res = gateway.run_cli(
        "delete-device-records",
        "--data-dir",
        str(gateway.data_dir),
        "--device-id",
        lost_phone["device_id"],
        "--json",
    )
    assert deleted_res.returncode == 0, deleted_res.stderr
    deleted_payload = json.loads(deleted_res.stdout)
    assert deleted_payload["device_id"] == lost_phone["device_id"]
    assert set(deleted_payload["deleted_record_ids"]) == {record_1, record_2}
    assert set(deleted_payload["cancelled_delivery_ids"]) == {
        by_dest_1[leased_dest.destination_id],
        by_dest_1[pending_dest.destination_id],
        rev2_pending_id,
    }
    assert "丢失手机" not in deleted_res.stdout

    # Pending agent only sees other_phone's delivery.
    remaining = pending_agent.claim()["deliveries"]
    assert [d["delivery_id"] for d in remaining] == [other_delivery_id]

    # Leased agent holding the cancelled lease gets REQUEST_INVALID (non-retryable), same as HTTP DELETE.
    for tool, args in (
        (
            "delivery_renew_lease",
            {"delivery_id": leased_claim["delivery_id"], "lease_token": leased_claim["lease_token"]},
        ),
        (
            "delivery_ack",
            {"delivery_id": leased_claim["delivery_id"], "lease_token": leased_claim["lease_token"]},
        ),
        (
            "delivery_nack",
            {
                "delivery_id": leased_claim["delivery_id"],
                "lease_token": leased_claim["lease_token"],
                "reason": "太晚了",
                "retryable": True,
            },
        ),
    ):
        err = leased_agent.call_error(tool, args)
        assert err["code"] == "REQUEST_INVALID", tool
        assert err["retryable"] is False

    # Acknowledged delivery is kept (idempotent repeat ack succeeds with original timestamp).
    repeat_ack = acked_agent.ack(acked_claim["delivery_id"], acked_claim["lease_token"])
    assert repeat_ack["state"] == "ACKNOWLEDGED"
    assert repeat_ack["duplicate"] is True
    assert repeat_ack["acknowledged_at"] == first_ack["acknowledged_at"]

    # Repeating delete-device-records is idempotent.
    repeat_del = gateway.run_cli(
        "delete-device-records",
        "--data-dir",
        str(gateway.data_dir),
        "--device-id",
        lost_phone["device_id"],
        "--json",
    )
    assert repeat_del.returncode == 0, repeat_del.stderr
    assert json.loads(repeat_del.stdout)["deleted_record_ids"] == []
    assert json.loads(repeat_del.stdout)["cancelled_delivery_ids"] == []


def test_backup_while_running_and_restore_to_empty_dir_preserves_state_keys_and_hashes(
    tmp_path: pathlib.Path,
) -> None:
    from auri_gateway.client import PinnedGatewayClient

    source_dir = tmp_path / "source-gw"
    restored_dir = tmp_path / "restored-gw"
    backup_file = tmp_path / "gateway-backup.tar.gz"

    gw1 = GatewayProcess(source_dir, tls=True, host="127.0.0.1")
    gw1.start()
    try:
        pair_res = gw1.run_cli("pair", "--data-dir", str(source_dir), "--json")
        assert pair_res.returncode == 0, pair_res.stderr
        pair_payload = json.loads(pair_res.stdout)
        original_pin = pair_payload["public_key_sha256"]

        with PinnedGatewayClient.from_pairing_payload(pair_payload) as client:
            redeem = client.redeem_pairing(
                make_redeem_request(pair_payload["pairing_code"], display_name="Backed Up Phone")
            )
            device_token = redeem["device_token"]
            headers = {"Authorization": f"Bearer {device_token}"}

            def create_dest(name: str) -> tuple[str, str]:
                req = {
                    **load_fixture("messages/valid/destination.create_request.auto-send.json")[
                        "instance"
                    ],
                    "display_name": name,
                }
                created = client._client.post(
                    f"{gw1.base_url}/v1/destinations", json=req, headers=headers
                )
                assert created.status_code == 200, created.text
                dest_id = created.json()["destination_id"]
                issued = client._client.post(
                    f"{gw1.base_url}/v1/destinations/{dest_id}/credential", headers=headers
                )
                assert issued.status_code == 200, issued.text
                return dest_id, issued.json()["credential"]

            pending_dest_id, pending_cred = create_dest("Pending Agent")
            acked_dest_id, acked_cred = create_dest("Acked Agent")

            pending_rec_id = str(uuid.uuid4())
            pending_sub = make_submission(
                record_id=pending_rec_id,
                revision=1,
                text="备份前尚未领取的口述。",
                destination_ids=[pending_dest_id],
            )
            put_pending = client.put_revision(
                pending_rec_id, 1, pending_sub, device_token=device_token
            )
            assert put_pending.status_code == 200
            pending_stored = put_pending.json()
            pending_delivery_id = pending_stored["deliveries"][0]["delivery_id"]

            acked_rec_id = str(uuid.uuid4())
            acked_sub = make_submission(
                record_id=acked_rec_id,
                revision=1,
                text="备份前已确认的口述。",
                destination_ids=[acked_dest_id],
            )
            put_acked = client.put_revision(acked_rec_id, 1, acked_sub, device_token=device_token)
            assert put_acked.status_code == 200

            deleted_rec_id = str(uuid.uuid4())
            del_sub = make_submission(
                record_id=deleted_rec_id,
                revision=1,
                text="备份前已删除的口述。",
                destination_ids=[pending_dest_id],
            )
            assert client.put_revision(deleted_rec_id, 1, del_sub, device_token=device_token).status_code == 200
            assert client._client.delete(
                f"{gw1.base_url}/v1/records/{deleted_rec_id}", headers=headers
            ).status_code == 200

            # Ack one delivery over pinned-TLS MCP before taking the online backup.
            async def ack_before_backup() -> tuple[str, str, str]:
                params = StdioServerParameters(
                    command=sys.executable,
                    args=[str(BRIDGE)],
                    env={
                        "AURI_GATEWAY_URL": gw1.base_url + "/mcp",
                        "AURI_GATEWAY_SPKI_SHA256": original_pin,
                        "AURI_DESTINATION_CREDENTIAL": acked_cred,
                    },
                )
                async with Client(params, mode="legacy") as mcp_client:
                    claim = await mcp_client.call_tool("delivery_claim", {})
                    assert not claim.is_error
                    (item,) = claim.structured_content["deliveries"]
                    ack = await mcp_client.call_tool(
                        "delivery_ack",
                        {"delivery_id": item["delivery_id"], "lease_token": item["lease_token"]},
                    )
                    assert not ack.is_error
                    return item["delivery_id"], item["lease_token"], ack.structured_content["acknowledged_at"]

            acked_delivery_id, acked_lease_token, acked_at = asyncio.run(ack_before_backup())

            # Simulate Ticket 05's cursor.key in data_dir (mode 0600) so backup preserves it too.
            cursor_key_bytes = os.urandom(32)
            cursor_key_path = source_dir / "cursor.key"
            fd = os.open(cursor_key_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "wb") as f:
                f.write(cursor_key_bytes)

            # Take an online backup WHILE gw1 is still running.
            backup_res = gw1.run_cli(
                "backup",
                "--data-dir",
                str(source_dir),
                "--output",
                str(backup_file),
                "--json",
            )
            assert backup_res.returncode == 0, backup_res.stderr
            backup_info = json.loads(backup_res.stdout)
            assert backup_info["gateway_id"] == pair_payload["gateway_id"]
            assert backup_file.exists()
            assert (stat.S_IMODE(backup_file.stat().st_mode) & 0o077) == 0
    finally:
        gw1.stop()

    # Restore refuses a non-empty directory.
    non_empty = tmp_path / "non-empty"
    non_empty.mkdir()
    (non_empty / "existing.txt").write_text("keep", encoding="utf-8")
    refused_restore = gw1.run_cli(
        "restore",
        "--backup",
        str(backup_file),
        "--data-dir",
        str(non_empty),
    )
    assert refused_restore.returncode != 0

    # Restore into a fresh empty directory.
    restored_dir.mkdir()
    restore_res = gw1.run_cli(
        "restore",
        "--backup",
        str(backup_file),
        "--data-dir",
        str(restored_dir),
        "--json",
    )
    assert restore_res.returncode == 0, restore_res.stderr
    assert not (restored_dir / "runtime.json").exists()
    assert (restored_dir / "cursor.key").read_bytes() == cursor_key_bytes
    assert (stat.S_IMODE((restored_dir / "cursor.key").stat().st_mode) & 0o077) == 0
    assert (stat.S_IMODE((restored_dir / "tls" / "gateway.key.pem").stat().st_mode) & 0o077) == 0

    # Start a new Gateway process on the restored directory (different port, real TLS).
    gw2 = GatewayProcess(restored_dir, tls=True, host="127.0.0.1")
    gw2.start()
    try:
        restored_client = PinnedGatewayClient(
            gateway_id=pair_payload["gateway_id"],
            addresses=[{"host": "127.0.0.1", "port": gw2.port}],
            expected_public_key_sha256=original_pin,
        )
        try:
            assert restored_client.health() == {"status": "ok"}

            # Paired device token still authenticates and sees exact prior Records & tombstones.
            dup_resp = restored_client.put_revision(
                pending_rec_id, 1, pending_sub, device_token=device_token
            )
            assert dup_resp.status_code == 200
            dup_body = dup_resp.json()
            assert dup_body["duplicate"] is True
            assert dup_body["operation_id"] == pending_stored["operation_id"]
            assert dup_body["stored_at"] == pending_stored["stored_at"]

            tombstone_resp = restored_client.put_revision(
                deleted_rec_id,
                2,
                make_submission(record_id=deleted_rec_id, revision=2, text="不能复活。"),
                device_token=device_token,
            )
            assert tombstone_resp.status_code == 410
            assert tombstone_resp.json()["code"] == "RECORD_DELETED"

            # Same device can still publish new Records after restore.
            fresh_sub = make_submission(
                revision=1,
                text="恢复后新录入的口述。",
                destination_ids=[pending_dest_id],
            )
            fresh_resp = restored_client.put_revision(
                fresh_sub["record_id"], 1, fresh_sub, device_token=device_token
            )
            assert fresh_resp.status_code == 200
            fresh_delivery_id = fresh_resp.json()["deliveries"][0]["delivery_id"]
        finally:
            restored_client.close()

        # Destination credentials still work over pinned-TLS MCP and Deliveries are intact.
        async def verify_restored_mcp() -> None:
            pending_params = StdioServerParameters(
                command=sys.executable,
                args=[str(BRIDGE)],
                env={
                    "AURI_GATEWAY_URL": gw2.base_url + "/mcp",
                    "AURI_GATEWAY_SPKI_SHA256": original_pin,
                    "AURI_DESTINATION_CREDENTIAL": pending_cred,
                },
            )
            acked_params = StdioServerParameters(
                command=sys.executable,
                args=[str(BRIDGE)],
                env={
                    "AURI_GATEWAY_URL": gw2.base_url + "/mcp",
                    "AURI_GATEWAY_SPKI_SHA256": original_pin,
                    "AURI_DESTINATION_CREDENTIAL": acked_cred,
                },
            )
            async with Client(pending_params, mode="legacy") as p_mcp, Client(
                acked_params, mode="legacy"
            ) as a_mcp:
                claimed = await p_mcp.call_tool("delivery_claim", {})
                assert not claimed.is_error
                items = claimed.structured_content["deliveries"]
                assert [d["delivery_id"] for d in items] == [pending_delivery_id, fresh_delivery_id]
                assert items[0]["text"] == "备份前尚未领取的口述。"

                # Acked destination sees no pending deliveries, and repeat ack stays idempotent.
                none_left = await a_mcp.call_tool("delivery_claim", {})
                assert none_left.structured_content["deliveries"] == []
                repeat_ack = await a_mcp.call_tool(
                    "delivery_ack",
                    {"delivery_id": acked_delivery_id, "lease_token": acked_lease_token},
                )
                assert not repeat_ack.is_error
                assert repeat_ack.structured_content["duplicate"] is True
                assert repeat_ack.structured_content["acknowledged_at"] == acked_at

        asyncio.run(verify_restored_mcp())
    finally:
        gw2.stop()


def test_upgrade_auto_migrates_older_database_and_preserves_data_on_failed_migration(
    tmp_path: pathlib.Path,
) -> None:
    real_migrations_dir = REPO_ROOT / "services" / "gateway" / "auri_gateway" / "migrations"
    old_migrations_dir = tmp_path / "old-migrations"
    old_migrations_dir.mkdir()
    for name in ("0001_initial.sql", "0002_destinations_and_deliveries.sql"):
        (old_migrations_dir / name).write_text(
            (real_migrations_dir / name).read_text(encoding="utf-8"),
            encoding="utf-8",
        )

    data_dir = tmp_path / "upgrade-gw"
    gw_old = GatewayProcess(
        data_dir,
        env_overrides={"AURI_GATEWAY_MIGRATIONS_DIR": str(old_migrations_dir)},
    )
    gw_old.start()
    try:
        paired = gw_old.pair_device("Legacy Phone")
        token = paired["device_token"]
        dest = gw_old.create_agent_destination(token, "Legacy Agent")
        record_id = str(uuid.uuid4())
        stored = gw_old.publish(
            token,
            [dest.destination_id],
            record_id=record_id,
            revision=1,
            text="旧版本数据库里的口述。",
        )
        delivery_id = stored["deliveries"][0]["delivery_id"]

        record_to_delete = str(uuid.uuid4())
        gw_old.publish(
            token,
            [dest.destination_id],
            record_id=record_to_delete,
            revision=1,
            text="升级后要删除的旧记录。",
        )
    finally:
        gw_old.stop()

    with contextlib.closing(sqlite3.connect(data_dir / "gateway.db")) as conn:
        old_versions = [
            row[0]
            for row in conn.execute("SELECT version FROM schema_migrations ORDER BY version").fetchall()
        ]
        assert old_versions == [1, 2]

    # Starting the gateway on the current migration level auto-migrates from 0002 -> latest.
    gw_upgraded = GatewayProcess(data_dir)
    gw_upgraded.start()
    try:
        agent = McpAgent(gw_upgraded.base_url, dest.credential)
        claims = agent.claim()["deliveries"]
        assert {c["delivery_id"] for c in claims} >= {delivery_id}
        target_claim = next(c for c in claims if c["delivery_id"] == delivery_id)
        assert target_claim["text"] == "旧版本数据库里的口述。"
        ack = agent.ack(target_claim["delivery_id"], target_claim["lease_token"])
        assert ack["state"] == "ACKNOWLEDGED"

        # 0004 columns work on the upgraded database.
        del_resp = httpx.delete(
            f"{gw_upgraded.base_url}/v1/records/{record_to_delete}",
            headers={"Authorization": f"Bearer {token}"},
            timeout=5.0,
        )
        assert del_resp.status_code == 200, del_resp.text
        assert_valid_contract("revision.schema.json#/$defs/record_deleted", del_resp.json())
    finally:
        gw_upgraded.stop()

    expected_versions = sorted(
        int(p.name.split("_", 1)[0]) for p in real_migrations_dir.glob("*.sql")
    )
    with contextlib.closing(sqlite3.connect(data_dir / "gateway.db")) as conn:
        upgraded_versions = [
            row[0]
            for row in conn.execute("SELECT version FROM schema_migrations ORDER BY version").fetchall()
        ]
        assert upgraded_versions == expected_versions

    # Now simulate a broken upgrade (one valid migration followed by a failing migration).
    broken_migrations_dir = tmp_path / "broken-migrations"
    broken_migrations_dir.mkdir()
    for sql_file in sorted(real_migrations_dir.glob("*.sql")):
        (broken_migrations_dir / sql_file.name).write_text(
            sql_file.read_text(encoding="utf-8"),
            encoding="utf-8",
        )
    next_ver = max(expected_versions) + 1
    (broken_migrations_dir / f"{next_ver:04d}_good_first.sql").write_text(
        "CREATE TABLE should_not_persist_a (id TEXT PRIMARY KEY);\n",
        encoding="utf-8",
    )
    (broken_migrations_dir / f"{next_ver + 1:04d}_broken_second.sql").write_text(
        "CREATE TABLE should_not_persist_b (id TEXT PRIMARY KEY);\n"
        "ALTER TABLE nonexistent_table ADD COLUMN bad TEXT;\n",
        encoding="utf-8",
    )

    fail_env = subprocess_env()
    fail_env["AURI_GATEWAY_MIGRATIONS_DIR"] = str(broken_migrations_dir)
    failed_start = subprocess.run(
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
        env=fail_env,
        capture_output=True,
        text=True,
        timeout=10,
        check=False,
    )
    assert failed_start.returncode != 0
    assert "migration" in failed_start.stderr.lower()
    assert "Traceback" not in failed_start.stderr

    # Original database is completely untouched.
    with contextlib.closing(sqlite3.connect(data_dir / "gateway.db")) as conn:
        versions_after_fail = [
            row[0]
            for row in conn.execute("SELECT version FROM schema_migrations ORDER BY version").fetchall()
        ]
        tables_after_fail = {
            row[0]
            for row in conn.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            ).fetchall()
        }
        assert versions_after_fail == expected_versions
        assert "should_not_persist_a" not in tables_after_fail
        assert "should_not_persist_b" not in tables_after_fail

    # Restarting without the broken migration works and all prior data is still intact.
    gw_recovered = GatewayProcess(data_dir)
    gw_recovered.start()
    try:
        dup = gw_recovered.publish(
            token,
            [dest.destination_id],
            record_id=record_id,
            revision=1,
            text="旧版本数据库里的口述。",
        )
        assert dup["duplicate"] is True
        assert dup["operation_id"] == stored["operation_id"]
    finally:
        gw_recovered.stop()
