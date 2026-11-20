"""Black-box integration tests for Revision storage, idempotency, and non-contiguous versions."""
from __future__ import annotations

import uuid

import httpx

from harness import (
    GatewayProcess,
    assert_valid_contract,
    gateway,  # noqa: F401
    load_fixture,
    make_submission,
)


def test_store_revision_and_idempotent_resubmit(gateway: GatewayProcess) -> None:
    paired = gateway.pair_device("Primary Phone")
    headers = {"Authorization": f"Bearer {paired['device_token']}"}

    fixture_sub = load_fixture("messages/valid/revision.submission.no-destination.json")["instance"]
    assert_valid_contract("revision.schema.json#/$defs/submission", fixture_sub)
    record_id = fixture_sub["record_id"]
    rev = fixture_sub["revision"]

    first_resp = httpx.put(
        f"{gateway.base_url}/v1/records/{record_id}/revisions/{rev}", trust_env=False,
        json=fixture_sub,
        headers=headers,
        timeout=5.0,
    )
    assert first_resp.status_code == 200
    first_body = first_resp.json()
    assert_valid_contract("revision.schema.json#/$defs/stored", first_body)
    assert first_body["duplicate"] is False
    assert first_body["record_id"] == record_id
    assert first_body["revision"] == rev
    assert first_body["text_sha256"] == fixture_sub["content"]["text_sha256"]
    assert first_body["superseded_revisions"] == []
    assert first_body["deliveries"] == []

    # Repeat exact same submission: must return original stored snapshot with duplicate=True.
    dup_resp = httpx.put(
        f"{gateway.base_url}/v1/records/{record_id}/revisions/{rev}", trust_env=False,
        json=fixture_sub,
        headers=headers,
        timeout=5.0,
    )
    assert dup_resp.status_code == 200
    dup_body = dup_resp.json()
    assert_valid_contract("revision.schema.json#/$defs/stored", dup_body)
    assert dup_body["duplicate"] is True
    assert dup_body["operation_id"] == first_body["operation_id"]
    assert dup_body["stored_at"] == first_body["stored_at"]
    assert dup_body["text_sha256"] == first_body["text_sha256"]
    assert dup_body["superseded_revisions"] == first_body["superseded_revisions"]
    assert dup_body["deliveries"] == first_body["deliveries"]

    # Publishing a subsequent revision supersedes only the single stored revision [1].
    rev2 = make_submission(record_id=record_id, revision=2, text="补充说明。", source="user_edit")
    rev2_resp = httpx.put(
        f"{gateway.base_url}/v1/records/{record_id}/revisions/2", trust_env=False,
        json=rev2,
        headers=headers,
        timeout=5.0,
    )
    assert rev2_resp.status_code == 200
    assert rev2_resp.json()["superseded_revisions"] == [1]


def test_non_contiguous_revision_numbers_are_accepted(gateway: GatewayProcess) -> None:
    paired = gateway.pair_device("Editing Phone")
    headers = {"Authorization": f"Bearer {paired['device_token']}"}

    # Case A: First revision arriving at Gateway is already version 3.
    rec_a = str(uuid.uuid4())
    sub_a3 = make_submission(record_id=rec_a, revision=3, text="跳过了前两版的初次提交。", source="user_edit")
    assert_valid_contract("revision.schema.json#/$defs/submission", sub_a3)
    resp_a3 = httpx.put(
        f"{gateway.base_url}/v1/records/{rec_a}/revisions/3", trust_env=False,
        json=sub_a3,
        headers=headers,
        timeout=5.0,
    )
    assert resp_a3.status_code == 200
    body_a3 = resp_a3.json()
    assert_valid_contract("revision.schema.json#/$defs/stored", body_a3)
    assert body_a3["revision"] == 3
    assert body_a3["superseded_revisions"] == []

    # Case B: Revision 1, then skip 2 -> Revision 3, then skip 4..6 -> Revision 7.
    rec_b = str(uuid.uuid4())
    sub_b1 = make_submission(record_id=rec_b, revision=1, text="第一版。", source="asr")
    sub_b3 = make_submission(record_id=rec_b, revision=3, text="第三版。", source="user_edit")
    sub_b7 = make_submission(record_id=rec_b, revision=7, text="第七版。", source="user_edit")

    r1 = httpx.put(
        f"{gateway.base_url}/v1/records/{rec_b}/revisions/1", trust_env=False, json=sub_b1, headers=headers, timeout=5.0
    )
    assert r1.status_code == 200
    assert r1.json()["superseded_revisions"] == []

    r3 = httpx.put(
        f"{gateway.base_url}/v1/records/{rec_b}/revisions/3", trust_env=False, json=sub_b3, headers=headers, timeout=5.0
    )
    assert r3.status_code == 200
    body_r3 = r3.json()
    assert_valid_contract("revision.schema.json#/$defs/stored", body_r3)
    assert body_r3["superseded_revisions"] == [1]

    r7 = httpx.put(
        f"{gateway.base_url}/v1/records/{rec_b}/revisions/7", trust_env=False, json=sub_b7, headers=headers, timeout=5.0
    )
    assert r7.status_code == 200
    body_r7 = r7.json()
    assert_valid_contract("revision.schema.json#/$defs/stored", body_r7)
    assert body_r7["superseded_revisions"] == [3]

    # Resubmitting revision 3 returns its original superseded_revisions=[1] and duplicate=True.
    r3_dup = httpx.put(
        f"{gateway.base_url}/v1/records/{rec_b}/revisions/3", trust_env=False, json=sub_b3, headers=headers, timeout=5.0
    )
    assert r3_dup.status_code == 200
    assert r3_dup.json()["duplicate"] is True
    assert r3_dup.json()["superseded_revisions"] == [1]


