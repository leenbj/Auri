"""Seam 2 (gateway black box): how the `/mcp` endpoint behaves on the wire (Ticket 03).

Authentication is checked before anything else, a GET never holds a stream open, a revoked
Destination is cut off even while it holds a lease, and one endpoint serves Agents of both MCP
protocol eras.
"""
from __future__ import annotations

import httpx
import pytest

from harness import (  # noqa: F401
    GatewayProcess,
    World,
    assert_valid_contract,
    gateway,
    world,
)

MCP_ACCEPT = "application/json, text/event-stream"


def test_get_on_the_mcp_endpoint_is_refused_so_no_stream_is_held_open(world: World) -> None:
    destination = world.destination()
    headers = {"Authorization": f"Bearer {destination.credential}", "Accept": "text/event-stream"}

    # Not reading the body: a gateway that opened an event stream would answer 200 and never finish.
    with httpx.Client(trust_env=False, timeout=5.0) as http:
        with http.stream("GET", f"{world.gw.base_url}/mcp", headers=headers) as response:
            status, allow = response.status_code, response.headers.get("allow", "")

    assert status == 405
    assert "POST" in allow


def test_an_unauthenticated_get_on_the_mcp_endpoint_is_a_unified_401_not_a_405(
    gateway: GatewayProcess,
) -> None:
    response = httpx.get(
        f"{gateway.base_url}/mcp", trust_env=False, headers={"Accept": "text/event-stream"}, timeout=5.0
    )

    assert response.status_code == 401
    assert_valid_contract("error.schema.json", response.json())


def test_a_revoked_destination_is_refused_on_tool_calls_even_while_holding_a_lease(
    world: World,
) -> None:
    destination = world.destination()
    world.publish(destination)
    (held,) = world.agent(destination).claim()["deliveries"]

    world.revoke(destination)

    response = httpx.post(
        f"{world.gw.base_url}/mcp", trust_env=False,
        json={
            "jsonrpc": "2.0",
            "id": 1,
            "method": "tools/call",
            "params": {
                "name": "delivery_ack",
                "arguments": {"delivery_id": held["delivery_id"], "lease_token": held["lease_token"]},
            },
        },
        headers={"Authorization": f"Bearer {destination.credential}", "Accept": MCP_ACCEPT},
        timeout=5.0,
    )

    assert response.status_code == 401
    error = response.json()
    assert_valid_contract("error.schema.json", error)
    assert error["code"] == "GATEWAY_AUTH_REVOKED"


@pytest.mark.parametrize(
    ("mode", "protocol_version"),
    [("modern", "2026-07-28"), ("legacy", "2025-11-25")],
)
def test_the_whole_delivery_journey_works_in_both_mcp_protocol_eras(
    world: World, mode: str, protocol_version: str
) -> None:
    destination = world.destination()
    world.publish(destination)
    agent = world.agent(destination, mode=mode)

    (claimed,) = agent.claim()["deliveries"]
    renewed = agent.renew(claimed["delivery_id"], claimed["lease_token"])
    acked = agent.ack(claimed["delivery_id"], claimed["lease_token"])

    assert agent.protocol_version == protocol_version
    assert renewed["lease_token"] == claimed["lease_token"]
    assert acked["state"] == "ACKNOWLEDGED"
