"""Black-box integration tests for Destination management, credentials, and deliveries (Ticket 02)."""
from __future__ import annotations

import pathlib
import sqlite3
import uuid

import httpx

from harness import (
    GatewayProcess,
    McpAgent,
    assert_valid_contract,
    gateway,  # noqa: F401
    load_fixture,
    make_submission,
    sha256_hex,
)


def test_create_destination_requires_explicit_send_mode_and_persists_valid_modes(
    gateway: GatewayProcess,
) -> None:
    paired = gateway.pair_device("Destination Owner Phone")
    headers = {"Authorization": f"Bearer {paired['device_token']}"}

    for fixture_name in (
        "messages/valid/destination.create_request.auto-send.json",
        "messages/valid/destination.create_request.preview-confirm.json",
    ):
        req = load_fixture(fixture_name)["instance"]
        assert_valid_contract("destination.schema.json#/$defs/create_request", req)

        resp = httpx.post(
            f"{gateway.base_url}/v1/destinations",
            json=req,
            headers=headers,
            timeout=5.0,
        )
        assert resp.status_code == 200, f"Unexpected status {resp.status_code}: {resp.text}"
        body = resp.json()
        assert_valid_contract("destination.schema.json#/$defs/resource", body)
        assert body["display_name"] == req["display_name"]
        assert body["send_mode"] == req["send_mode"]
        assert body["allowed_intents"] == req["allowed_intents"]
        assert body["created_by_device_id"] == paired["device_id"]
        assert body["credential_issued"] is False
        assert body["revoked_at"] is None


def test_credential_appears_once_is_hashed_at_rest_and_never_echoed_on_query(
    tmp_path: pathlib.Path,
) -> None:
    data_dir = tmp_path / "cred-hash-check"
    gw = GatewayProcess(data_dir)
    gw.start()
    try:
        paired = gw.pair_device("Cred Owner Phone")
        headers = {"Authorization": f"Bearer {paired['device_token']}"}
        create_req = load_fixture("messages/valid/destination.create_request.auto-send.json")["instance"]

        create_resp = httpx.post(
            f"{gw.base_url}/v1/destinations",
            json=create_req,
            headers=headers,
            timeout=5.0,
        )
        assert create_resp.status_code == 200
        dest = create_resp.json()
        assert_valid_contract("destination.schema.json#/$defs/resource", dest)
        dest_id = dest["destination_id"]

        issue_resp = httpx.post(
            f"{gw.base_url}/v1/destinations/{dest_id}/credential",
            headers=headers,
            timeout=5.0,
        )
        assert issue_resp.status_code == 200, f"issue failed: {issue_resp.status_code} {issue_resp.text}"
        cred_body = issue_resp.json()
        assert_valid_contract("destination.schema.json#/$defs/credential_response", cred_body)
        assert cred_body["destination_id"] == dest_id
        assert cred_body["replaced_previous"] is False
        credential = cred_body["credential"]
        assert len(credential) >= 32

        # Query single destination and list destinations: neither may echo the credential.
        get_resp = httpx.get(
            f"{gw.base_url}/v1/destinations/{dest_id}",
            headers=headers,
            timeout=5.0,
        )
        assert get_resp.status_code == 200
        get_body = get_resp.json()
        assert_valid_contract("destination.schema.json#/$defs/resource", get_body)
        assert get_body["credential_issued"] is True
        assert credential not in get_resp.text

        list_resp = httpx.get(
            f"{gw.base_url}/v1/destinations",
            headers=headers,
            timeout=5.0,
        )
        assert list_resp.status_code == 200
        list_body = list_resp.json()
        assert list_body["schema_version"] == "1.1"
        assert len(list_body["destinations"]) == 1
        assert_valid_contract("destination.schema.json#/$defs/resource", list_body["destinations"][0])
        assert list_body["destinations"][0]["credential_issued"] is True
        assert credential not in list_resp.text
    finally:
        stdout, stderr = gw.stop()

    combined_logs = stdout + "\n" + stderr
    assert credential not in combined_logs

    db_path = data_dir / "gateway.db"
    raw_db_bytes = db_path.read_bytes()
    assert credential.encode("utf-8") not in raw_db_bytes

    with sqlite3.connect(db_path) as conn:
        row = conn.execute(
            "SELECT token_hash, revoked_at FROM destination_credentials WHERE destination_id = ?",
            (dest_id,),
        ).fetchone()
        assert row is not None
        assert row[0] == sha256_hex(credential)
        assert row[1] is None


