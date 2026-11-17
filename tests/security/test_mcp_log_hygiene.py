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

from harness import World, gateway, world  # noqa: E402,F401


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
