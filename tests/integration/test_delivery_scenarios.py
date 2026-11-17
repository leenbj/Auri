"""Seam 2 (gateway black box): the contract's Delivery event sequences, replayed against the gateway (Tickets 03 & 04).

`contracts/fixtures/state-machines/delivery.scenarios.json` is the rule set. Each scenario is replayed
through the real gateway up to the first event that cannot be driven here; after the last replayed
step the state the sample promises is checked with a probe that needs no side channel.

Events and who owns their trigger:
- CLAIM, RENEW_LEASE, ACK, NACK_RETRYABLE, NACK_PERMANENT: the MCP tools (Ticket 03).
- SUPERSEDED, RECORD_DELETED: HTTP PUT new Revision / DELETE Record (Ticket 04).
- LEASE_EXPIRED: time. It needs a real 10 s wait, so `ack-after-lease-expired` is replayed in full by
  `test_delivery_lease_expiry.py`.
- DESTINATION_REVOKED: Ticket 02 can revoke, but a revoked credential gets a 401 before any Delivery
  state is visible over HTTP/MCP; the CANCELLED state becomes observable with Ticket 05's read tools.
"""
from __future__ import annotations

import json
from typing import Any

import httpx
import pytest

from harness import FIXTURE_DIR, McpAgent, World, assert_valid_contract, gateway, world  # noqa: F401

SCENARIOS: list[dict[str, Any]] = json.loads(
    (FIXTURE_DIR / "state-machines" / "delivery.scenarios.json").read_text(encoding="utf-8")
)["scenarios"]
DRIVABLE_EVENTS = {
    "CLAIM",
    "RENEW_LEASE",
    "ACK",
    "NACK_RETRYABLE",
    "NACK_PERMANENT",
    "SUPERSEDED",
    "RECORD_DELETED",
}

# Scenario -> index of the first step that is not replayed here, and why.
UNREPLAYED_FROM = {
    "ack-after-lease-expired": 1,  # LEASE_EXPIRED: test_delivery_lease_expiry.py
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
    initial_state = scenario["initial_state"]
    assert initial_state in {"PENDING", "LEASED", "ACKNOWLEDGED"}, initial_state
    destination = world.destination()
    published = world.publish(destination)
    record_id = published["record_id"]
    delivery_id = published["deliveries"][0]["delivery_id"]
    revision = 1
    agent = world.agent(destination)
    held: dict[str, Any] = {}
    if initial_state in {"LEASED", "ACKNOWLEDGED"}:
        (held,) = agent.claim()["deliveries"]
        if initial_state == "ACKNOWLEDGED":
            agent.ack(held["delivery_id"], held["lease_token"])
    state = initial_state

    for number, step in enumerate(replayable_steps(scenario)):
        event, expect, state = step["event"], step["expect"], step["state_after"]
        where = f"{scenario['name']} step {number} ({event}, expect {expect})"
        if event == "CLAIM":
            claimed = agent.claim()["deliveries"]
            if expect == "transition":
                assert [d["delivery_id"] for d in claimed] == [delivery_id], where
                held = claimed[0]
            else:
                assert expect == "rejected", where
                assert delivery_id not in [d["delivery_id"] for d in claimed], where
        elif event == "RENEW_LEASE":
            _expect_transition_or_rejection(agent, "delivery_renew_lease", held, expect, where)
        elif event == "ACK":
            if expect == "rejected":
                _expect_transition_or_rejection(agent, "delivery_ack", held, expect, where)
            else:
                acked = agent.ack(held["delivery_id"], held["lease_token"])
                assert acked["duplicate"] is (expect == "ignored"), where
                assert acked["state"] == state, where
        elif event == "SUPERSEDED":
            revision += 1
            sup_pub = world.publish(
                destination,
                record_id=record_id,
                revision=revision,
                text=f"修订版本 {revision}。",
                source="user_edit",
            )
            cancelled = [d["delivery_id"] for d in sup_pub["deliveries"] if d["state"] == "CANCELLED"]
            if expect == "transition":
                assert delivery_id in cancelled, where
            else:
                assert expect == "ignored", where
                assert delivery_id not in cancelled, where
        elif event == "RECORD_DELETED":
            del_resp = httpx.delete(
                f"{world.gw.base_url}/v1/records/{record_id}",
                headers={"Authorization": f"Bearer {world.device_token}"},
                timeout=5.0,
            )
            assert del_resp.status_code == 200, del_resp.text
            deleted = del_resp.json()
            assert_valid_contract("revision.schema.json#/$defs/record_deleted", deleted)
            if expect == "transition":
                assert delivery_id in deleted["cancelled_delivery_ids"], where
            else:
                assert expect == "ignored", where
                assert delivery_id not in deleted["cancelled_delivery_ids"], where
        else:  # NACK_RETRYABLE / NACK_PERMANENT
            assert expect == "transition", where
            nacked = agent.nack(
                held["delivery_id"], held["lease_token"], retryable=event == "NACK_RETRYABLE"
            )
            assert nacked["state"] == state, where

    _assert_state(agent, held, state, delivery_id, scenario["name"])


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
        assert delivery_id not in [d["delivery_id"] for d in agent.claim()["deliveries"]], where
        late_ack = agent.call_error(
            "delivery_ack", {"delivery_id": held["delivery_id"], "lease_token": held["lease_token"]}
        )
        assert late_ack["code"] == "REQUEST_INVALID", where
    elif state == "CANCELLED":
        assert delivery_id not in [d["delivery_id"] for d in agent.claim()["deliveries"]], where
        if held:
            late_ack = agent.call_error(
                "delivery_ack",
                {"delivery_id": held["delivery_id"], "lease_token": held["lease_token"]},
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
