"""Black-box security & contract rejection tests for Destinations and credentials (Ticket 02)."""
from __future__ import annotations

import json
import pathlib
import sys
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
    assert_valid_contract,
    gateway,  # noqa: F401
    load_fixture,
    make_submission,
)

SCHEMA_INVALID_DESTINATION_CREATES = sorted(
    (FIXTURE_DIR / "messages" / "schema-invalid").glob("destination.create_request.*.json")
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
    "fixture_path",
    SCHEMA_INVALID_DESTINATION_CREATES,
    ids=[p.stem for p in SCHEMA_INVALID_DESTINATION_CREATES],
)
def test_schema_invalid_destination_create_requests_rejected(
    gateway: GatewayProcess, fixture_path: pathlib.Path
) -> None:
    paired = gateway.pair_device("Reject Create Phone")
    headers = {"Authorization": f"Bearer {paired['device_token']}"}
    fixture = json.loads(fixture_path.read_text(encoding="utf-8"))

    resp = httpx.post(
        f"{gateway.base_url}/v1/destinations", trust_env=False,
        json=fixture["instance"],
        headers=headers,
        timeout=5.0,
    )
    assert_contract_error(resp, expected_status=422, expected_code="REQUEST_INVALID")


def test_create_destination_rejects_missing_auth_and_self_reported_identity(
    gateway: GatewayProcess,
) -> None:
    req = load_fixture("messages/valid/destination.create_request.auto-send.json")["instance"]

    no_auth = httpx.post(f"{gateway.base_url}/v1/destinations", trust_env=False, json=req, timeout=5.0)
    assert_contract_error(no_auth, expected_status=401, expected_code="GATEWAY_AUTH_INVALID")

    paired = gateway.pair_device("Spoof Identity Phone")
    headers = {"Authorization": f"Bearer {paired['device_token']}"}

    with_tenant = {**req, "tenant_id": str(uuid.uuid4())}
    resp_tenant = httpx.post(
        f"{gateway.base_url}/v1/destinations", trust_env=False,
        json=with_tenant,
        headers=headers,
        timeout=5.0,
    )
    assert_contract_error(resp_tenant, expected_status=422, expected_code="REQUEST_INVALID")

    with_device = {**req, "created_by_device_id": str(uuid.uuid4())}
    resp_device = httpx.post(
        f"{gateway.base_url}/v1/destinations", trust_env=False,
        json=with_device,
        headers=headers,
        timeout=5.0,
    )
    assert_contract_error(resp_device, expected_status=422, expected_code="REQUEST_INVALID")


def test_destination_query_and_credential_issue_rejections(gateway: GatewayProcess) -> None:
    missing_id = str(uuid.uuid4())
    for method, url in (
        ("GET", f"{gateway.base_url}/v1/destinations"),
        ("GET", f"{gateway.base_url}/v1/destinations/{missing_id}"),
        ("POST", f"{gateway.base_url}/v1/destinations/{missing_id}/credential"),
    ):
        resp = httpx.request(method, url, trust_env=False, timeout=5.0)
        assert_contract_error(resp, expected_status=401, expected_code="GATEWAY_AUTH_INVALID")

    paired = gateway.pair_device("Cred Reject Phone")
    headers = {"Authorization": f"Bearer {paired['device_token']}"}

    bad_uuid = httpx.get(
        f"{gateway.base_url}/v1/destinations/NOT-A-UUID", trust_env=False,
        headers=headers,
        timeout=5.0,
    )
    assert_contract_error(bad_uuid, expected_status=422, expected_code="REQUEST_INVALID")

    not_found_get = httpx.get(
        f"{gateway.base_url}/v1/destinations/{missing_id}", trust_env=False,
        headers=headers,
        timeout=5.0,
    )
    assert_contract_error(not_found_get, expected_status=404, expected_code="REQUEST_INVALID")

    not_found_issue = httpx.post(
        f"{gateway.base_url}/v1/destinations/{missing_id}/credential", trust_env=False,
        headers=headers,
        timeout=5.0,
    )
    assert_contract_error(not_found_issue, expected_status=404, expected_code="REQUEST_INVALID")

    create_req = load_fixture("messages/valid/destination.create_request.auto-send.json")["instance"]
    dest_id = httpx.post(
        f"{gateway.base_url}/v1/destinations", trust_env=False,
        json=create_req,
        headers=headers,
        timeout=5.0,
    ).json()["destination_id"]

    bad_json = httpx.post(
        f"{gateway.base_url}/v1/destinations/{dest_id}/credential", trust_env=False,
        content=b"{broken",
        headers={**headers, "Content-Type": "application/json"},
        timeout=5.0,
    )
    assert_contract_error(bad_json, expected_status=400, expected_code="REQUEST_INVALID")

    with_tenant = httpx.post(
        f"{gateway.base_url}/v1/destinations/{dest_id}/credential", trust_env=False,
        json={"tenant_id": str(uuid.uuid4())},
        headers=headers,
        timeout=5.0,
    )
    assert_contract_error(with_tenant, expected_status=422, expected_code="REQUEST_INVALID")