def test_rotating_credential_immediately_invalidates_old_and_revoke_blocks_auth(
    gateway: GatewayProcess,
) -> None:
    paired = gateway.pair_device("Rotate Owner Phone")
    device_headers = {"Authorization": f"Bearer {paired['device_token']}"}
    create_req = load_fixture("messages/valid/destination.create_request.preview-confirm.json")[
        "instance"
    ]

    dest_resp = httpx.post(
        f"{gateway.base_url}/v1/destinations",
        json=create_req,
        headers=device_headers,
        timeout=5.0,
    )
    assert dest_resp.status_code == 200
    dest_id = dest_resp.json()["destination_id"]

    cred1_resp = httpx.post(
        f"{gateway.base_url}/v1/destinations/{dest_id}/credential",
        headers=device_headers,
        timeout=5.0,
    )
    assert cred1_resp.status_code == 200
    cred1_body = cred1_resp.json()
    assert_valid_contract("destination.schema.json#/$defs/credential_response", cred1_body)
    assert cred1_body["replaced_previous"] is False
    cred1 = cred1_body["credential"]

    mcp_v1_ok = httpx.post(
        f"{gateway.base_url}/mcp",
        json={"jsonrpc": "2.0", "id": 1, "method": "initialize"},
        headers={"Authorization": f"Bearer {cred1}"},
        timeout=5.0,
    )
    assert mcp_v1_ok.status_code != 401

    # Rotate credential: v2 replaces v1 immediately.
    cred2_resp = httpx.post(
        f"{gateway.base_url}/v1/destinations/{dest_id}/credential",
        headers=device_headers,
        timeout=5.0,
    )
    assert cred2_resp.status_code == 200
    cred2_body = cred2_resp.json()
    assert_valid_contract("destination.schema.json#/$defs/credential_response", cred2_body)
    assert cred2_body["replaced_previous"] is True
    cred2 = cred2_body["credential"]
    assert cred2 != cred1

    mcp_v1_revoked = httpx.post(
        f"{gateway.base_url}/mcp",
        json={"jsonrpc": "2.0", "id": 2, "method": "initialize"},
        headers={"Authorization": f"Bearer {cred1}"},
        timeout=5.0,
    )
    assert mcp_v1_revoked.status_code == 401
    v1_err = mcp_v1_revoked.json()
    assert_valid_contract("error.schema.json", v1_err)
    assert v1_err["code"] == "GATEWAY_AUTH_REVOKED"

    mcp_v2_ok = httpx.post(
        f"{gateway.base_url}/mcp",
        json={"jsonrpc": "2.0", "id": 3, "method": "initialize"},
        headers={"Authorization": f"Bearer {cred2}"},
        timeout=5.0,
    )
    assert mcp_v2_ok.status_code != 401

    # Revoke the destination: credential v2 immediately fails auth and revoked_at is recorded.
    del_resp = httpx.delete(
        f"{gateway.base_url}/v1/destinations/{dest_id}",
        headers=device_headers,
        timeout=5.0,
    )
    assert del_resp.status_code == 200
    del_body = del_resp.json()
    assert_valid_contract("destination.schema.json#/$defs/resource", del_body)
    assert del_body["revoked_at"] is not None

    del_again = httpx.delete(
        f"{gateway.base_url}/v1/destinations/{dest_id}",
        headers=device_headers,
        timeout=5.0,
    )
    assert del_again.status_code == 200
    assert del_again.json()["revoked_at"] == del_body["revoked_at"]

    mcp_v2_after_revoke = httpx.post(
        f"{gateway.base_url}/mcp",
        json={"jsonrpc": "2.0", "id": 4, "method": "initialize"},
        headers={"Authorization": f"Bearer {cred2}"},
        timeout=5.0,
    )
    assert mcp_v2_after_revoke.status_code == 401
    v2_err = mcp_v2_after_revoke.json()
    assert_valid_contract("error.schema.json", v2_err)
    assert v2_err["code"] == "GATEWAY_AUTH_REVOKED"


