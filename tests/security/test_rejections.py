"""Black-box security & contract rejection tests (Checklist items 2, 6, 7).

All rejection responses are validated strictly against contracts/schemas/error.schema.json
(contracts v1.2) and verified through the HTTP interface to leave no stored side effects.
"""
from __future__ import annotations

import json
import pathlib
import sqlite3
import sys
import time
import uuid
from typing import Any

import httpx
import pytest

_INTEGRATION_DIR = pathlib.Path(__file__).resolve().parents[1] / "integration"
if str(_INTEGRATION_DIR) not in sys.path:
    sys.path.append(str(_INTEGRATION_DIR))

from harness import (
    FIXTURE_DIR,
    GatewayProcess,
    McpAgent,
    assert_valid_contract,
    gateway,  # noqa: F401
    load_fixture,
    make_redeem_request,
    make_submission,
)

SCHEMA_INVALID_SUBMISSIONS = sorted(
    (FIXTURE_DIR / "messages" / "schema-invalid").glob("revision.submission.*.json")
)
SCHEMA_INVALID_REDEEMS = sorted(
    (FIXTURE_DIR / "messages" / "schema-invalid").glob("pairing.redeem_request.*.json")
)
SEMANTIC_INVALID_SUBMISSIONS = sorted(
    (FIXTURE_DIR / "messages" / "semantic-invalid").glob("revision.submission.*.json")
)


def assert_contract_error(
    resp: httpx.Response, *, expected_status: int, expected_code: str
) -> dict[str, Any]:
    assert resp.status_code == expected_status, (
        f"Expected HTTP {expected_status}, got {resp.status_code}: {resp.text}"
    )
    body = resp.json()
    assert_valid_contract("error.schema.json", body)
    assert body["code"] == expected_code
    assert body["retryable"] is False
    return body


@pytest.mark.parametrize(
    "auth_header",
    [
        None,
        "",
        "Bearer",
        "Basic dXNlcjpwYXNz",
        "Token some-token-value",
        "Bearer not-a-valid-paired-device-token-000000",
    ],
)
def test_missing_or_invalid_token_is_rejected(
    gateway: GatewayProcess, auth_header: str | None
) -> None:
    sub = make_submission()
    headers = {} if auth_header is None else {"Authorization": auth_header}
    resp = httpx.put(
        f"{gateway.base_url}/v1/records/{sub['record_id']}/revisions/{sub['revision']}", trust_env=False,
        json=sub,
        headers=headers,
        timeout=5.0,
    )
    body = assert_contract_error(resp, expected_status=401, expected_code="GATEWAY_AUTH_INVALID")
    assert sub["content"]["text"] not in body["message"]


def test_pairing_rejects_unknown_used_and_expired_codes_with_uniform_response(
    gateway: GatewayProcess,
) -> None:
    unknown_resp = httpx.post(
        f"{gateway.base_url}/v1/pairing/redeem", trust_env=False,
        json=make_redeem_request("UNKNOWN-PAIRING-CODE-123456"),
        timeout=5.0,
    )
    unknown_body = assert_contract_error(
        unknown_resp, expected_status=401, expected_code="PAIRING_CODE_INVALID"
    )
    assert "device_token" not in unknown_body

    code = gateway.issue_pairing_code(ttl_seconds=60)
    ok_resp = httpx.post(
        f"{gateway.base_url}/v1/pairing/redeem", trust_env=False, json=make_redeem_request(code), timeout=5.0
    )
    assert ok_resp.status_code == 200

    reuse_resp = httpx.post(
        f"{gateway.base_url}/v1/pairing/redeem", trust_env=False, json=make_redeem_request(code), timeout=5.0
    )
    reuse_body = assert_contract_error(
        reuse_resp, expected_status=401, expected_code="PAIRING_CODE_INVALID"
    )
    assert "device_token" not in reuse_body

    expired_code = gateway.issue_pairing_code(ttl_seconds=1)
    time.sleep(1.2)
    expired_resp = httpx.post(
        f"{gateway.base_url}/v1/pairing/redeem", trust_env=False,
        json=make_redeem_request(expired_code),
        timeout=5.0,
    )
    expired_body = assert_contract_error(
        expired_resp, expected_status=401, expected_code="PAIRING_CODE_INVALID"
    )
    assert "device_token" not in expired_body

    # All three failure modes must produce identical message so responses do not leak which failed.
    assert unknown_body["message"] == reuse_body["message"] == expired_body["message"]


