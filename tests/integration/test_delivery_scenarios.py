"""Seam 2 (gateway black box): the contract's Delivery event sequences, replayed against the gateway (Ticket 03).

`contracts/fixtures/state-machines/delivery.scenarios.json` is the rule set. Each scenario is replayed
through the real gateway up to the first event that this ticket has no way to cause; after the last
replayed step the state the sample promises is checked with a probe that needs no side channel.

Events and who owns their trigger:
- CLAIM, RENEW_LEASE, ACK, NACK_RETRYABLE, NACK_PERMANENT: the MCP tools of this ticket.
- LEASE_EXPIRED: time. It needs a real 10 s wait, so `ack-after-lease-expired` is replayed in full by
  `test_delivery_lease_expiry.py`.
- SUPERSEDED, RECORD_DELETED: Ticket 04 (a new Revision, deleting a Record).
- DESTINATION_REVOKED: Ticket 02 can revoke, but a revoked credential gets a 401 before any Delivery
  state is visible over HTTP/MCP; the CANCELLED state becomes observable with Ticket 05's read tools.
"""
from __future__ import annotations

import json
from typing import Any

import pytest

from harness import FIXTURE_DIR, McpAgent, World, gateway, world  # noqa: F401

SCENARIOS: list[dict[str, Any]] = json.loads(
    (FIXTURE_DIR / "state-machines" / "delivery.scenarios.json").read_text(encoding="utf-8")
)["scenarios"]
DRIVABLE_EVENTS = {"CLAIM", "RENEW_LEASE", "ACK", "NACK_RETRYABLE", "NACK_PERMANENT"}

# Scenario -> index of the first step that is not replayed here, and why.
UNREPLAYED_FROM = {
    "ack-after-lease-expired": 1,  # LEASE_EXPIRED: test_delivery_lease_expiry.py
    "nack-retryable-then-permanent": 4,  # SUPERSEDED: Ticket 04
    "superseded-before-claim": 0,  # SUPERSEDED: Ticket 04
    "superseded-after-claim": 1,  # SUPERSEDED: Ticket 04
    "record-deleted-before-claim": 0,  # RECORD_DELETED: Ticket 04
    "record-deleted-while-leased": 0,  # RECORD_DELETED: Ticket 04
    "record-deleted-after-ack": 0,  # RECORD_DELETED: Ticket 04
    "destination-revoked": 0,  # DESTINATION_REVOKED: observable with Ticket 05
    "destination-revoked-while-leased": 0,  # DESTINATION_REVOKED: observable with Ticket 05
}


def replayable_steps(scenario: dict[str, Any]) -> list[dict[str, Any]]:
    steps = []
    for step in scenario["steps"]:
        if step["event"] not in DRIVABLE_EVENTS:
            break
        steps.append(step)
    return steps


def test_every_scenario_is_replayed_or_its_missing_steps_are_declared() -> None:
    actually_unreplayed = {
        scenario["name"]: len(replayable_steps(scenario))
        for scenario in SCENARIOS
        if len(replayable_steps(scenario)) < len(scenario["steps"])
    }

    assert actually_unreplayed == UNREPLAYED_FROM


def replay(world: World, scenario: dict[str, Any]) -> None:
    assert scenario["initial_state"] == "PENDING", "only a freshly published Delivery can be set up"
    destination = world.destination()
    published = world.publish(destination)
    agent = world.agent(destination)
    held: dict[str, Any] = {}
    state = "PENDING"

    for number, step in enumerate(replayable_steps(scenario)):
        event, expect, state = step["event"], step["expect"], step["state_after"]
        where = f"{scenario['name']} step {number} ({event}, expect {expect})"
        if event == "CLAIM":
            claimed = agent.claim()["deliveries"]
            if expect == "transition":
                assert [d["delivery_id"] for d in claimed] == [
                    d["delivery_id"] for d in published["deliveries"]
                ], where
                held = claimed[0]
            else:
                assert expect == "rejected", where
                assert claimed == [], where
        elif event == "RENEW_LEASE":
            _expect_transition_or_rejection(agent, "delivery_renew_lease", held, expect, where)
        elif event == "ACK":
            if expect == "rejected":
                _expect_transition_or_rejection(agent, "delivery_ack", held, expect, where)
            else:
                acked = agent.ack(held["delivery_id"], held["lease_token"])
                assert acked["duplicate"] is (expect == "ignored"), where
                assert acked["state"] == state, where
        else:  # NACK_RETRYABLE / NACK_PERMANENT
            assert expect == "transition", where
            nacked = agent.nack(
                held["delivery_id"], held["lease_token"], retryable=event == "NACK_RETRYABLE"
            )
            assert nacked["state"] == state, where

    _assert_state(agent, held, state, published["deliveries"][0]["delivery_id"], scenario["name"])


def _expect_transition_or_rejection(
    agent: McpAgent, tool: str, held: dict[str, Any], expect: str, where: str
) -> None:
    arguments = {"delivery_id": held["delivery_id"], "lease_token": held["lease_token"]}
    if expect == "transition":
        agent.call_ok(tool, arguments)
    else:
        assert expect == "rejected", where
        agent.call_error(tool, arguments)


def _assert_state(
    agent: McpAgent, held: dict[str, Any], state: str, delivery_id: str, name: str
) -> None:
    """Check the state promised after the last replayed step, using only the public tools."""
    where = f"{name}: the Delivery must end {state}"
    if state == "LEASED":
        agent.renew(held["delivery_id"], held["lease_token"])  # the holder's lease still works
    elif state == "PENDING":
        assert [d["delivery_id"] for d in agent.claim()["deliveries"]] == [delivery_id], where
    elif state == "ACKNOWLEDGED":
        assert agent.ack(held["delivery_id"], held["lease_token"])["duplicate"] is True, where
    elif state == "REJECTED":
        assert agent.claim()["deliveries"] == [], where  # never offered again
        late_ack = agent.call_error(
            "delivery_ack", {"delivery_id": held["delivery_id"], "lease_token": held["lease_token"]}
        )
        assert late_ack["code"] == "REQUEST_INVALID", where
    else:
        raise AssertionError(f"{where}: no black-box probe for {state}")


@pytest.mark.parametrize(
    "scenario",
    [scenario for scenario in SCENARIOS if replayable_steps(scenario)],
    ids=lambda scenario: scenario["name"],
)
def test_contract_scenario_replayed_through_the_gateway(world: World, scenario: dict[str, Any]) -> None:
    replay(world, scenario)