def test_submit_revision_creates_pending_delivery_per_destination_and_empty_list_stores_only(
    gateway: GatewayProcess,
) -> None:
    paired = gateway.pair_device("Publisher Phone")
    headers = {"Authorization": f"Bearer {paired['device_token']}"}
    create_req = load_fixture("messages/valid/destination.create_request.auto-send.json")["instance"]

    dest_1 = httpx.post(
        f"{gateway.base_url}/v1/destinations",
        json={**create_req, "display_name": "Agent Alpha"},
        headers=headers,
        timeout=5.0,
    ).json()["destination_id"]
    dest_2 = httpx.post(
        f"{gateway.base_url}/v1/destinations",
        json={**create_req, "display_name": "Agent Beta"},
        headers=headers,
        timeout=5.0,
    ).json()["destination_id"]

    rec_id = str(uuid.uuid4())
    sub = make_submission(record_id=rec_id, revision=1, destination_ids=[dest_1, dest_2])
    assert_valid_contract("revision.schema.json#/$defs/submission", sub)

    resp = httpx.put(
        f"{gateway.base_url}/v1/records/{rec_id}/revisions/1",
        json=sub,
        headers=headers,
        timeout=5.0,
    )
    assert resp.status_code == 200
    body = resp.json()
    assert_valid_contract("revision.schema.json#/$defs/stored", body)
    assert body["duplicate"] is False
    assert len(body["deliveries"]) == 2
    assert [d["destination_id"] for d in body["deliveries"]] == [dest_1, dest_2]
    assert all(d["revision"] == 1 and d["state"] == "PENDING" for d in body["deliveries"])
    assert len({d["delivery_id"] for d in body["deliveries"]}) == 2

    # Empty destination list stores the revision without creating deliveries.
    empty_rec = str(uuid.uuid4())
    empty_sub = make_submission(record_id=empty_rec, revision=1, destination_ids=[])
    empty_resp = httpx.put(
        f"{gateway.base_url}/v1/records/{empty_rec}/revisions/1",
        json=empty_sub,
        headers=headers,
        timeout=5.0,
    )
    assert empty_resp.status_code == 200
    empty_body = empty_resp.json()
    assert_valid_contract("revision.schema.json#/$defs/stored", empty_body)
    assert empty_body["deliveries"] == []

    dup_empty = httpx.put(
        f"{gateway.base_url}/v1/records/{empty_rec}/revisions/1",
        json=empty_sub,
        headers=headers,
        timeout=5.0,
    )
    assert dup_empty.status_code == 200
    assert dup_empty.json()["duplicate"] is True
    assert dup_empty.json()["deliveries"] == []


def test_duplicate_submission_does_not_create_duplicate_deliveries(
    tmp_path: pathlib.Path,
) -> None:
    data_dir = tmp_path / "dup-deliveries"
    gw = GatewayProcess(data_dir)
    gw.start()
    try:
        paired = gw.pair_device("Idempotent Publisher")
        headers = {"Authorization": f"Bearer {paired['device_token']}"}
        preview_req = load_fixture("messages/valid/destination.create_request.preview-confirm.json")[
            "instance"
        ]

        dest_1 = httpx.post(
            f"{gw.base_url}/v1/destinations",
            json={**preview_req, "display_name": "Writer A"},
            headers=headers,
            timeout=5.0,
        ).json()["destination_id"]
        dest_2 = httpx.post(
            f"{gw.base_url}/v1/destinations",
            json={**preview_req, "display_name": "Writer B"},
            headers=headers,
            timeout=5.0,
        ).json()["destination_id"]

        rec_id = str(uuid.uuid4())
        sub = make_submission(record_id=rec_id, revision=1, destination_ids=[dest_1, dest_2])
        sub["publication"]["intent"] = "action_request"
        assert_valid_contract("revision.schema.json#/$defs/submission", sub)

        first = httpx.put(
            f"{gw.base_url}/v1/records/{rec_id}/revisions/1",
            json=sub,
            headers=headers,
            timeout=5.0,
        )
        assert first.status_code == 200
        first_body = first.json()
        assert_valid_contract("revision.schema.json#/$defs/stored", first_body)
        assert first_body["duplicate"] is False
        assert len(first_body["deliveries"]) == 2

        second = httpx.put(
            f"{gw.base_url}/v1/records/{rec_id}/revisions/1",
            json=sub,
            headers=headers,
            timeout=5.0,
        )
        assert second.status_code == 200
        second_body = second.json()
        assert_valid_contract("revision.schema.json#/$defs/stored", second_body)
        assert second_body["duplicate"] is True
        assert second_body["operation_id"] == first_body["operation_id"]
        assert second_body["stored_at"] == first_body["stored_at"]
        assert second_body["deliveries"] == first_body["deliveries"]

        # Same record_id + revision + text_sha256 with different destination_ids still returns
        # the original stored result idempotently without creating new deliveries.
        alt_sub = make_submission(
            record_id=rec_id,
            revision=1,
            text=sub["content"]["text"],
            destination_ids=[dest_1],
        )
        third = httpx.put(
            f"{gw.base_url}/v1/records/{rec_id}/revisions/1",
            json=alt_sub,
            headers=headers,
            timeout=5.0,
        )
        assert third.status_code == 200
        third_body = third.json()
        assert_valid_contract("revision.schema.json#/$defs/stored", third_body)
        assert third_body["duplicate"] is True
        assert third_body["deliveries"] == first_body["deliveries"]
    finally:
        gw.stop()

    with sqlite3.connect(data_dir / "gateway.db") as conn:
        count_row = conn.execute(
            "SELECT COUNT(*) FROM deliveries WHERE record_id = ? AND revision = 1",
            (rec_id,),
        ).fetchone()
        assert count_row is not None
        assert count_row[0] == 2


