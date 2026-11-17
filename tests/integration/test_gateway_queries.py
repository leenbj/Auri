"""Black-box integration tests for Gateway query & status HTTP endpoints (Ticket 05)."""
from __future__ import annotations

import uuid

import httpx

from harness import (
    GatewayProcess,
    McpAgent,
    assert_valid_contract,
    gateway,  # noqa: F401
    make_submission,
)


def test_health_returns_minimal_ok_with_no_text_secrets_or_topology(
    gateway: GatewayProcess,
) -> None:
    paired = gateway.pair_device("Health Check Phone")
    dest = gateway.create_agent_destination(paired["device_token"], "Health Agent")
    secret_text = f"隐私口述正文-{uuid.uuid4()}"
    gateway.publish(paired["device_token"], [dest.destination_id], text=secret_text)

    resp = httpx.get(f"{gateway.base_url}/health", timeout=5.0)
    assert resp.status_code == 200
    body = resp.json()
    assert_valid_contract("gateway.schema.json#/$defs/health_response", body)
    assert body == {"status": "ok"}
    for forbidden in (secret_text, paired["device_token"], dest.credential, str(gateway.data_dir)):
        assert forbidden not in resp.text


def test_capabilities_requires_device_auth_and_returns_v1_limits(
    gateway: GatewayProcess,
) -> None:
    unauth = httpx.get(f"{gateway.base_url}/v1/capabilities", timeout=5.0)
    assert unauth.status_code == 401
    assert_valid_contract("error.schema.json", unauth.json())
    assert unauth.json()["code"] == "GATEWAY_AUTH_INVALID"

    paired = gateway.pair_device("Capabilities Phone")
    dest = gateway.create_agent_destination(paired["device_token"], "Caps Agent")

    # Destination credential cannot access device HTTP endpoints.
    dest_attempt = httpx.get(
        f"{gateway.base_url}/v1/capabilities",
        headers={"Authorization": f"Bearer {dest.credential}"},
        timeout=5.0,
    )
    assert dest_attempt.status_code == 401
    assert_valid_contract("error.schema.json", dest_attempt.json())
    assert dest_attempt.json()["code"] == "GATEWAY_AUTH_INVALID"

    resp = httpx.get(
        f"{gateway.base_url}/v1/capabilities",
        headers={"Authorization": f"Bearer {paired['device_token']}"},
        timeout=5.0,
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert_valid_contract("gateway.schema.json#/$defs/capabilities_response", body)
    assert body["features"]["cloud_asr"] is False
    assert body["features"]["record_read_tools"] is True
    assert body["features"]["delivery_tools"] is True
    assert body["limits"]["max_text_bytes"] == 1048576
    assert body["limits"]["record_list_default_limit"] == 20
    assert body["limits"]["record_list_max_limit"] == 100
    assert body["limits"]["delivery_claim_default_limit"] == 10
    assert body["limits"]["delivery_claim_max_limit"] == 50
    assert body["limits"]["delivery_lease_default_seconds"] == 120


def test_operation_lookup_returns_stored_outcome_and_live_delivery_states(
    gateway: GatewayProcess,
) -> None:
    phone_a = gateway.pair_device("Origin Phone")
    phone_b = gateway.pair_device("Other Phone")
    headers_a = {"Authorization": f"Bearer {phone_a['device_token']}"}
    headers_b = {"Authorization": f"Bearer {phone_b['device_token']}"}

    dest_1 = gateway.create_agent_destination(phone_a["device_token"], "Agent One")
    dest_2 = gateway.create_agent_destination(phone_a["device_token"], "Agent Two")
    secret_text = f"提交后断网重连查询-{uuid.uuid4()}"

    stored = gateway.publish(
        phone_a["device_token"],
        [dest_1.destination_id, dest_2.destination_id],
        text=secret_text,
    )
    op_id = stored["operation_id"]

    # Agent One claims and acknowledges before the phone looks up the operation.
    agent_1 = McpAgent(gateway.base_url, dest_1.credential)
    (claimed,) = agent_1.claim()["deliveries"]
    agent_1.ack(claimed["delivery_id"], claimed["lease_token"])

    op_resp = httpx.get(
        f"{gateway.base_url}/v1/operations/{op_id}",
        headers=headers_a,
        timeout=5.0,
    )
    assert op_resp.status_code == 200, op_resp.text
    op_body = op_resp.json()
    assert_valid_contract("gateway.schema.json#/$defs/operation_response", op_body)
    assert op_body["operation_id"] == op_id
    assert op_body["operation_type"] == "revision_store"
    assert op_body["status"] == "succeeded"
    assert op_body["record_id"] == stored["record_id"]
    assert op_body["revision"] == stored["revision"]
    assert op_body["text_sha256"] == stored["text_sha256"]
    assert op_body["stored_at"] == stored["stored_at"]
    assert op_body["superseded_revisions"] == []
    assert {d["destination_id"]: d["state"] for d in op_body["deliveries"]} == {
        dest_1.destination_id: "ACKNOWLEDGED",
        dest_2.destination_id: "PENDING",
    }
    assert secret_text not in op_resp.text

    # Another paired device cannot inspect Phone A's operation.
    other_dev = httpx.get(
        f"{gateway.base_url}/v1/operations/{op_id}",
        headers=headers_b,
        timeout=5.0,
    )
    assert other_dev.status_code == 403
    assert_valid_contract("error.schema.json", other_dev.json())
    assert other_dev.json()["code"] == "RECORD_NOT_ORIGIN_DEVICE"

    # Unknown UUID -> 404 REQUEST_INVALID; invalid UUID -> 422 REQUEST_INVALID; no auth -> 401.
    missing = httpx.get(
        f"{gateway.base_url}/v1/operations/{uuid.uuid4()}",
        headers=headers_a,
        timeout=5.0,
    )
    assert missing.status_code == 404
    assert_valid_contract("error.schema.json", missing.json())
    assert missing.json()["code"] == "REQUEST_INVALID"

    bad_id = httpx.get(
        f"{gateway.base_url}/v1/operations/NOT-A-UUID",
        headers=headers_a,
        timeout=5.0,
    )
    assert bad_id.status_code == 422
    assert_valid_contract("error.schema.json", bad_id.json())
    assert bad_id.json()["code"] == "REQUEST_INVALID"

    no_auth = httpx.get(f"{gateway.base_url}/v1/operations/{op_id}", timeout=5.0)
    assert no_auth.status_code == 401
    assert_valid_contract("error.schema.json", no_auth.json())
    assert no_auth.json()["code"] == "GATEWAY_AUTH_INVALID"


def test_record_status_shows_stored_leased_and_acknowledged_per_destination(
    gateway: GatewayProcess,
) -> None:
    paired = gateway.pair_device("Status Phone")
    headers = {"Authorization": f"Bearer {paired['device_token']}"}
    dest_stored = gateway.create_agent_destination(paired["device_token"], "Stored Agent")
    dest_leased = gateway.create_agent_destination(paired["device_token"], "Leased Agent")
    dest_acked = gateway.create_agent_destination(paired["device_token"], "Acked Agent")

    secret_text = f"各目标投递状态检查-{uuid.uuid4()}"
    stored = gateway.publish(
        paired["device_token"],
        [dest_stored.destination_id, dest_leased.destination_id, dest_acked.destination_id],
        text=secret_text,
    )
    record_id = stored["record_id"]

    # Leased Agent claims; Acked Agent claims + acks; Stored Agent leaves pending.
    (leased_dlv,) = McpAgent(gateway.base_url, dest_leased.credential).claim()["deliveries"]
    acked_agent = McpAgent(gateway.base_url, dest_acked.credential)
    (acked_dlv,) = acked_agent.claim()["deliveries"]
    acked_agent.ack(acked_dlv["delivery_id"], acked_dlv["lease_token"], note="secret-ack-note")

    resp = httpx.get(
        f"{gateway.base_url}/v1/records/{record_id}/status",
        headers=headers,
        timeout=5.0,
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert_valid_contract("gateway.schema.json#/$defs/record_status_response", body)
    assert body["record_id"] == record_id
    assert body["origin_device_id"] == paired["device_id"]
    assert body["deleted"] is False
    assert body["latest_revision"] == 1
    assert len(body["revisions"]) == 1
    assert body["revisions"][0]["state"] == "PUBLISHED"

    by_dest = {d["destination_id"]: d for d in body["destinations"]}
    assert set(by_dest) == {
        dest_stored.destination_id,
        dest_leased.destination_id,
        dest_acked.destination_id,
    }
    assert by_dest[dest_stored.destination_id]["delivery_state"] == "PENDING"
    assert by_dest[dest_stored.destination_id]["lease_expires_at"] is None
    assert by_dest[dest_leased.destination_id]["delivery_state"] == "LEASED"
    assert by_dest[dest_leased.destination_id]["lease_expires_at"] is not None
    assert by_dest[dest_acked.destination_id]["delivery_state"] == "ACKNOWLEDGED"
    assert by_dest[dest_acked.destination_id]["acknowledged_at"] is not None

    for forbidden in (
        secret_text,
        "secret-ack-note",
        leased_dlv["lease_token"],
        acked_dlv["lease_token"],
    ):
        assert forbidden not in resp.text

    # Publish revision 2: revision 1 transitions to SUPERSEDED, revision 2 is PUBLISHED.
    gateway.publish(
        paired["device_token"],
        [dest_stored.destination_id],
        record_id=record_id,
        revision=2,
        text="第二版正文。",
        source="user_edit",
    )
    resp_v2 = httpx.get(
        f"{gateway.base_url}/v1/records/{record_id}/status",
        headers=headers,
        timeout=5.0,
    )
    assert resp_v2.status_code == 200
    body_v2 = resp_v2.json()
    assert_valid_contract("gateway.schema.json#/$defs/record_status_response", body_v2)
    assert body_v2["latest_revision"] == 2
    assert {r["revision"]: r["state"] for r in body_v2["revisions"]} == {
        1: "SUPERSEDED",
        2: "PUBLISHED",
    }

    # Unknown / invalid / unauthenticated / destination-credential rejections.
    assert (
        httpx.get(
            f"{gateway.base_url}/v1/records/{uuid.uuid4()}/status",
            headers=headers,
            timeout=5.0,
        ).status_code
        == 404
    )
    assert (
        httpx.get(
            f"{gateway.base_url}/v1/records/NOT-A-UUID/status",
            headers=headers,
            timeout=5.0,
        ).status_code
        == 422
    )
    assert (
        httpx.get(f"{gateway.base_url}/v1/records/{record_id}/status", timeout=5.0).status_code
        == 401
    )
    assert (
        httpx.get(
            f"{gateway.base_url}/v1/records/{record_id}/status",
            headers={"Authorization": f"Bearer {dest_stored.credential}"},
            timeout=5.0,
        ).status_code
        == 401
    )
