"""Two isolated SDK agents use the local bridge and a real pinned-TLS Gateway."""
from __future__ import annotations

import asyncio
import json
import os
import pathlib
import subprocess
import sys

import pytest
from mcp import Client, StdioServerParameters

from harness import GatewayProcess, World, gateway  # noqa: F401

BRIDGE = pathlib.Path(__file__).resolve().parents[2] / "packages/mcp-stdio-bridge/bridge.py"


def test_two_destination_credentials_claim_and_ack_independently(gateway: GatewayProcess) -> None:
    world = World(gateway)
    first, second = world.destination("Hermes"), world.destination("Claude Code")
    stored = world.publish(first, second, text="Synthetic acceptance fixture")
    published = {delivery["destination_id"]: delivery["delivery_id"] for delivery in stored["deliveries"]}
    a, b = world.agent(first, mode="legacy"), world.agent(second, mode="legacy")

    (a_delivery,) = a.claim()["deliveries"]
    assert a_delivery["delivery_id"] == published[first.destination_id]
    assert a.ack(a_delivery["delivery_id"], a_delivery["lease_token"])["state"] == "ACKNOWLEDGED"
    (b_delivery,) = b.claim()["deliveries"]
    assert b_delivery["delivery_id"] == published[second.destination_id]
    assert b_delivery["delivery_id"] != a_delivery["delivery_id"]
    wrong_owner = a.call_error("delivery_ack", {
        "delivery_id": b_delivery["delivery_id"], "lease_token": b_delivery["lease_token"]
    })
    assert wrong_owner["code"] == "REQUEST_INVALID"
    assert b.ack(b_delivery["delivery_id"], b_delivery["lease_token"])["state"] == "ACKNOWLEDGED"
    assert a.claim()["deliveries"] == b.claim()["deliveries"] == []
    assert a.protocol_version == b.protocol_version == "2025-11-25"


@pytest.mark.parametrize("url,pin", [
    ("http://127.0.0.1:8443/mcp", "a" * 64),
    ("https://127.0.0.1:8443/mcp", "not-a-fingerprint"),
])
def test_bridge_rejects_insecure_configuration_without_logging_credentials(url: str, pin: str) -> None:
    credential = "synthetic-credential-must-not-appear-in-output"
    result = subprocess.run(
        [sys.executable, str(BRIDGE)], input="", capture_output=True, text=True, timeout=5,
        env={**os.environ, "AURI_GATEWAY_URL": url, "AURI_GATEWAY_SPKI_SHA256": pin,
             "AURI_DESTINATION_CREDENTIAL": credential},
    )
    assert result.returncode != 0
    assert credential not in result.stdout + result.stderr


def bridge_params(url: str, pin: str, credential: str) -> StdioServerParameters:
    return StdioServerParameters(command=sys.executable, args=[str(BRIDGE)], env={
        "AURI_GATEWAY_URL": url + "/mcp",
        "AURI_GATEWAY_SPKI_SHA256": pin,
        "AURI_DESTINATION_CREDENTIAL": credential,
    })


def test_bridge_stdio_client_claims_and_acks_over_pinned_tls(gateway: GatewayProcess) -> None:
    world = World(gateway)
    first, second = world.destination("Hermes bridge"), world.destination("Claude bridge")
    stored = world.publish(first, second)
    published = {delivery["destination_id"]: delivery["delivery_id"] for delivery in stored["deliveries"]}
    tls_gateway = GatewayProcess(gateway.data_dir, tls=True)
    tls_gateway.start()
    try:
        pin_result = tls_gateway.run_cli("pair", "--data-dir", str(gateway.data_dir), "--json")
        assert pin_result.returncode == 0
        pin = json.loads(pin_result.stdout)["public_key_sha256"]

        async def exercise() -> None:
            first_params = bridge_params(tls_gateway.base_url, pin, first.credential)
            second_params = bridge_params(tls_gateway.base_url, pin, second.credential)
            async with Client(first_params, mode="legacy") as a, Client(second_params, mode="legacy") as b:
                assert a.protocol_version == b.protocol_version == "2025-11-25"
                tools = await a.list_tools()
                assert {"delivery_claim", "delivery_ack"} <= {tool.name for tool in tools.tools}
                claim = await a.call_tool("delivery_claim", {})
                assert not claim.is_error
                (delivery,) = claim.structured_content["deliveries"]
                assert delivery["delivery_id"] == published[first.destination_id]
                ack = await a.call_tool("delivery_ack", {
                    "delivery_id": delivery["delivery_id"], "lease_token": delivery["lease_token"]
                })
                assert not ack.is_error
                assert ack.structured_content["state"] == "ACKNOWLEDGED"
                claim_b = await b.call_tool("delivery_claim", {})
                (delivery_b,) = claim_b.structured_content["deliveries"]
                assert delivery_b["delivery_id"] == published[second.destination_id]
                wrong_owner = await a.call_tool("delivery_ack", {
                    "delivery_id": delivery_b["delivery_id"], "lease_token": delivery_b["lease_token"]
                })
                assert wrong_owner.is_error
                assert wrong_owner.structured_content["code"] == "REQUEST_INVALID"
                ack_b = await b.call_tool("delivery_ack", {
                    "delivery_id": delivery_b["delivery_id"], "lease_token": delivery_b["lease_token"]
                })
                assert not ack_b.is_error
                assert ack_b.structured_content["state"] == "ACKNOWLEDGED"

        asyncio.run(exercise())
    finally:
        tls_gateway.stop()


def test_bridge_rejects_wrong_pin_before_auth_and_accepts_same_key_renewal(
    gateway: GatewayProcess,
) -> None:
    world = World(gateway)
    destination = world.destination()
    tls_gateway = GatewayProcess(gateway.data_dir, tls=True)
    tls_gateway.start()
    try:
        result = tls_gateway.run_cli("pair", "--data-dir", str(gateway.data_dir), "--json")
        assert result.returncode == 0
        pin = json.loads(result.stdout)["public_key_sha256"]

        async def list_tools(fingerprint: str) -> None:
            async with Client(bridge_params(tls_gateway.base_url, fingerprint, destination.credential), mode="legacy") as agent:
                assert "delivery_claim" in {tool.name for tool in (await agent.list_tools()).tools}

        asyncio.run(list_tools(pin))
        tls_gateway.wait_for_output("mcp_authenticated", occurrences=1)
        before = tls_gateway.count_output("mcp_authenticated")
        wrong_pin = ("0" if pin[0] != "0" else "1") + pin[1:]
        with pytest.raises(Exception):
            asyncio.run(list_tools(wrong_pin))
        assert tls_gateway.count_output("mcp_authenticated") == before

        tls_gateway.stop()
        renewed = tls_gateway.run_cli("renew-cert", "--data-dir", str(gateway.data_dir), "--json")
        assert renewed.returncode == 0
        assert json.loads(renewed.stdout)["public_key_sha256"] == pin
        tls_gateway.start()
        asyncio.run(list_tools(pin))
    finally:
        stdout, stderr = tls_gateway.stop()
        assert destination.credential not in stdout + stderr
