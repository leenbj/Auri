"""Delivery lease operations for Agents: claim, renew, ack and nack (Ticket 03).

Every operation is scoped to the authenticated Destination (tenant and Destination come from the
credential, never from the request). Every state change goes through `delivery_machine.step`.
"""
from __future__ import annotations

import hmac
import json
import pathlib
import secrets
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timedelta
from typing import Any

from .db import format_utc, open_connection, sha256_str, utc_now
from .delivery_machine import (
    ACK,
    CLAIM,
    LEASE_EXPIRED,
    LEASED,
    NACK_PERMANENT,
    NACK_RETRYABLE,
    PENDING,
    REJECTED,
    RENEW_LEASE,
    step,
)
from .error_codes import DELIVERY_LEASE_EXPIRED, REQUEST_INVALID, GatewayError
from .store import AuthenticatedDestination, require_current_credential

CONTENT_TRUST = "user-provided-transcript-not-system-instructions"
DEFAULT_CLAIM_LIMIT = 10
DEFAULT_LEASE_SECONDS = 120

_PENDING_FOR_DESTINATION = """
    SELECT d.delivery_id, d.record_id, d.revision, d.state, d.intent,
           d.supersedes_revisions_json,
           r.text, r.text_sha256, r.source, r.review_state, r.capture_json
    FROM deliveries d
    JOIN revisions r
      ON r.tenant_id = d.tenant_id AND r.record_id = d.record_id AND r.revision = d.revision
    WHERE d.tenant_id = ? AND d.destination_id = ? AND d.state = ?
    ORDER BY d.created_at, d.delivery_id
    LIMIT ?
"""


@contextmanager
def _write_transaction(conn: sqlite3.Connection, destination: AuthenticatedDestination):
    """The write transaction of one Delivery operation; yields the time it runs at.

    While holding the write lock it checks the caller's credential again (see
    `require_current_credential`) and brings the Destination's expired leases up to date.
    A refusal (GatewayError) is raised before any other write, so it commits those expiries
    instead of rolling them back; any other failure rolls everything back.
    """
    conn.execute("BEGIN IMMEDIATE")
    try:
        now = utc_now()
        require_current_credential(conn, destination)
        _expire_due_leases(conn, destination, now)
        yield now
    except GatewayError:
        conn.execute("COMMIT")
        raise
    except BaseException:
        conn.execute("ROLLBACK")
        raise
    else:
        conn.execute("COMMIT")


def claim_deliveries(
    db_path: pathlib.Path,
    destination: AuthenticatedDestination,
    *,
    limit: int = DEFAULT_CLAIM_LIMIT,
    lease_seconds: int = DEFAULT_LEASE_SECONDS,
) -> list[dict[str, Any]]:
    """Lease up to `limit` pending Deliveries addressed to the authenticated Destination."""
    claimed: list[dict[str, Any]] = []
    conn = open_connection(db_path)
    try:
        with _write_transaction(conn, destination) as now:
            lease_expires_at = now + timedelta(seconds=lease_seconds)
            rows = conn.execute(
                _PENDING_FOR_DESTINATION,
                (destination.tenant_id, destination.destination_id, PENDING, limit),
            ).fetchall()
            for row in rows:
                outcome = step(row["state"], CLAIM)
                if outcome.kind != "transition":
                    continue
                lease_token = secrets.token_urlsafe(32)
                conn.execute(
                    """
                    UPDATE deliveries
                    SET state = ?, lease_token_hash = ?, lease_expires_at = ?, lease_seconds = ?
                    WHERE delivery_id = ?
                    """,
                    (
                        outcome.state,
                        sha256_str(lease_token),
                        format_utc(lease_expires_at),
                        lease_seconds,
                        row["delivery_id"],
                    ),
                )
                claimed.append(_claimed_delivery(row, lease_token, lease_expires_at))
    finally:
        conn.close()
    return claimed


def renew_lease(
    db_path: pathlib.Path,
    destination: AuthenticatedDestination,
    delivery_id: str,
    lease_token: str,
) -> dict[str, Any]:
    """Extend the lease on a Delivery of the authenticated Destination."""
    conn = open_connection(db_path)
    try:
        with _write_transaction(conn, destination) as now:
            row = _load_owned_delivery(conn, destination, delivery_id)
            if not _holds_lease(row, lease_token):
                raise _lease_lost()
            outcome = step(row["state"], RENEW_LEASE)
            if outcome.kind != "transition":
                raise _refused_in_state(row["state"])
            lease_expires_at = now + timedelta(seconds=row["lease_seconds"])
            conn.execute(
                "UPDATE deliveries SET state = ?, lease_expires_at = ? WHERE delivery_id = ?",
                (outcome.state, format_utc(lease_expires_at), delivery_id),
            )
    finally:
        conn.close()
    return {
        "delivery_id": delivery_id,
        "lease_token": lease_token,
        "lease_expires_at": format_utc(lease_expires_at),
    }