@pytest.mark.parametrize(
    "fixture_path", SCHEMA_INVALID_REDEEMS, ids=[p.stem for p in SCHEMA_INVALID_REDEEMS]
)
def test_schema_invalid_pairing_requests_rejected(
    gateway: GatewayProcess, fixture_path: pathlib.Path
) -> None:
    fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
    resp = httpx.post(
        f"{gateway.base_url}/v1/pairing/redeem", trust_env=False,
        json=fixture["instance"],
        timeout=5.0,
    )
    assert_contract_error(resp, expected_status=422, expected_code="REQUEST_INVALID")


def test_pairing_request_with_tenant_id_is_rejected(gateway: GatewayProcess) -> None:
    code = gateway.issue_pairing_code(ttl_seconds=60)
    req = make_redeem_request(code)
    req["tenant_id"] = str(uuid.uuid4())
    resp = httpx.post(f"{gateway.base_url}/v1/pairing/redeem", trust_env=False, json=req, timeout=5.0)
    assert_contract_error(resp, expected_status=422, expected_code="REQUEST_INVALID")

    # Rejecting the malformed request must not consume the pairing code.
    clean_resp = httpx.post(
        f"{gateway.base_url}/v1/pairing/redeem", trust_env=False, json=make_redeem_request(code), timeout=5.0
    )
    assert clean_resp.status_code == 200
    assert_valid_contract("pairing.schema.json#/$defs/redeem_response", clean_resp.json())


@pytest.mark.parametrize(
    "fixture_path", SCHEMA_INVALID_SUBMISSIONS, ids=[p.stem for p in SCHEMA_INVALID_SUBMISSIONS]
)
def test_schema_invalid_revision_submissions_rejected(
    gateway: GatewayProcess, fixture_path: pathlib.Path
) -> None:
    paired = gateway.pair_device("Schema Reject Phone")
    headers = {"Authorization": f"Bearer {paired['device_token']}"}
    fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
    instance = fixture["instance"]

    record_id = instance.get("record_id") or str(uuid.uuid4())
    revision = instance.get("revision") if isinstance(instance.get("revision"), int) else 1
    url_record_id = record_id if record_id == record_id.lower() else str(uuid.uuid4())
    url_revision = revision if revision >= 1 else 1

    resp = httpx.put(
        f"{gateway.base_url}/v1/records/{url_record_id}/revisions/{url_revision}", trust_env=False,
        json=instance,
        headers=headers,
        timeout=5.0,
    )
    assert_contract_error(resp, expected_status=422, expected_code="REQUEST_INVALID")

    # Verify via HTTP that nothing was stored at that (record_id, revision).
    valid_follow_up = make_submission(record_id=url_record_id, revision=url_revision, text="正常正文。")
    ok = httpx.put(
        f"{gateway.base_url}/v1/records/{url_record_id}/revisions/{url_revision}", trust_env=False,
        json=valid_follow_up,
        headers=headers,
        timeout=5.0,
    )
    assert ok.status_code == 200
    assert ok.json()["duplicate"] is False


