"""Seam 1 (data contract): the gateway's delivery state machine follows the frozen contract.

The rule set is `contracts/fixtures/state-machines/delivery.machine.json` plus the event
sequences in `delivery.scenarios.json`. These tests read those files and compare the
gateway's own implementation against them; nothing here is copied into the gateway.
"""
from __future__ import annotations

import itertools
import sys

import pytest

from harness import GATEWAY_SRC, load_fixture

sys.path.insert(0, str(GATEWAY_SRC))
from auri_gateway.delivery_machine import step  # noqa: E402

MACHINE = load_fixture("state-machines/delivery.machine.json")
SCENARIOS = load_fixture("state-machines/delivery.scenarios.json")["scenarios"]
CONTRACT_TABLE = {(row["from"], row["event"]): row for row in MACHINE["transitions"]}


def contract_outcome(state: str, event: str) -> tuple[str, str]:
    """A pair missing from the contract table is rejected and leaves the state unchanged."""
    rule = CONTRACT_TABLE.get((state, event))
    if rule is None:
        return "rejected", state
    return rule["outcome"], rule["to"]


@pytest.mark.parametrize(
    ("state", "event"),
    list(itertools.product(MACHINE["states"], MACHINE["events"])),
    ids=lambda value: str(value),
)
def test_every_state_event_pair_follows_the_contract_transition_table(
    state: str, event: str
) -> None:
    expected_kind, expected_state = contract_outcome(state, event)

    outcome = step(state, event)

    assert (outcome.kind, outcome.state) == (expected_kind, expected_state)


@pytest.mark.parametrize("scenario", SCENARIOS, ids=lambda scenario: scenario["name"])
def test_every_contract_scenario_replays_to_the_expected_outcomes(scenario: dict) -> None:
    state = scenario["initial_state"]
    for index, expected in enumerate(scenario["steps"]):
        outcome = step(state, expected["event"])
        state = outcome.state
        where = f"{scenario['name']} step {index} ({expected['event']})"
        assert outcome.kind == expected["expect"], where
        assert state == expected["state_after"], where