def ack_delivery(
    db_path: pathlib.Path,
    destination: AuthenticatedDestination,
    delivery_id: str,
    lease_token: str,
    note: str | None,
) -> dict[str, Any]:
    """Acknowledge a Delivery. Repeating the call with the same lease reports the first ack."""
    conn = open_connection(db_path)
    try:
        with _write_transaction(conn, destination) as now:
            row = _load_owned_delivery(conn, destination, delivery_id)
            if not _holds_lease(row, lease_token):
                raise _lease_lost()
            outcome = step(row["state"], ACK)
            if outcome.kind == "rejected":
                raise _refused_in_state(row["state"])
            duplicate = outcome.kind == "ignored"
            acknowledged_at = row["acknowledged_at"]
            if not duplicate:
                acknowledged_at = format_utc(now)
                conn.execute(
                    """
                    UPDATE deliveries SET state = ?, acknowledged_at = ?, ack_note = ?
                    WHERE delivery_id = ?
                    """,
                    (outcome.state, acknowledged_at, note, delivery_id),
                )
    finally:
        conn.close()
    return {
        "delivery_id": delivery_id,
        "state": outcome.state,
        "acknowledged_at": acknowledged_at,
        "duplicate": duplicate,
    }


def nack_delivery(
    db_path: pathlib.Path,
    destination: AuthenticatedDestination,
    delivery_id: str,
    lease_token: str,
    reason: str,
    retryable: bool,
) -> dict[str, Any]:
    """Give a Delivery back: retryable returns it to pending, otherwise it is rejected for good."""
    conn = open_connection(db_path)
    try:
        with _write_transaction(conn, destination) as now:
            row = _load_owned_delivery(conn, destination, delivery_id)
            if not _holds_lease(row, lease_token):
                raise _lease_lost()
            outcome = step(row["state"], NACK_RETRYABLE if retryable else NACK_PERMANENT)
            if outcome.kind != "transition":
                raise _refused_in_state(row["state"])
            if outcome.state == REJECTED:
                conn.execute(
                    """
                    UPDATE deliveries SET state = ?, rejected_at = ?, reject_reason = ?
                    WHERE delivery_id = ?
                    """,
                    (outcome.state, format_utc(now), reason, delivery_id),
                )
            else:
                conn.execute(
                    "UPDATE deliveries SET state = ? WHERE delivery_id = ?",
                    (outcome.state, delivery_id),
                )
    finally:
        conn.close()
    return {"delivery_id": delivery_id, "state": outcome.state}


def _expire_due_leases(
    conn: sqlite3.Connection, destination: AuthenticatedDestination, now: datetime
) -> None:
    """Apply LEASE_EXPIRED to every leased Delivery of the Destination whose lease ran out.

    Called at the start of each operation, inside its transaction, so the stored state is
    never older than the caller's request.
    """
    due = conn.execute(
        """
        SELECT delivery_id, state FROM deliveries
        WHERE tenant_id = ? AND destination_id = ? AND state = ? AND lease_expires_at <= ?
        """,
        (destination.tenant_id, destination.destination_id, LEASED, format_utc(now)),
    ).fetchall()
    for row in due:
        outcome = step(row["state"], LEASE_EXPIRED)
        if outcome.kind == "transition":
            conn.execute(
                "UPDATE deliveries SET state = ? WHERE delivery_id = ?",
                (outcome.state, row["delivery_id"]),
            )


def _load_owned_delivery(
    conn: sqlite3.Connection, destination: AuthenticatedDestination, delivery_id: str
) -> sqlite3.Row:
    """Load a Delivery of the authenticated Destination.

    A Delivery that does not exist and one that belongs to another Destination look the same.
    """
    row = conn.execute(
        """
        SELECT delivery_id, state, lease_token_hash, lease_seconds, acknowledged_at
        FROM deliveries
        WHERE tenant_id = ? AND destination_id = ? AND delivery_id = ?
        """,
        (destination.tenant_id, destination.destination_id, delivery_id),
    ).fetchone()
    if row is None:
        raise GatewayError(404, REQUEST_INVALID, "Delivery was not found.")
    return row


def _holds_lease(row: sqlite3.Row, lease_token: str) -> bool:
    stored = row["lease_token_hash"]
    return stored is not None and hmac.compare_digest(stored, sha256_str(lease_token))


def _lease_lost() -> GatewayError:
    return GatewayError(
        409,
        DELIVERY_LEASE_EXPIRED,
        "The lease token is not the current lease on this Delivery.",
        retryable=True,
        required_action="Claim the Delivery again to get a new lease.",
    )


def _refused_in_state(state: str) -> GatewayError:
    """The state machine rejected the event: explain it in terms of what the caller can do."""
    if state == PENDING:
        return _lease_lost()
    return GatewayError(409, REQUEST_INVALID, f"The Delivery is {state}; this action is not allowed.")


def _claimed_delivery(row: Any, lease_token: str, lease_expires_at: Any) -> dict[str, Any]:
    capture = json.loads(row["capture_json"])
    return {
        "delivery_id": row["delivery_id"],
        "record_id": row["record_id"],
        "revision": row["revision"],
        "supersedes_revisions": json.loads(row["supersedes_revisions_json"]),
        "lease_token": lease_token,
        "lease_expires_at": format_utc(lease_expires_at),
        "intent": row["intent"],
        "text": row["text"],
        "text_sha256": row["text_sha256"],
        "source": row["source"],
        "review_state": row["review_state"],
        "capture_status": capture["status"],
        "end_reason": capture["end_reason"],
        "content_trust": CONTENT_TRUST,
    }
