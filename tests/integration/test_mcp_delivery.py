"""Seam 2 (gateway black box): an Agent claims and acknowledges Deliveries over MCP (Ticket 03).

Real gateway process, real SQLite. The phone side talks HTTP, the Agent side talks MCP with a
Destination credential. Every request and response is validated against `contracts/`.
"""
from __future__ import annotations

import json

import pytest
from jsonschema import Draft202012Validator

from harness import (  # noqa: F401
    FIXTURE_DIR,
    World,
    gateway,
    world,
)

TOOL_CONTRACTS = {
    "delivery_claim": ("claim_request", "claim_response"),
    "delivery_renew_lease": ("renew_request", "renew_response"),
    "delivery_ack": ("ack_request", "ack_response"),
    "delivery_nack": ("nack_request", "nack_response"),
}


def contract_instances(schema_name: str, folder: str) -> list[tuple[str, object]]:
    """Fixture instances (file name, instance) in `messages/<folder>` that target one delivery def."""
    target = f"delivery.schema.json#/$defs/{schema_name}"
    found = []
    for path in sorted((FIXTURE_DIR / "messages" / folder).glob("delivery.*.json")):
        fixture = json.loads(path.read_text(encoding="utf-8"))
        if fixture["schema"] == target:
            found.append((path.name, fixture["instance"]))
    return found


def test_mcp_endpoint_advertises_the_four_delivery_tools_with_contract_equivalent_schemas(
    world: World,
) -> None:
    tools = {tool.name: tool for tool in world.agent(world.destination()).list_tools()}

    assert set(TOOL_CONTRACTS) <= set(tools)
    for name, (request_name, response_name) in TOOL_CONTRACTS.items():
        tool = tools[name]
        accepts_input = Draft202012Validator(tool.input_schema)
        produces_output = Draft202012Validator(tool.output_schema)
        for fixture_name, instance in contract_instances(request_name, "valid"):
            assert accepts_input.is_valid(instance), f"{name} must accept {fixture_name}"
        for fixture_name, instance in contract_instances(request_name, "schema-invalid"):
            assert not accepts_input.is_valid(instance), f"{name} must reject {fixture_name}"
        for fixture_name, instance in contract_instances(response_name, "valid"):
            assert produces_output.is_valid(instance), f"{name} output must allow {fixture_name}"
        for fixture_name, instance in contract_instances(response_name, "schema-invalid"):
            assert not produces_output.is_valid(instance), f"{name} output must reject {fixture_name}"
        # Every delivery tool writes (lease, renewal, ack, nack): none may claim to be read-only.
        assert tool.annotations is not None and tool.annotations.read_only_hint is False, name
