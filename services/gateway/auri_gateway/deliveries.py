"""Delivery lease operations for Agents: claim, renew, ack and nack (Ticket 03).

Every operation is scoped to the authenticated Destination (tenant and Destination come from the
credential, never from the request). Every state change goes through `delivery_machine.step`.
"""
from __future__ import annotations

import json
import pathlib
import secrets
from datetime import timedelta
from typing import Any

from .db import format_utc, open_connection, sha256_str, utc_now
from .delivery_machine import CLAIM, PENDING, step
from .store import AuthenticatedDestination

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
        conn.execute("BEGIN IMMEDIATE")
        try:
            now = utc_now()
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
                    SET state = ?, lease_token_hash = ?, lease_expires_at = ?
                    WHERE delivery_id = ?
                    """,
                    (
                        outcome.state,
                        sha256_str(lease_token),
                        format_utc(lease_expires_at),
                        row["delivery_id"],
                    ),
                )
                claimed.append(_claimed_delivery(row, lease_token, lease_expires_at))
            conn.execute("COMMIT")
        except Exception:
            conn.execute("ROLLBACK")
            raise
    finally:
        conn.close()
    return claimed


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
