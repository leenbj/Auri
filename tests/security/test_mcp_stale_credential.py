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
import time
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
HEADERS_ARRIVE_FIRST_SECONDS = 0.5  # the gateway authenticates as soon as the headers are in


def claim_with_body_held_back(
    world: World, credential: str, meanwhile: Callable[[], None]
) -> httpx.Response:
    """POST a delivery_claim whose body is only sent after `meanwhile` ran (headers go out first)."""
    payload = json.dumps(CLAIM_CALL).encode()

    def body():
        time.sleep(HEADERS_ARRIVE_FIRST_SECONDS)
        meanwhile()
        yield payload

    return httpx.post(
        f"{world.gw.base_url}/mcp",
        content=body(),
        headers={
            "Authorization": f"Bearer {credential}",
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
        },
        timeout=15.0,
    )


def refusal_code(response: httpx.Response) -> str:
    """The unified error code of a refused request, whether refused at the door or inside the tool."""
    if response.status_code == 401:
        return response.json()["code"]
    assert response.status_code == 200, response.text
    result = response.json()["result"]
    assert result["isError"] is True, result
    return result["structuredContent"]["code"]


def test_a_request_held_back_works_when_nothing_changes_meanwhile(world: World) -> None:
    # Control: the way the requests below are sent is itself valid and does claim the Delivery.
    destination = world.destination()
    world.publish(destination)

    response = claim_with_body_held_back(world, destination.credential, lambda: None)

    assert response.status_code == 200, response.text
    result = response.json()["result"]
    assert result["isError"] is False
    assert len(result["structuredContent"]["deliveries"]) == 1


def test_a_credential_rotated_while_the_body_is_held_back_cannot_claim(world: World) -> None:
    destination = world.destination()
    world.publish(destination)
    rotated: list[AgentDestination] = []

    response = claim_with_body_held_back(
        world,
        destination.credential,
        lambda: rotated.append(world.rotate_credential(destination)),
    )

    assert refusal_code(response) == "GATEWAY_AUTH_REVOKED"
    # Nothing was leased by the stale request: the new credential still gets the Delivery.
    (claimed,) = world.agent(rotated[0]).claim()["deliveries"]
    assert claimed["lease_token"]


def test_a_destination_revoked_while_the_body_is_held_back_cannot_claim(world: World) -> None:
    destination = world.destination()
    world.publish(destination)

    response = claim_with_body_held_back(
        world, destination.credential, lambda: world.revoke(destination)
    )

    assert refusal_code(response) == "GATEWAY_AUTH_REVOKED"