def test_submission_with_tenant_id_and_path_mismatch_rejected(gateway: GatewayProcess) -> None:
    paired = gateway.pair_device("Tenant Spoof Phone")
    headers = {"Authorization": f"Bearer {paired['device_token']}"}

    tenant_fixture = load_fixture("messages/schema-invalid/revision.submission.tenant-id-in-body.json")[
        "instance"
    ]
    resp_tenant = httpx.put(
        f"{gateway.base_url}/v1/records/{tenant_fixture['record_id']}/revisions/{tenant_fixture['revision']}", trust_env=False,
        json=tenant_fixture,
        headers=headers,
        timeout=5.0,
    )
    assert_contract_error(resp_tenant, expected_status=422, expected_code="REQUEST_INVALID")

    sub = make_submission()
    other_record_id = str(uuid.uuid4())
    resp_rec_mismatch = httpx.put(
        f"{gateway.base_url}/v1/records/{other_record_id}/revisions/{sub['revision']}", trust_env=False,
        json=sub,
        headers=headers,
        timeout=5.0,
    )
    assert_contract_error(resp_rec_mismatch, expected_status=422, expected_code="REQUEST_INVALID")

    resp_rev_mismatch = httpx.put(
        f"{gateway.base_url}/v1/records/{sub['record_id']}/revisions/2", trust_env=False,
        json=sub,
        headers=headers,
        timeout=5.0,
    )
    assert_contract_error(resp_rev_mismatch, expected_status=422, expected_code="REQUEST_INVALID")


def test_malformed_json_and_invalid_path_params_are_rejected(gateway: GatewayProcess) -> None:
    paired = gateway.pair_device("Malformed Phone")
    headers = {"Authorization": f"Bearer {paired['device_token']}"}

    resp_bad_json = httpx.put(
        f"{gateway.base_url}/v1/records/{uuid.uuid4()}/revisions/1", trust_env=False,
        content=b"{not-json",
        headers={**headers, "Content-Type": "application/json"},
        timeout=5.0,
    )
    assert_contract_error(resp_bad_json, expected_status=400, expected_code="REQUEST_INVALID")

    resp_non_object = httpx.put(
        f"{gateway.base_url}/v1/records/{uuid.uuid4()}/revisions/1", trust_env=False,
        json=["not", "an", "object"],
        headers=headers,
        timeout=5.0,
    )
    assert_contract_error(resp_non_object, expected_status=422, expected_code="REQUEST_INVALID")

    sub = make_submission()
    resp_bad_uuid = httpx.put(
        f"{gateway.base_url}/v1/records/NOT-A-UUID/revisions/1", trust_env=False,
        json=sub,
        headers=headers,
        timeout=5.0,
    )
    assert_contract_error(resp_bad_uuid, expected_status=422, expected_code="REQUEST_INVALID")

    resp_zero_rev = httpx.put(
        f"{gateway.base_url}/v1/records/{sub['record_id']}/revisions/0", trust_env=False,
        json=sub,
        headers=headers,
        timeout=5.0,
    )
    assert_contract_error(resp_zero_rev, expected_status=422, expected_code="REQUEST_INVALID")


@pytest.mark.parametrize(
    "fixture_path", SEMANTIC_INVALID_SUBMISSIONS, ids=[p.stem for p in SEMANTIC_INVALID_SUBMISSIONS]
)
def test_semantic_invalid_fixtures_sr01_to_sr05_rejected(
    gateway: GatewayProcess, fixture_path: pathlib.Path
) -> None:
    paired = gateway.pair_device("Semantic Reject Phone")
    headers = {"Authorization": f"Bearer {paired['device_token']}"}
    fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
    instance = fixture["instance"]

    assert_valid_contract("revision.schema.json#/$defs/submission", instance)

    resp = httpx.put(
        f"{gateway.base_url}/v1/records/{instance['record_id']}/revisions/{instance['revision']}", trust_env=False,
        json=instance,
        headers=headers,
        timeout=5.0,
    )
    body = assert_contract_error(resp, expected_status=422, expected_code="REQUEST_INVALID")
    assert instance["content"]["text"] not in body["message"]

    # Confirm via HTTP that the rejected submission was not persisted.
    valid_sub = make_submission(
        record_id=instance["record_id"], revision=instance["revision"], text="合法正文。"
    )
    ok = httpx.put(
        f"{gateway.base_url}/v1/records/{instance['record_id']}/revisions/{instance['revision']}", trust_env=False,
        json=valid_sub,
        headers=headers,
        timeout=5.0,
    )
    assert ok.status_code == 200
    assert ok.json()["duplicate"] is False