def test_credential_role_separation_and_revoked_destination_blocks_reissue(
    gateway: GatewayProcess,
) -> None:
    paired = gateway.pair_device("Role Separation Phone")
    device_headers = {"Authorization": f"Bearer {paired['device_token']}"}
    create_req = load_fixture("messages/valid/destination.create_request.auto-send.json")["instance"]

    dest_id = httpx.post(
        f"{gateway.base_url}/v1/destinations", trust_env=False,
        json=create_req,
        headers=device_headers,
        timeout=5.0,
    ).json()["destination_id"]
    dest_cred = httpx.post(
        f"{gateway.base_url}/v1/destinations/{dest_id}/credential", trust_env=False,
        headers=device_headers,
        timeout=5.0,
    ).json()["credential"]
    dest_headers = {"Authorization": f"Bearer {dest_cred}"}

    # Device token cannot authenticate as a Destination at /mcp.
    for bad_mcp_headers in (
        {},
        {"Authorization": "Bearer unknown-dest-credential-000000000000"},
        device_headers,
    ):
        resp = httpx.post(
            f"{gateway.base_url}/mcp", trust_env=False,
            json={"jsonrpc": "2.0", "id": 1, "method": "initialize"},
            headers=bad_mcp_headers,
            timeout=5.0,
        )
        assert_contract_error(resp, expected_status=401, expected_code="GATEWAY_AUTH_INVALID")

    # Destination credential cannot act as a Device token on device endpoints.
    assert_contract_error(
        httpx.get(f"{gateway.base_url}/v1/destinations", trust_env=False, headers=dest_headers, timeout=5.0),
        expected_status=401,
        expected_code="GATEWAY_AUTH_INVALID",
    )
    assert_contract_error(
        httpx.post(
            f"{gateway.base_url}/v1/destinations", trust_env=False,
            json=create_req,
            headers=dest_headers,
            timeout=5.0,
        ),
        expected_status=401,
        expected_code="GATEWAY_AUTH_INVALID",
    )
    sub = make_submission()
    assert_contract_error(
        httpx.put(
            f"{gateway.base_url}/v1/records/{sub['record_id']}/revisions/1", trust_env=False,
            json=sub,
            headers=dest_headers,
            timeout=5.0,
        ),
        expected_status=401,
        expected_code="GATEWAY_AUTH_INVALID",
    )

    # Deleting missing destination -> 404 REQUEST_INVALID.
    assert_contract_error(
        httpx.delete(
            f"{gateway.base_url}/v1/destinations/{uuid.uuid4()}", trust_env=False,
            headers=device_headers,
            timeout=5.0,
        ),
        expected_status=404,
        expected_code="REQUEST_INVALID",
    )

    # Revoked destination cannot be issued a new credential.
    revoked = httpx.delete(
        f"{gateway.base_url}/v1/destinations/{dest_id}", trust_env=False,
        headers=device_headers,
        timeout=5.0,
    )
    assert revoked.status_code == 200

    reissue = httpx.post(
        f"{gateway.base_url}/v1/destinations/{dest_id}/credential", trust_env=False,
        headers=device_headers,
        timeout=5.0,
    )
    assert_contract_error(reissue, expected_status=409, expected_code="REQUEST_INVALID")


