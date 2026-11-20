"""Seam 2 (gateway black box): the gateway's own output stays free of private data (Ticket 03).

User story 71 / CLAUDE.md: logs by default contain no dictated text, audio or credentials. The MCP
path handles all of those, so after a full journey (including the error paths) none of them may
appear in what the gateway process wrote.
"""
from __future__ import annotations

import pathlib
import sys
import uuid

_INTEGRATION_DIR = pathlib.Path(__file__).resolve().parents[1] / "integration"
if str(_INTEGRATION_DIR) not in sys.path:
    sys.path.append(str(_INTEGRATION_DIR))

import httpx

from harness import World, gateway, make_submission, world  # noqa: E402,F401


def test_the_gateway_output_never_contains_dictated_text_lease_tokens_or_credentials(
    world: World,
) -> None:
    dictated = f"口述里的私密内容-{uuid.uuid4()}"
    note = f"确认备注-{uuid.uuid4()}"
    reason = f"拒收原因-{uuid.uuid4()}"
    destination = world.destination()
    world.publish(destination, text=dictated)
    world.publish(destination, text=f"{dictated}（第二条）")
    agent = world.agent(destination)

    first, second = agent.claim()["deliveries"]
    agent.renew(first["delivery_id"], first["lease_token"])
    agent.ack(first["delivery_id"], first["lease_token"], note=note)
    agent.nack(second["delivery_id"], second["lease_token"], retryable=False, reason=reason)
    # Error paths: a stale lease and a call that names an identity of its own.
    agent.call_error(
        "delivery_ack", {"delivery_id": first["delivery_id"], "lease_token": "x" * 32}
    )
    agent.call("delivery_claim", {"tenant_id": str(uuid.uuid4())})
    stdout, stderr = world.gw.stop()
    output = stdout + stderr

    # Control: the output was captured and does record that the Destination connected.
    assert f"destination_id={destination.destination_id}" in output
    for name, secret in {
        "dictated text": dictated,
        "ack note": note,
        "nack reason": reason,
        "first lease token": first["lease_token"],
        "second lease token": second["lease_token"],
        "destination credential": destination.credential,
        "device token": world.device_token,
    }.items():
        assert secret not in output, f"the gateway output contains the {name}"


def test_the_gateway_output_stays_clean_across_conflicts_rejections_and_delete(
    world: World,
) -> None:
    other = world.gw.pair_device("Intruder Phone")
    destination = world.destination()
    record_id = str(uuid.uuid4())
    secret_v1 = f"首版私密口述-{uuid.uuid4()}"
    secret_conflict = f"冲突私密口述-{uuid.uuid4()}"
    secret_v2 = f"二版私密口述-{uuid.uuid4()}"

    world.publish(destination, record_id=record_id, revision=1, text=secret_v1)

    # Version conflict, non-origin delete, origin delete, and post-delete submit.
    conflict_sub = make_submission(record_id=record_id, revision=1, text=secret_conflict)
    httpx.put(
        f"{world.gw.base_url}/v1/records/{record_id}/revisions/1", trust_env=False,
        json=conflict_sub,
        headers={"Authorization": f"Bearer {world.device_token}"},
        timeout=5.0,
    )
    httpx.delete(
        f"{world.gw.base_url}/v1/records/{record_id}", trust_env=False,
        headers={"Authorization": f"Bearer {other['device_token']}"},
        timeout=5.0,
    )
    httpx.delete(
        f"{world.gw.base_url}/v1/records/{record_id}", trust_env=False,
        headers={"Authorization": f"Bearer {world.device_token}"},
        timeout=5.0,
    )
    rev2_sub = make_submission(record_id=record_id, revision=2, text=secret_v2)
    httpx.put(
        f"{world.gw.base_url}/v1/records/{record_id}/revisions/2", trust_env=False,
        json=rev2_sub,
        headers={"Authorization": f"Bearer {world.device_token}"},
        timeout=5.0,
    )

    stdout, stderr = world.gw.stop()
    output = stdout + stderr
    assert f"record_deleted record_id={record_id}" in output
    for name, secret in {
        "v1 text": secret_v1,
        "conflict text": secret_conflict,
        "v2 text": secret_v2,
        "origin token": world.device_token,
        "intruder token": other["device_token"],
        "destination credential": destination.credential,
    }.items():
        assert secret not in output, f"the gateway output contains the {name}"
