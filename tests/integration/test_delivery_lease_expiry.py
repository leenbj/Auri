"""Seam 2 (gateway black box): what happens when a lease runs out (Ticket 03).

The shortest lease the contract allows is 10 seconds and the gateway is a real process, so these
tests use real time. To pay for the wait only once, one module-scoped gateway has several Destinations
that each took a 10 second lease; the fixture waits until those leases have run out, and every
test then acts on its own Destination's Delivery.
"""
from __future__ import annotations

import time
from datetime import datetime, timedelta, timezone
from typing import NamedTuple

import pytest

from harness import AgentDestination, GatewayProcess, World

LEASE_SECONDS = 10  # the shortest lease the contract allows
# The `renewed` Delivery has a longer lease and is renewed 6 s before it would end, so it is
# still held 6 s after the original expiry: the test process may stall for about 5 s at either
# end of that window before these tests become unreliable.
RENEWED_LEASE_SECONDS = 12
RENEW_BEFORE_EXPIRY = timedelta(seconds=6)
ROLES = (
    "renewed",
    "ack_late",
    "nack_late",
    "scenario",
    "renew_late",
    "stale_holder",
    "reclaim",
    "acknowledged",
)


def parse_timestamp(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def wait_until(moment: datetime) -> None:
    delay = (moment - datetime.now(timezone.utc)).total_seconds()
    if delay > 0:
        time.sleep(delay)


class AgedLeases(NamedTuple):
    world: World
    destinations: dict[str, AgentDestination]
    deliveries: dict[str, dict]  # the Delivery each role claimed, with the token it holds
    first_ack: dict  # the response to the acknowledgement made while the lease was still valid


@pytest.fixture(scope="module")
def aged(tmp_path_factory: pytest.TempPathFactory):
    gateway = GatewayProcess(tmp_path_factory.mktemp("lease-expiry") / "gateway-data")
    gateway.start()
    try:
        world = World(gateway)
        destinations = {role: world.destination(role) for role in ROLES}
        for role in ROLES:
            world.publish(destinations[role], text=f"租约到期演示：{role}")

        deliveries: dict[str, dict] = {}
        # `renewed` takes its lease last, so its original expiry is the latest of all.
        for role in (*ROLES[1:], ROLES[0]):
            (deliveries[role],) = world.agent(destinations[role]).claim(
                lease_seconds=RENEWED_LEASE_SECONDS if role == "renewed" else LEASE_SECONDS
            )["deliveries"]

        first_ack = world.agent(destinations["acknowledged"]).ack(
            deliveries["acknowledged"]["delivery_id"], deliveries["acknowledged"]["lease_token"]
        )

        original_expiry = parse_timestamp(deliveries["renewed"]["lease_expires_at"])
        wait_until(original_expiry - RENEW_BEFORE_EXPIRY)
        renewed = world.agent(destinations["renewed"]).renew(
            deliveries["renewed"]["delivery_id"], deliveries["renewed"]["lease_token"]
        )
        assert parse_timestamp(renewed["lease_expires_at"]) > original_expiry + timedelta(seconds=4)

        wait_until(original_expiry + timedelta(seconds=0.3))  # now every original lease is over
        yield AgedLeases(world, destinations, deliveries, first_ack)
    finally:
        gateway.stop()


def test_a_renewed_lease_outlives_its_original_expiry(aged: AgedLeases) -> None:
    held = aged.deliveries["renewed"]
    agent = aged.world.agent(aged.destinations["renewed"])

    acked = agent.ack(held["delivery_id"], held["lease_token"])

    assert acked["duplicate"] is False


def test_ack_after_the_lease_expired_is_refused_with_the_lease_expired_error(
    aged: AgedLeases,
) -> None:
    held = aged.deliveries["ack_late"]
    agent = aged.world.agent(aged.destinations["ack_late"])

    error = agent.call_error(
        "delivery_ack", {"delivery_id": held["delivery_id"], "lease_token": held["lease_token"]}
    )

    assert error["code"] == "DELIVERY_LEASE_EXPIRED"
    assert error["retryable"] is True
    assert error["required_action"]


def test_a_late_permanent_nack_is_refused_and_does_not_reject_the_delivery(
    aged: AgedLeases,
) -> None:
    held = aged.deliveries["nack_late"]
    agent = aged.world.agent(aged.destinations["nack_late"])

    error = agent.call_error(
        "delivery_nack",
        {
            "delivery_id": held["delivery_id"],
            "lease_token": held["lease_token"],
            "reason": "太晚了",
            "retryable": False,
        },
    )
    (still_pending,) = agent.claim()["deliveries"]

    assert error["code"] == "DELIVERY_LEASE_EXPIRED"
    assert still_pending["delivery_id"] == held["delivery_id"]


def test_renew_after_the_lease_expired_is_refused(aged: AgedLeases) -> None:
    held = aged.deliveries["renew_late"]
    agent = aged.world.agent(aged.destinations["renew_late"])

    error = agent.call_error(
        "delivery_renew_lease",
        {"delivery_id": held["delivery_id"], "lease_token": held["lease_token"]},
    )

    assert error["code"] == "DELIVERY_LEASE_EXPIRED"


def test_a_delivery_whose_lease_expired_is_pending_again_with_a_fresh_lease(
    aged: AgedLeases,
) -> None:
    held = aged.deliveries["reclaim"]

    (again,) = aged.world.agent(aged.destinations["reclaim"]).claim()["deliveries"]

    assert again["delivery_id"] == held["delivery_id"]
    assert again["lease_token"] != held["lease_token"]
    assert parse_timestamp(again["lease_expires_at"]) > datetime.now(timezone.utc)


def test_contract_scenario_ack_after_lease_expired_recovers_with_a_new_claim(
    aged: AgedLeases,
) -> None:
    # CLAIM (fixture), LEASE_EXPIRED (waited in fixture), then: ACK rejected, CLAIM, ACK.
    held = aged.deliveries["scenario"]
    agent = aged.world.agent(aged.destinations["scenario"])

    late = agent.call_error(
        "delivery_ack", {"delivery_id": held["delivery_id"], "lease_token": held["lease_token"]}
    )
    (reclaimed,) = agent.claim()["deliveries"]
    acked = agent.ack(reclaimed["delivery_id"], reclaimed["lease_token"])

    assert late["code"] == "DELIVERY_LEASE_EXPIRED"
    assert reclaimed["delivery_id"] == held["delivery_id"]
    assert acked["state"] == "ACKNOWLEDGED"
    assert acked["duplicate"] is False


def test_a_stale_holder_cannot_act_once_another_instance_has_reclaimed_the_delivery(
    aged: AgedLeases,
) -> None:
    stale = aged.deliveries["stale_holder"]
    destination = aged.destinations["stale_holder"]
    other_instance = aged.world.agent(destination)
    (current,) = other_instance.claim()["deliveries"]
    stale_instance = aged.world.agent(destination)
    stale_arguments = {"delivery_id": stale["delivery_id"], "lease_token": stale["lease_token"]}

    for tool, arguments in (
        ("delivery_renew_lease", stale_arguments),
        ("delivery_ack", stale_arguments),
        ("delivery_nack", stale_arguments | {"reason": "过期持有人", "retryable": False}),
    ):
        error = stale_instance.call_error(tool, arguments)
        assert error["code"] == "DELIVERY_LEASE_EXPIRED", tool

    # The instance that holds the current lease is unaffected and can still acknowledge.
    assert other_instance.ack(current["delivery_id"], current["lease_token"])["duplicate"] is False


def test_repeating_an_ack_after_the_original_lease_time_is_still_a_duplicate_not_an_error(
    aged: AgedLeases,
) -> None:
    held = aged.deliveries["acknowledged"]
    agent = aged.world.agent(aged.destinations["acknowledged"])
    assert aged.first_ack["duplicate"] is False

    repeated = agent.ack(held["delivery_id"], held["lease_token"])

    assert repeated["duplicate"] is True
    assert repeated["acknowledged_at"] == aged.first_ack["acknowledged_at"]
