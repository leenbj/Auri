"""Seam 2 (gateway black box): a credential that stopped being valid cannot act, even mid-request (Ticket 03).

The gateway checks the credential when a request's headers arrive. A client can hold a request open
(headers sent, body not yet), let the phone rotate or revoke the credential, and only then send the
body. That request must be refused: the credential has to be valid at the moment the Delivery
operation runs, not only when the request started.
"""
from __future__ import annotations

import json
import pathlib
import sys
from collections.abc import Callable

import httpx
import pytest

_INTEGRATION_DIR = pathlib.Path(__file__).resolve().parents[1] / "integration"
if str(_INTEGRATION_DIR) not in sys.path:
    sys.path.append(str(_INTEGRATION_DIR))

from harness import AgentDestination, World, gateway, world  # noqa: E402,F401

CLAIM_CALL = {
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/call",
    "params": {"name": "delivery_claim", "arguments": {}},
}


def claim_with_body_held_back(
    world: World, destination: AgentDestination, meanwhile: Callable[[], None]
) -> httpx.Response:
    """POST a delivery_claim whose body is only sent after `meanwhile` ran (headers go out first).

    `meanwhile` runs once the gateway has logged that the request passed authentication, so the
    credential is certainly still valid when the request starts and changes only afterwards.
    """
    payload = json.dumps(CLAIM_CALL).encode()
    authenticated = f"mcp_authenticated destination_id={destination.destination_id}"
    before = world.gw.count_output(authenticated)

    def body():
        world.gw.wait_for_output(authenticated, occurrences=before + 1)
        meanwhile()
        yield payload

    return httpx.post(
        f"{world.gw.base_url}/mcp", trust_env=False,
        content=body(),
        headers={
            "Authorization": f"Bearer {destination.credential}",
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
            "Transfer-Encoding": "chunked",
        },
        timeout=15.0,
    )


def refusal_code(response: httpx.Response) -> str:
    """The unified error code of a tool call that was refused after it had been accepted.

    The request passed the door while its credential was valid, so the refusal has to come from
    the check inside the tool, as an error result (HTTP 200), not from a 401 at the door.
    """
    assert response.status_code == 200, response.text
    result = response.json()["result"]
    assert result["isError"] is True, result
    return result["structuredContent"]["code"]


def test_a_request_held_back_works_when_nothing_changes_meanwhile(world: World) -> None:
    # Control: the way the requests below are sent is itself valid and does claim the Delivery.
    destination = world.destination()
    world.publish(destination)

    response = claim_with_body_held_back(world, destination, lambda: None)

    assert response.status_code == 200, response.text
    result = response.json()["result"]
    assert result["isError"] is False
    assert len(result["structuredContent"]["deliveries"]) == 1


def test_a_credential_rotated_while_the_body_is_held_back_cannot_claim(world: World) -> None:
    destination = world.destination()
    world.publish(destination)
    rotated: list[AgentDestination] = []

    response = claim_with_body_held_back(
        world, destination, lambda: rotated.append(world.rotate_credential(destination))
    )

    assert refusal_code(response) == "GATEWAY_AUTH_REVOKED"
    # Nothing was leased by the stale request: the new credential still gets the Delivery.
    (claimed,) = world.agent(rotated[0]).claim()["deliveries"]
    assert claimed["lease_token"]


def test_a_destination_revoked_while_the_body_is_held_back_cannot_claim(world: World) -> None:
    destination = world.destination()
    world.publish(destination)

    response = claim_with_body_held_back(world, destination, lambda: world.revoke(destination))

    assert refusal_code(response) == "GATEWAY_AUTH_REVOKED"