def test_revoking_destination_cancels_open_deliveries_and_preserves_terminal_ones(
    tmp_path: pathlib.Path,
) -> None:
    data_dir = tmp_path / "revoke-deliveries"
    gw = GatewayProcess(data_dir)
    gw.start()
    try:
        paired = gw.pair_device("Revoke Deliveries Phone")
        device_token = paired["device_token"]
        headers = {"Authorization": f"Bearer {device_token}"}

        dest_1 = gw.create_agent_destination(device_token, "Revoked Agent")
        dest_2 = gw.create_agent_destination(device_token, "Unaffected Agent")
        agent_1 = McpAgent(gw.base_url, dest_1.credential)

        # Create one delivery that gets ACKNOWLEDGED and one that gets REJECTED first.
        acked_pub = gw.publish(device_token, [dest_1.destination_id], text="已确认的内容")
        acked_id = acked_pub["deliveries"][0]["delivery_id"]
        claimed_ack = agent_1.claim(limit=1)["deliveries"][0]
        assert claimed_ack["delivery_id"] == acked_id
        agent_1.ack(acked_id, claimed_ack["lease_token"])

        rejected_pub = gw.publish(device_token, [dest_1.destination_id], text="永久拒收的内容")
        rejected_id = rejected_pub["deliveries"][0]["delivery_id"]
        claimed_rej = agent_1.claim(limit=1)["deliveries"][0]
        assert claimed_rej["delivery_id"] == rejected_id
        agent_1.nack(rejected_id, claimed_rej["lease_token"], retryable=False, reason="permanent")

        # Create one LEASED delivery and one PENDING delivery on dest_1, plus one PENDING on dest_2.
        leased_pub = gw.publish(device_token, [dest_1.destination_id], text="租约中被撤销")
        leased_id = leased_pub["deliveries"][0]["delivery_id"]
        claimed_leased = agent_1.claim(limit=1)["deliveries"][0]
        assert claimed_leased["delivery_id"] == leased_id

        pending_pub = gw.publish(device_token, [dest_1.destination_id], text="待领取被撤销")
        pending_id = pending_pub["deliveries"][0]["delivery_id"]

        other_pub = gw.publish(device_token, [dest_2.destination_id], text="另一个目标不受影响")
        other_id = other_pub["deliveries"][0]["delivery_id"]

        # Revoke dest_1 over HTTP.
        del_resp = httpx.delete(
            f"{gw.base_url}/v1/destinations/{dest_1.destination_id}",
            headers=headers,
            timeout=5.0,
        )
        assert del_resp.status_code == 200
        revoked_at = del_resp.json()["revoked_at"]
        assert revoked_at is not None

        # Repeating DELETE is idempotent.
        del_again = httpx.delete(
            f"{gw.base_url}/v1/destinations/{dest_1.destination_id}",
            headers=headers,
            timeout=5.0,
        )
        assert del_again.status_code == 200
        assert del_again.json()["revoked_at"] == revoked_at
    finally:
        gw.stop()

    with sqlite3.connect(data_dir / "gateway.db") as conn:
        conn.row_factory = sqlite3.Row
        rows = {
            row["delivery_id"]: dict(row)
            for row in conn.execute(
                "SELECT delivery_id, state, cancelled_at, lease_token_hash, lease_expires_at "
                "FROM deliveries"
            ).fetchall()
        }

    assert rows[pending_id]["state"] == "CANCELLED"
    assert rows[pending_id]["cancelled_at"] is not None
    assert rows[pending_id]["lease_token_hash"] is None
    assert rows[pending_id]["lease_expires_at"] is None

    assert rows[leased_id]["state"] == "CANCELLED"
    assert rows[leased_id]["cancelled_at"] is not None
    assert rows[leased_id]["lease_token_hash"] is None
    assert rows[leased_id]["lease_expires_at"] is None

    assert rows[acked_id]["state"] == "ACKNOWLEDGED"
    assert rows[acked_id]["cancelled_at"] is None

    assert rows[rejected_id]["state"] == "REJECTED"
    assert rows[rejected_id]["cancelled_at"] is None

    assert rows[other_id]["state"] == "PENDING"
    assert rows[other_id]["cancelled_at"] is None