def test_semantic_rule_sr06_over_1mib_utf8_bytes_rejected(gateway: GatewayProcess) -> None:
    paired = gateway.pair_device("Large Text Phone")
    headers = {"Authorization": f"Bearer {paired['device_token']}"}

    # 349,526 CJK chars <= 1,048,576 chars (passes JSON Schema maxLength)
    # but 349,526 * 3 = 1,048,578 UTF-8 bytes > 1 MiB (violates SR-06).
    oversized_text = "中" * 349_526
    assert len(oversized_text) <= 1024 * 1024
    assert len(oversized_text.encode("utf-8")) == 1024 * 1024 + 2

    sub = make_submission(text=oversized_text, source="user_edit")
    assert_valid_contract("revision.schema.json#/$defs/submission", sub)

    resp = httpx.put(
        f"{gateway.base_url}/v1/records/{sub['record_id']}/revisions/{sub['revision']}", trust_env=False,
        json=sub,
        headers=headers,
        timeout=10.0,
    )
    body = assert_contract_error(resp, expected_status=422, expected_code="REQUEST_INVALID")
    assert "中" not in body["message"]

    # Confirm via HTTP that nothing was stored.
    valid_sub = make_submission(record_id=sub["record_id"], revision=1, text="合法短文本。")
    ok = httpx.put(
        f"{gateway.base_url}/v1/records/{sub['record_id']}/revisions/1", trust_env=False,
        json=valid_sub,
        headers=headers,
        timeout=5.0,
    )
    assert ok.status_code == 200
    assert ok.json()["duplicate"] is False


def test_unknown_route_404_returns_unified_error(gateway: GatewayProcess) -> None:
    for method, path in (("GET", "/v1/does-not-exist"), ("POST", "/unknown")):
        resp = httpx.request(method, f"{gateway.base_url}{path}", trust_env=False, timeout=5.0)
        assert_contract_error(resp, expected_status=404, expected_code="REQUEST_INVALID")


def test_method_not_allowed_405_returns_unified_error(gateway: GatewayProcess) -> None:
    resp_health = httpx.post(f"{gateway.base_url}/health", trust_env=False, timeout=5.0)
    assert_contract_error(resp_health, expected_status=405, expected_code="REQUEST_INVALID")
    assert "GET" in resp_health.headers.get("allow", "")

    resp_redeem = httpx.delete(f"{gateway.base_url}/v1/pairing/redeem", trust_env=False, timeout=5.0)
    assert_contract_error(resp_redeem, expected_status=405, expected_code="REQUEST_INVALID")
    assert "POST" in resp_redeem.headers.get("allow", "")

    paired = gateway.pair_device("MCP 405 Phone")
    dest = gateway.create_agent_destination(paired["device_token"], "MCP 405 Agent")
    resp_mcp = httpx.get(
        f"{gateway.base_url}/mcp", trust_env=False,
        headers={"Authorization": f"Bearer {dest.credential}", "Accept": "text/event-stream"},
        timeout=5.0,
    )
    assert_contract_error(resp_mcp, expected_status=405, expected_code="REQUEST_INVALID")
    assert "POST" in resp_mcp.headers.get("allow", "")


def test_unhandled_500_returns_unified_error_without_leaking_internals(
    tmp_path: pathlib.Path,
) -> None:
    gw = GatewayProcess(tmp_path / "unhandled-500")
    gw.start()
    try:
        paired = gw.pair_device("Error Isolation Phone")
        headers = {"Authorization": f"Bearer {paired['device_token']}"}
        with sqlite3.connect(gw.data_dir / "gateway.db") as conn:
            conn.execute("DROP TABLE destinations")

        resp = httpx.get(f"{gw.base_url}/v1/destinations", trust_env=False, headers=headers, timeout=5.0)
    finally:
        gw.stop()

    assert resp.status_code == 500, resp.text
    body = resp.json()
    assert_valid_contract("error.schema.json", body)
    assert body["code"] == "GATEWAY_UNREACHABLE"
    assert body["retryable"] is True
    for leaked in ("sqlite", "OperationalError", "no such table", "destinations", "Traceback", str(tmp_path)):
        assert leaked.lower() not in resp.text.lower()