def test_sending_to_missing_revoked_or_disallowed_intent_destination_is_rejected_atomically(
    gateway: GatewayProcess,
) -> None:
    paired = gateway.pair_device("Delivery Reject Phone")
    headers = {"Authorization": f"Bearer {paired['device_token']}"}

    context_only_req = load_fixture("messages/valid/destination.create_request.auto-send.json")[
        "instance"
    ]
    context_only_id = httpx.post(
        f"{gateway.base_url}/v1/destinations", trust_env=False,
        json=context_only_req,
        headers=headers,
        timeout=5.0,
    ).json()["destination_id"]

    revoked_id = httpx.post(
        f"{gateway.base_url}/v1/destinations", trust_env=False,
        json={**context_only_req, "display_name": "Soon Revoked"},
        headers=headers,
        timeout=5.0,
    ).json()["destination_id"]
    assert (
        httpx.delete(
            f"{gateway.base_url}/v1/destinations/{revoked_id}", trust_env=False,
            headers=headers,
            timeout=5.0,
        ).status_code
        == 200
    )

    missing_id = str(uuid.uuid4())

    # 1. Nonexistent destination
    rec_missing = str(uuid.uuid4())
    sub_missing = make_submission(record_id=rec_missing, revision=1, destination_ids=[missing_id])
    resp_missing = httpx.put(
        f"{gateway.base_url}/v1/records/{rec_missing}/revisions/1", trust_env=False,
        json=sub_missing,
        headers=headers,
        timeout=5.0,
    )
    assert_contract_error(resp_missing, expected_status=422, expected_code="REQUEST_INVALID")

    # 2. Revoked destination
    sub_revoked = make_submission(record_id=rec_missing, revision=1, destination_ids=[revoked_id])
    resp_revoked = httpx.put(
        f"{gateway.base_url}/v1/records/{rec_missing}/revisions/1", trust_env=False,
        json=sub_revoked,
        headers=headers,
        timeout=5.0,
    )
    assert_contract_error(resp_revoked, expected_status=422, expected_code="REQUEST_INVALID")

    # 3. Intent not allowed on destination (action_request to context-only destination)
    sub_disallowed = make_submission(
        record_id=rec_missing, revision=1, destination_ids=[context_only_id]
    )
    sub_disallowed["publication"]["intent"] = "action_request"
    resp_disallowed = httpx.put(
        f"{gateway.base_url}/v1/records/{rec_missing}/revisions/1", trust_env=False,
        json=sub_disallowed,
        headers=headers,
        timeout=5.0,
    )
    assert_contract_error(resp_disallowed, expected_status=422, expected_code="REQUEST_INVALID")

    # 4. Mixed valid + invalid destination must fail atomically without persisting the revision
    sub_mixed = make_submission(
        record_id=rec_missing, revision=1, destination_ids=[context_only_id, revoked_id]
    )
    resp_mixed = httpx.put(
        f"{gateway.base_url}/v1/records/{rec_missing}/revisions/1", trust_env=False,
        json=sub_mixed,
        headers=headers,
        timeout=5.0,
    )
    assert_contract_error(resp_mixed, expected_status=422, expected_code="REQUEST_INVALID")

    # Confirm via HTTP that none of the rejected attempts stored anything at (rec_missing, 1).
    sub_valid = make_submission(
        record_id=rec_missing, revision=1, destination_ids=[context_only_id]
    )
    ok_resp = httpx.put(
        f"{gateway.base_url}/v1/records/{rec_missing}/revisions/1", trust_env=False,
        json=sub_valid,
        headers=headers,
        timeout=5.0,
    )
    assert ok_resp.status_code == 200
    assert ok_resp.json()["duplicate"] is False
