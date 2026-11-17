"""Delivery state machine (PRD 12.4, contracts/fixtures/state-machines/delivery.machine.json).

Every change to a Delivery's state goes through `step`. A (state, event) pair that the
contract does not list is rejected and leaves the state unchanged.
"""
from __future__ import annotations

from typing import Literal, NamedTuple

PENDING = "PENDING"
LEASED = "LEASED"
ACKNOWLEDGED = "ACKNOWLEDGED"
REJECTED = "REJECTED"
CANCELLED = "CANCELLED"

CLAIM = "CLAIM"
SUPERSEDED = "SUPERSEDED"
RECORD_DELETED = "RECORD_DELETED"
DESTINATION_REVOKED = "DESTINATION_REVOKED"
RENEW_LEASE = "RENEW_LEASE"
ACK = "ACK"
NACK_RETRYABLE = "NACK_RETRYABLE"
NACK_PERMANENT = "NACK_PERMANENT"
LEASE_EXPIRED = "LEASE_EXPIRED"

STATES = frozenset({PENDING, LEASED, ACKNOWLEDGED, REJECTED, CANCELLED})
EVENTS = frozenset(
    {
        CLAIM,
        SUPERSEDED,
        RECORD_DELETED,
        DESTINATION_REVOKED,
        RENEW_LEASE,
        ACK,
        NACK_RETRYABLE,
        NACK_PERMANENT,
        LEASE_EXPIRED,
    }
)

Kind = Literal["transition", "ignored", "rejected"]


class Outcome(NamedTuple):
    kind: Kind
    state: str


_TABLE: dict[tuple[str, str], Outcome] = {
    (PENDING, CLAIM): Outcome("transition", LEASED),
    (PENDING, SUPERSEDED): Outcome("transition", CANCELLED),
    (PENDING, RECORD_DELETED): Outcome("transition", CANCELLED),
    (PENDING, DESTINATION_REVOKED): Outcome("transition", CANCELLED),
    (LEASED, RENEW_LEASE): Outcome("transition", LEASED),
    (LEASED, ACK): Outcome("transition", ACKNOWLEDGED),
    (LEASED, NACK_RETRYABLE): Outcome("transition", PENDING),
    (LEASED, NACK_PERMANENT): Outcome("transition", REJECTED),
    (LEASED, LEASE_EXPIRED): Outcome("transition", PENDING),
    (LEASED, SUPERSEDED): Outcome("ignored", LEASED),
    (LEASED, RECORD_DELETED): Outcome("transition", CANCELLED),
    (LEASED, DESTINATION_REVOKED): Outcome("transition", CANCELLED),
    (ACKNOWLEDGED, ACK): Outcome("ignored", ACKNOWLEDGED),
    (ACKNOWLEDGED, SUPERSEDED): Outcome("ignored", ACKNOWLEDGED),
    (ACKNOWLEDGED, RECORD_DELETED): Outcome("ignored", ACKNOWLEDGED),
    (REJECTED, SUPERSEDED): Outcome("ignored", REJECTED),
    (REJECTED, RECORD_DELETED): Outcome("ignored", REJECTED),
    (CANCELLED, SUPERSEDED): Outcome("ignored", CANCELLED),
    (CANCELLED, RECORD_DELETED): Outcome("ignored", CANCELLED),
}


def step(state: str, event: str) -> Outcome:
    """Apply one event to a state; unlisted pairs are rejected with the state unchanged."""
    if state not in STATES:
        raise ValueError(f"unknown delivery state: {state!r}")
    if event not in EVENTS:
        raise ValueError(f"unknown delivery event: {event!r}")
    return _TABLE.get((state, event), Outcome("rejected", state))