def test_non_origin_device_cannot_submit_or_delete_record(gateway: GatewayProcess) -> None:
    origin = gateway.pair_device("Origin Phone")
    other = gateway.pair_device("Other Phone")
    origin_headers = {"Authorization": f"Bearer {origin['device_token']}"}
    other_headers = {"Authorization": f"Bearer {other['device_token']}"}

    dest = gateway.create_agent_destination(origin["device_token"], "Reader Agent")
    agent = McpAgent(gateway.base_url, dest.credential)

    record_id = str(uuid.uuid4())
    original_text = "原始设备录下的口述。"
    gateway.publish(
        origin["device_token"],
        [dest.destination_id],
        record_id=record_id,
        revision=1,
        text=original_text,
    )

    # Non-origin device cannot submit the same or a new revision, even with matching content.
    for rev, text in ((1, original_text), (2, "非原始设备提交的修订。")):
        sub = make_submission(
            record_id=record_id, revision=rev, text=text, destination_ids=[dest.destination_id]
        )
        resp = httpx.put(
            f"{gateway.base_url}/v1/records/{record_id}/revisions/{rev}", trust_env=False,
            json=sub,
            headers=other_headers,
            timeout=5.0,
        )
        assert_contract_error(resp, expected_status=403, expected_code="RECORD_NOT_ORIGIN_DEVICE")

    # Non-origin device cannot delete the record.
    del_resp = httpx.delete(
        f"{gateway.base_url}/v1/records/{record_id}", trust_env=False,
        headers=other_headers,
        timeout=5.0,
    )
    assert_contract_error(del_resp, expected_status=403, expected_code="RECORD_NOT_ORIGIN_DEVICE")

    # Record and its pending delivery remain intact; origin device can still publish & Agent can claim.
    (claimed,) = agent.claim()["deliveries"]
    assert claimed["record_id"] == record_id
    assert claimed["text"] == original_text
    agent.ack(claimed["delivery_id"], claimed["lease_token"])

    rev2_resp = gateway.publish(
        origin["device_token"],
        [dest.destination_id],
        record_id=record_id,
        revision=2,
        text="原始设备追加的修订。",
        source="user_edit",
    )
    assert rev2_resp["revision"] == 2

    # Origin device deletes the record; non-origin device still receives RECORD_NOT_ORIGIN_DEVICE.
    ok_del = httpx.delete(
        f"{gateway.base_url}/v1/records/{record_id}", trust_env=False,
        headers=origin_headers,
        timeout=5.0,
    )
    assert ok_del.status_code == 200
    del_after = httpx.delete(
        f"{gateway.base_url}/v1/records/{record_id}", trust_env=False,
        headers=other_headers,
        timeout=5.0,
    )
    assert_contract_error(del_after, expected_status=403, expected_code="RECORD_NOT_ORIGIN_DEVICE")


def test_delete_record_rejects_bad_auth_bad_path_and_body_payloads(gateway: GatewayProcess) -> None:
    paired = gateway.pair_device("Delete Auth Phone")
    device_headers = {"Authorization": f"Bearer {paired['device_token']}"}
    dest = gateway.create_agent_destination(paired["device_token"], "Agent")
    dest_headers = {"Authorization": f"Bearer {dest.credential}"}
    record_id = str(uuid.uuid4())

    # Unauthenticated and Destination-credential callers are rejected with 401.
    for headers in ({}, {"Authorization": "Bearer invalid-token"}, dest_headers):
        resp = httpx.delete(
            f"{gateway.base_url}/v1/records/{record_id}", trust_env=False,
            headers=headers,
            timeout=5.0,
        )
        assert_contract_error(resp, expected_status=401, expected_code="GATEWAY_AUTH_INVALID")

    # Uppercase UUID or non-UUID path parameter is rejected.
    for bad_id in ("NOT-A-UUID", record_id.upper()):
        resp = httpx.delete(
            f"{gateway.base_url}/v1/records/{bad_id}", trust_env=False,
            headers=device_headers,
            timeout=5.0,
        )
        assert_contract_error(resp, expected_status=422, expected_code="REQUEST_INVALID")

    # Malformed JSON or non-empty body (such as spoofed tenant_id / device_id) is rejected.
    bad_json = httpx.request(
        "DELETE",
        f"{gateway.base_url}/v1/records/{record_id}", trust_env=False,
        content=b"{not-json",
        headers={**device_headers, "Content-Type": "application/json"},
        timeout=5.0,
    )
    assert_contract_error(bad_json, expected_status=400, expected_code="REQUEST_INVALID")

    for spoofed in ({"tenant_id": str(uuid.uuid4())}, {"origin_device_id": str(uuid.uuid4())}):
        spoof_resp = httpx.request(
            "DELETE",
            f"{gateway.base_url}/v1/records/{record_id}", trust_env=False,
            json=spoofed,
            headers=device_headers,
            timeout=5.0,
        )
        assert_contract_error(spoof_resp, expected_status=422, expected_code="REQUEST_INVALID")


