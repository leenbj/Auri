"""Black-box security tests for the Agent MCP endpoint (Ticket 03).

Identity is the Destination credential and nothing else: anything the request body claims about
who the caller is must be rejected, and a Destination must never reach another one's Deliveries.
"""
from __future__ import annotations

import json
import pathlib
import sys
from typing import Any

import pytest

_INTEGRATION_DIR = pathlib.Path(__file__).resolve().parents[1] / "integration"
if str(_INTEGRATION_DIR) not in sys.path:
    sys.path.append(str(_INTEGRATION_DIR))

from harness import (  # noqa: E402
    FIXTURE_DIR,
    World,
    gateway,  # noqa: F401
    world,  # noqa: F401
)

TOOL_FOR_REQUEST = {
    "claim_request": "delivery_claim",
    "renew_request": "delivery_renew_lease",
    "ack_request": "delivery_ack",
    "nack_request": "delivery_nack",
}


def schema_invalid_requests(*request_names: str) -> list[tuple[str, str, dict[str, Any]]]:
    """(fixture name, tool, instance) for contract-invalid tool inputs of the given requests."""
    found = []
    for path in sorted((FIXTURE_DIR / "messages" / "schema-invalid").glob("delivery.*.json")):
        fixture = json.loads(path.read_text(encoding="utf-8"))
        request_name = fixture["schema"].rsplit("/", 1)[-1]
        if request_name in request_names:
            found.append((path.stem, TOOL_FOR_REQUEST[request_name], fixture["instance"]))
    return found


INVALID_INPUTS = schema_invalid_requests("claim_request", "renew_request", "ack_request", "nack_request")


@pytest.mark.parametrize(
    ("tool", "arguments"),
    [(tool, instance) for _, tool, instance in INVALID_INPUTS],
    ids=[name for name, _, _ in INVALID_INPUTS],
)
def test_contract_invalid_tool_input_is_rejected_and_has_no_effect(
    world: World, tool: str, arguments: dict[str, Any]
) -> None:
    destination = world.destination()
    world.publish(destination)
    agent = world.agent(destination)

    error = agent.call_error(tool, arguments)

    assert error["code"] == "REQUEST_INVALID"
    assert error["retryable"] is False
    # The rejected call had no effect: the pending Delivery is still there to be claimed.
    assert len(agent.claim()["deliveries"]) == 1


def test_claim_cannot_name_another_destination_or_tenant_in_its_input(world: World) -> None:
    mine = world.destination("Mine")
    other = world.destination("Other")
    world.publish(other, text="只发给 Other 的口述。")
    agent = world.agent(mine)

    for self_reported in (
        {"destination_id": other.destination_id},
        {"tenant_id": "00000000-0000-4000-8000-000000000000"},
        {"agent_id": "other-agent", "limit": 1},
    ):
        error = agent.call_error("delivery_claim", self_reported)
        assert error["code"] == "REQUEST_INVALID", self_reported

    # Nothing was handed out to the caller, and the other Destination still gets its own content.
    assert agent.claim()["deliveries"] == []
    assert [d["text"] for d in world.agent(other).claim()["deliveries"]] == ["只发给 Other 的口述。"]


def test_a_destination_only_ever_claims_the_deliveries_addressed_to_it(world: World) -> None:
    alpha = world.destination("Alpha")
    beta = world.destination("Beta")
    stored = world.publish(alpha, beta, text="发给两个 Agent 的同一句话。")
    delivery_ids = {d["destination_id"]: d["delivery_id"] for d in stored["deliveries"]}

    alpha_claim = world.agent(alpha).claim()
    beta_claim = world.agent(beta).claim()

    assert [d["delivery_id"] for d in alpha_claim["deliveries"]] == [delivery_ids[alpha.destination_id]]
    assert [d["delivery_id"] for d in beta_claim["deliveries"]] == [delivery_ids[beta.destination_id]]


@pytest.mark.parametrize("tool", ["delivery_renew_lease", "delivery_ack", "delivery_nack"])
def test_another_destination_cannot_renew_ack_or_nack_even_with_a_valid_lease_token(
    world: World, tool: str
) -> None:
    owner = world.destination("Owner")
    intruder = world.destination("Intruder")
    world.publish(owner)
    owner_agent = world.agent(owner)
    (delivery,) = owner_agent.claim()["deliveries"]
    intruder_agent = world.agent(intruder)
    arguments = {"delivery_id": delivery["delivery_id"], "lease_token": delivery["lease_token"]}
    if tool == "delivery_nack":
        arguments |= {"reason": "不是我的", "retryable": False}

    error = intruder_agent.call_error(tool, arguments)

    assert error["code"] == "REQUEST_INVALID"
    # Not even the existence of the other Destination's Delivery is revealed.
    unknown = intruder_agent.call_error(
        tool, {**arguments, "delivery_id": "00000000-0000-4000-8000-000000000000"}
    )
    assert (error["code"], error["message"]) == (unknown["code"], unknown["message"])
    # The owner's lease is untouched: it can still acknowledge.
    assert owner_agent.ack(delivery["delivery_id"], delivery["lease_token"])["duplicate"] is False


@pytest.mark.parametrize("tool", ["delivery_renew_lease", "delivery_ack", "delivery_nack"])
def test_a_self_reported_identity_field_invalidates_a_call_on_the_callers_own_delivery(
    world: World, tool: str
) -> None:
    mine = world.destination("Mine")
    other = world.destination("Other")
    world.publish(mine)
    agent = world.agent(mine)
    (delivery,) = agent.claim()["deliveries"]
    arguments = {"delivery_id": delivery["delivery_id"], "lease_token": delivery["lease_token"]}
    if tool == "delivery_nack":
        arguments |= {"reason": "自报身份", "retryable": False}

    for self_reported in (
        {"destination_id": other.destination_id},
        {"tenant_id": "00000000-0000-4000-8000-000000000000"},
        {"agent_id": "someone-else"},
    ):
        error = agent.call_error(tool, arguments | self_reported)
        assert error["code"] == "REQUEST_INVALID", (tool, self_reported)

    # Without the self-reported field the same call is valid, and none of the rejected ones
    # had any effect: this is the first acknowledgement.
    assert agent.ack(delivery["delivery_id"], delivery["lease_token"])["duplicate"] is False