def test_conflicting_resubmit_preserves_original_and_non_origin_device_is_rejected(
    gateway: GatewayProcess,
) -> None:
    phone_a = gateway.pair_device("Phone A")
    phone_b = gateway.pair_device("Phone B")
    headers_a = {"Authorization": f"Bearer {phone_a['device_token']}"}
    headers_b = {"Authorization": f"Bearer {phone_b['device_token']}"}

    record_id = str(uuid.uuid4())
    sub_1 = make_submission(record_id=record_id, revision=1, text="原始内容。")
    resp_1 = httpx.put(
        f"{gateway.base_url}/v1/records/{record_id}/revisions/1", trust_env=False,
        json=sub_1,
        headers=headers_a,
        timeout=5.0,
    )
    assert resp_1.status_code == 200
    original_op_id = resp_1.json()["operation_id"]

    # Same record_id + revision with different text_sha256 -> RECORD_VERSION_CONFLICT.
    sub_conflict = make_submission(record_id=record_id, revision=1, text="篡改后的内容。")
    conflict_resp = httpx.put(
        f"{gateway.base_url}/v1/records/{record_id}/revisions/1", trust_env=False,
        json=sub_conflict,
        headers=headers_a,
        timeout=5.0,
    )
    assert conflict_resp.status_code == 409
    conflict_body = conflict_resp.json()
    assert_valid_contract("error.schema.json", conflict_body)
    assert conflict_body["code"] == "RECORD_VERSION_CONFLICT"

    # Resubmitting the original text still succeeds idempotently with original operation_id,
    # proving the conflicting submission did not overwrite it.
    verify_resp = httpx.put(
        f"{gateway.base_url}/v1/records/{record_id}/revisions/1", trust_env=False,
        json=sub_1,
        headers=headers_a,
        timeout=5.0,
    )
    assert verify_resp.status_code == 200
    assert verify_resp.json()["duplicate"] is True
    assert verify_resp.json()["operation_id"] == original_op_id

    # Different device submitting revision 2 for phone_a's record -> RECORD_NOT_ORIGIN_DEVICE.
    sub_2 = make_submission(record_id=record_id, revision=2, text="另一台设备试图写入。")
    non_origin_resp = httpx.put(
        f"{gateway.base_url}/v1/records/{record_id}/revisions/2", trust_env=False,
        json=sub_2,
        headers=headers_b,
        timeout=5.0,
    )
    assert non_origin_resp.status_code == 403
    non_origin_body = non_origin_resp.json()
    assert_valid_contract("error.schema.json", non_origin_body)
    assert non_origin_body["code"] == "RECORD_NOT_ORIGIN_DEVICE"


def test_max_utf8_boundary_1mib_is_accepted(gateway: GatewayProcess) -> None:
    paired = gateway.pair_device("Boundary Phone")
    headers = {"Authorization": f"Bearer {paired['device_token']}"}

    # Exactly 1,048,576 UTF-8 bytes (1 MiB) using multi-byte UTF-8 characters.
    max_text = ("中" * 349_525) + "a"
    assert len(max_text.encode("utf-8")) == 1024 * 1024

    record_id = str(uuid.uuid4())
    sub = make_submission(record_id=record_id, revision=1, text=max_text, source="user_edit")
    assert_valid_contract("revision.schema.json#/$defs/submission", sub)
    resp = httpx.put(
        f"{gateway.base_url}/v1/records/{record_id}/revisions/1", trust_env=False,
        json=sub,
        headers=headers,
        timeout=10.0,
    )
    assert resp.status_code == 200
    assert_valid_contract("revision.schema.json#/$defs/stored", resp.json())