def test_path_mismatch_non_canonical_revision_and_conflict_leave_no_side_effects(
    gateway: GatewayProcess,
) -> None:
    paired = gateway.pair_device("Mismatch Phone")
    headers = {"Authorization": f"Bearer {paired['device_token']}"}
    dest = gateway.create_agent_destination(paired["device_token"], "Agent")
    agent = McpAgent(gateway.base_url, dest.credential)

    # Non-canonical revision path strings must be rejected even when body has matching integer.
    sub_1 = make_submission(revision=1, destination_ids=[dest.destination_id])
    for bad_rev in ("01", "1_0", "+1", "-1", "abc"):
        resp = httpx.put(
            f"{gateway.base_url}/v1/records/{sub_1['record_id']}/revisions/{bad_rev}", trust_env=False,
            json=sub_1,
            headers=headers,
            timeout=5.0,
        )
        assert_contract_error(resp, expected_status=422, expected_code="REQUEST_INVALID")

    # Path/body mismatch on record_id or revision stores nothing at either path or body target.
    other_record_id = str(uuid.uuid4())
    mismatch_rec = httpx.put(
        f"{gateway.base_url}/v1/records/{other_record_id}/revisions/1", trust_env=False,
        json=sub_1,
        headers=headers,
        timeout=5.0,
    )
    assert_contract_error(mismatch_rec, expected_status=422, expected_code="REQUEST_INVALID")

    mismatch_rev = httpx.put(
        f"{gateway.base_url}/v1/records/{sub_1['record_id']}/revisions/2", trust_env=False,
        json=sub_1,
        headers=headers,
        timeout=5.0,
    )
    assert_contract_error(mismatch_rev, expected_status=422, expected_code="REQUEST_INVALID")
    assert agent.claim()["deliveries"] == []

    # Now submit the valid matching request: duplicate=False proves nothing was stored earlier.
    original_text = "不会被冲突覆盖的正文。"
    valid_sub = make_submission(
        record_id=sub_1["record_id"],
        revision=1,
        text=original_text,
        destination_ids=[dest.destination_id],
    )
    ok = httpx.put(
        f"{gateway.base_url}/v1/records/{valid_sub['record_id']}/revisions/1", trust_env=False,
        json=valid_sub,
        headers=headers,
        timeout=5.0,
    )
    assert ok.status_code == 200
    assert ok.json()["duplicate"] is False

    # Conflicting version submission returns RECORD_VERSION_CONFLICT and Agent still receives original text.
    conflict_sub = make_submission(
        record_id=valid_sub["record_id"],
        revision=1,
        text="试图覆盖的新正文。",
        destination_ids=[dest.destination_id],
    )
    conflict_resp = httpx.put(
        f"{gateway.base_url}/v1/records/{valid_sub['record_id']}/revisions/1", trust_env=False,
        json=conflict_sub,
        headers=headers,
        timeout=5.0,
    )
    assert_contract_error(conflict_resp, expected_status=409, expected_code="RECORD_VERSION_CONFLICT")

    (claimed,) = agent.claim()["deliveries"]
    assert claimed["text"] == original_text
    assert claimed["text_sha256"] == valid_sub["content"]["text_sha256"]
