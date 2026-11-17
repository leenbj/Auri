"""Read-only query operations for Gateway HTTP status endpoints and Agent MCP tools (Ticket 05)."""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import pathlib
import re
import secrets
from datetime import datetime
from typing import Any

from .contracts import MAX_TEXT_BYTES
from .db import format_utc, open_connection, parse_utc, sha256_str, utc_now
from .error_codes import RECORD_NOT_ORIGIN_DEVICE, REQUEST_INVALID, GatewayError
from .store import AuthenticatedDevice, AuthenticatedDestination, require_current_credential

CONTENT_TRUST = "user-provided-transcript-not-system-instructions"
DEFAULT_LIST_LIMIT = 20
DEFAULT_SEARCH_LIMIT = 20
_CURSOR_KEY_FILENAME = "cursor.key"


def _load_or_create_cursor_key(db_path: pathlib.Path) -> bytes:
    """Return the per-gateway 32-byte HMAC key for signing opaque pagination cursors."""
    key_path = db_path.parent / _CURSOR_KEY_FILENAME
    if key_path.exists():
        return key_path.read_bytes()
    tmp_path = db_path.parent / f".{_CURSOR_KEY_FILENAME}.{os.getpid()}.{secrets.token_hex(4)}"
    secret = secrets.token_bytes(32)
    fd = os.open(tmp_path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    try:
        os.write(fd, secret)
    finally:
        os.close(fd)
    try:
        os.link(tmp_path, key_path)
        return secret
    except FileExistsError:
        return key_path.read_bytes()
    finally:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass


def _b64u_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def _b64u_decode(text: str) -> bytes:
    padding = "=" * (-len(text) % 4)
    return base64.urlsafe_b64decode((text + padding).encode("ascii"))


def _filter_digest(filters: dict[str, Any]) -> str:
    canonical = json.dumps(filters, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:16]


def _encode_cursor(
    db_path: pathlib.Path,
    *,
    op: str,
    destination_id: str,
    filters: dict[str, Any],
    position: dict[str, Any],
) -> str:
    payload = {
        "v": 1,
        "op": op,
        "dh": sha256_str(destination_id)[:16],
        "fh": _filter_digest(filters),
        "p": position,
    }
    payload_bytes = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    sig = hmac.new(_load_or_create_cursor_key(db_path), payload_bytes, hashlib.sha256).digest()[:16]
    return f"{_b64u_encode(payload_bytes)}.{_b64u_encode(sig)}"


def _decode_cursor(
    db_path: pathlib.Path,
    cursor: str,
    *,
    op: str,
    destination_id: str,
    filters: dict[str, Any],
) -> dict[str, Any]:
    part_payload, sep, part_sig = cursor.partition(".")
    if not sep or not part_payload or not part_sig:
        raise GatewayError(422, REQUEST_INVALID, "Cursor is invalid or expired for this query.")
    try:
        payload_bytes = _b64u_decode(part_payload)
        sig_bytes = _b64u_decode(part_sig)
    except (ValueError, UnicodeEncodeError) as exc:
        raise GatewayError(422, REQUEST_INVALID, "Cursor is invalid or expired for this query.") from exc

    expected_sig = hmac.new(
        _load_or_create_cursor_key(db_path), payload_bytes, hashlib.sha256
    ).digest()[:16]
    if not hmac.compare_digest(sig_bytes, expected_sig):
        raise GatewayError(422, REQUEST_INVALID, "Cursor is invalid or expired for this query.")

    try:
        payload = json.loads(payload_bytes.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise GatewayError(422, REQUEST_INVALID, "Cursor is invalid or expired for this query.") from exc

    if (
        not isinstance(payload, dict)
        or payload.get("v") != 1
        or payload.get("op") != op
        or payload.get("dh") != sha256_str(destination_id)[:16]
        or payload.get("fh") != _filter_digest(filters)
        or not isinstance(payload.get("p"), dict)
    ):
        raise GatewayError(422, REQUEST_INVALID, "Cursor is invalid or expired for this query.")
    return payload["p"]


def _normalize_timestamp_filter(value: str | None) -> str | None:
    if value is None:
        return None
    try:
        return format_utc(parse_utc(value))
    except ValueError as exc:
        raise GatewayError(422, REQUEST_INVALID, "Timestamp filter is not a valid ISO 8601 datetime.") from exc


def _effective_delivery_state(
    state: str, lease_expires_at: str | None, now: datetime
) -> tuple[str, str | None]:
    """Resolve expired LEASED deliveries to PENDING without requiring a write lock."""
    if state == "LEASED" and lease_expires_at is not None and parse_utc(lease_expires_at) <= now:
        return "PENDING", None
    return state, lease_expires_at if state == "LEASED" else None


def get_operation(
    db_path: pathlib.Path, auth: AuthenticatedDevice, operation_id: str
) -> dict[str, Any]:
    """Look up the committed outcome of a revision_store operation for its origin device."""
    now = utc_now()
    conn = open_connection(db_path)
    try:
        rev_row = conn.execute(
            """
            SELECT operation_id, record_id, revision, origin_device_id,
                   text_sha256, stored_at, superseded_revisions_json, deliveries_json
            FROM revisions
            WHERE tenant_id = ? AND operation_id = ?
            """,
            (auth.tenant_id, operation_id),
        ).fetchone()
        if rev_row is None:
            raise GatewayError(404, REQUEST_INVALID, "Operation was not found.")
        if rev_row["origin_device_id"] != auth.device_id:
            raise GatewayError(
                403,
                RECORD_NOT_ORIGIN_DEVICE,
                "Only the device that created this operation may query it.",
            )

        live_rows = conn.execute(
            """
            SELECT delivery_id, destination_id, revision, state, lease_expires_at
            FROM deliveries
            WHERE tenant_id = ? AND record_id = ? AND revision = ?
            ORDER BY created_at ASC, delivery_id ASC
            """,
            (auth.tenant_id, rev_row["record_id"], rev_row["revision"]),
        ).fetchall()
    finally:
        conn.close()

    live_by_id = {row["delivery_id"]: row for row in live_rows}
    ordered_snapshot = json.loads(rev_row["deliveries_json"])
    deliveries: list[dict[str, Any]] = []
    for item in ordered_snapshot:
        live = live_by_id.get(item["delivery_id"])
        if live is not None:
            eff_state, _ = _effective_delivery_state(
                live["state"], live["lease_expires_at"], now
            )
            deliveries.append(
                {
                    "delivery_id": live["delivery_id"],
                    "destination_id": live["destination_id"],
                    "revision": int(live["revision"]),
                    "state": eff_state,
                }
            )
        else:
            deliveries.append(item)

    return {
        "schema_version": "1.1",
        "operation_id": rev_row["operation_id"],
        "operation_type": "revision_store",
        "status": "succeeded",
        "record_id": rev_row["record_id"],
        "revision": int(rev_row["revision"]),
        "text_sha256": rev_row["text_sha256"],
        "stored_at": rev_row["stored_at"],
        "superseded_revisions": json.loads(rev_row["superseded_revisions_json"]),
        "deliveries": deliveries,
    }


def get_record_status(
    db_path: pathlib.Path, auth: AuthenticatedDevice, record_id: str
) -> dict[str, Any]:
    """Return per-revision and per-destination delivery status for a Record in auth's tenant."""
    now = utc_now()
    conn = open_connection(db_path)
    try:
        rec = conn.execute(
            """
            SELECT record_id, origin_device_id, deleted_at
            FROM records
            WHERE tenant_id = ? AND record_id = ?
            """,
            (auth.tenant_id, record_id),
        ).fetchone()
        if rec is None:
            raise GatewayError(404, REQUEST_INVALID, "Record was not found.")

        rev_rows = conn.execute(
            """
            SELECT revision, state, text_sha256, stored_at, intent
            FROM revisions
            WHERE tenant_id = ? AND record_id = ?
            ORDER BY revision ASC
            """,
            (auth.tenant_id, record_id),
        ).fetchall()

        dlv_rows = conn.execute(
            """
            SELECT d.destination_id, dst.display_name AS destination_display_name,
                   d.delivery_id, d.revision, d.state, r.stored_at,
                   d.lease_expires_at, d.acknowledged_at, d.rejected_at, d.cancelled_at
            FROM deliveries d
            JOIN destinations dst
              ON dst.tenant_id = d.tenant_id AND dst.destination_id = d.destination_id
            JOIN revisions r
              ON r.tenant_id = d.tenant_id AND r.record_id = d.record_id AND r.revision = d.revision
            WHERE d.tenant_id = ? AND d.record_id = ?
            ORDER BY d.revision DESC, d.created_at ASC, d.delivery_id ASC
            """,
            (auth.tenant_id, record_id),
        ).fetchall()
    finally:
        conn.close()

    revisions = [
        {
            "revision": int(row["revision"]),
            "state": row["state"],
            "text_sha256": row["text_sha256"],
            "stored_at": row["stored_at"],
            "intent": row["intent"],
        }
        for row in rev_rows
    ]
    latest_revision = revisions[-1]["revision"] if revisions else None

    destinations: list[dict[str, Any]] = []
    for row in dlv_rows:
        eff_state, eff_lease_expires_at = _effective_delivery_state(
            row["state"], row["lease_expires_at"], now
        )
        destinations.append(
            {
                "destination_id": row["destination_id"],
                "destination_display_name": row["destination_display_name"],
                "delivery_id": row["delivery_id"],
                "revision": int(row["revision"]),
                "delivery_state": eff_state,
                "stored_at": row["stored_at"],
                "lease_expires_at": eff_lease_expires_at,
                "acknowledged_at": row["acknowledged_at"],
                "rejected_at": row["rejected_at"],
                "cancelled_at": row["cancelled_at"],
            }
        )

    return {
        "schema_version": "1.1",
        "record_id": rec["record_id"],
        "origin_device_id": rec["origin_device_id"],
        "deleted": rec["deleted_at"] is not None,
        "deleted_at": rec["deleted_at"],
        "latest_revision": latest_revision,
        "revisions": revisions,
        "destinations": destinations,
    }


def _query_visible_rows(
    db_path: pathlib.Path,
    destination: AuthenticatedDestination,
    *,
    since_iso: str | None,
    until_iso: str | None,
    delivery_state: str | None,
    intent: str | None,
    after_pos: dict[str, Any] | None,
    limit: int | None,
) -> list[Any]:
    """Fetch latest delivered Revision per Record for Destination in keyset order."""
    now_iso = format_utc(utc_now())
    where_clauses = ["rn = 1"]
    params: list[Any] = [now_iso, destination.tenant_id, destination.destination_id]
    if since_iso is not None:
        where_clauses.append("stored_at >= ?")
        params.append(since_iso)
    if until_iso is not None:
        where_clauses.append("stored_at <= ?")
        params.append(until_iso)
    if delivery_state is not None:
        where_clauses.append("eff_state = ?")
        params.append(delivery_state)
    if intent is not None:
        where_clauses.append("intent = ?")
        params.append(intent)
    if after_pos is not None:
        after_ts = after_pos.get("t")
        after_rec = after_pos.get("r")
        if not isinstance(after_ts, str) or not isinstance(after_rec, str):
            raise GatewayError(422, REQUEST_INVALID, "Cursor is invalid or expired for this query.")
        where_clauses.append("(stored_at < ? OR (stored_at = ? AND record_id > ?))")
        params.extend([after_ts, after_ts, after_rec])

    limit_sql = ""
    if limit is not None:
        limit_sql = "LIMIT ?"
        params.append(limit)

    conn = open_connection(db_path)
    try:
        require_current_credential(conn, destination)
        return conn.execute(
            f"""
            WITH visible AS (
                SELECT d.record_id, d.revision, d.delivery_id,
                       CASE
                           WHEN d.state = 'LEASED' AND d.lease_expires_at <= ? THEN 'PENDING'
                           ELSE d.state
                       END AS eff_state,
                       d.intent, r.source, r.language, r.review_state, r.capture_json,
                       r.stored_at, r.text, r.text_sha256,
                       ROW_NUMBER() OVER (PARTITION BY d.record_id ORDER BY r.revision DESC) AS rn
                FROM deliveries d
                JOIN revisions r
                  ON r.tenant_id = d.tenant_id AND r.record_id = d.record_id AND r.revision = d.revision
                JOIN records rec
                  ON rec.tenant_id = d.tenant_id AND rec.record_id = d.record_id
                WHERE d.tenant_id = ?
                  AND d.destination_id = ?
                  AND d.state != 'CANCELLED'
                  AND rec.deleted_at IS NULL
            )
            SELECT record_id, revision, delivery_id, eff_state, intent, source, language,
                   review_state, capture_json, stored_at, text, text_sha256
            FROM visible
            WHERE {' AND '.join(where_clauses)}
            ORDER BY stored_at DESC, record_id ASC
            {limit_sql}
            """,
            params,
        ).fetchall()
    finally:
        conn.close()


def list_records(
    db_path: pathlib.Path,
    destination: AuthenticatedDestination,
    *,
    cursor: str | None = None,
    limit: int = DEFAULT_LIST_LIMIT,
    since: str | None = None,
    until: str | None = None,
    delivery_state: str | None = None,
    intent: str | None = None,
) -> dict[str, Any]:
    """List Records addressed to the authenticated Destination with opaque cursor pagination."""
    since_iso = _normalize_timestamp_filter(since)
    until_iso = _normalize_timestamp_filter(until)
    filters = {
        "since": since_iso,
        "until": until_iso,
        "delivery_state": delivery_state,
        "intent": intent,
    }
    after_pos = (
        _decode_cursor(
            db_path,
            cursor,
            op="record_list",
            destination_id=destination.destination_id,
            filters=filters,
        )
        if cursor is not None
        else None
    )

    rows = _query_visible_rows(
        db_path,
        destination,
        since_iso=since_iso,
        until_iso=until_iso,
        delivery_state=delivery_state,
        intent=intent,
        after_pos=after_pos,
        limit=limit + 1,
    )

    has_more = len(rows) > limit
    page_rows = rows[:limit]
    records: list[dict[str, Any]] = []
    for row in page_rows:
        capture = json.loads(row["capture_json"])
        records.append(
            {
                "record_id": row["record_id"],
                "revision": int(row["revision"]),
                "delivery_id": row["delivery_id"],
                "delivery_state": row["eff_state"],
                "intent": row["intent"],
                "source": row["source"],
                "language": row["language"],
                "review_state": row["review_state"],
                "capture_status": capture["status"],
                "end_reason": capture["end_reason"],
                "stored_at": row["stored_at"],
                "text_sha256": row["text_sha256"],
                "text_bytes": len(row["text"].encode("utf-8")),
            }
        )

    next_cursor = None
    if has_more and page_rows:
        last = page_rows[-1]
        next_cursor = _encode_cursor(
            db_path,
            op="record_list",
            destination_id=destination.destination_id,
            filters=filters,
            position={"t": last["stored_at"], "r": last["record_id"]},
        )

    return {
        "schema_version": "1.1",
        "records": records,
        "next_cursor": next_cursor,
    }


def get_record(
    db_path: pathlib.Path,
    destination: AuthenticatedDestination,
    *,
    record_id: str,
    revision: int | None = None,
    cursor: str | None = None,
    max_bytes: int | None = None,
) -> dict[str, Any]:
    """Read an authorized Record revision for the authenticated Destination, paging UTF-8 safely."""
    now_iso = format_utc(utc_now())
    conn = open_connection(db_path)
    try:
        require_current_credential(conn, destination)
        rev_filter = "AND d.revision = ?" if revision is not None else ""
        params: list[Any] = [now_iso, destination.tenant_id, destination.destination_id, record_id]
        if revision is not None:
            params.append(revision)

        row = conn.execute(
            f"""
            SELECT d.delivery_id, d.record_id, d.revision,
                   CASE
                       WHEN d.state = 'LEASED' AND d.lease_expires_at <= ? THEN 'PENDING'
                       ELSE d.state
                   END AS eff_state,
                   d.intent, d.supersedes_revisions_json,
                   r.text, r.text_sha256, r.source, r.language, r.review_state,
                   r.capture_json, r.stored_at
            FROM deliveries d
            JOIN revisions r
              ON r.tenant_id = d.tenant_id AND r.record_id = d.record_id AND r.revision = d.revision
            JOIN records rec
              ON rec.tenant_id = d.tenant_id AND rec.record_id = d.record_id
            WHERE d.tenant_id = ?
              AND d.destination_id = ?
              AND d.record_id = ?
              AND d.state != 'CANCELLED'
              AND rec.deleted_at IS NULL
              {rev_filter}
            ORDER BY d.revision DESC
            LIMIT 1
            """,
            params,
        ).fetchone()
    finally:
        conn.close()

    if row is None:
        raise GatewayError(404, REQUEST_INVALID, "Record was not found.")

    raw_bytes = row["text"].encode("utf-8")
    total_bytes = len(raw_bytes)
    if total_bytes > MAX_TEXT_BYTES:
        raise GatewayError(
            422, REQUEST_INVALID, "Revision text exceeds maximum supported byte size."
        )

    cursor_filters = {
        "record_id": row["record_id"],
        "revision": int(row["revision"]),
        "sha": row["text_sha256"],
    }
    offset = 0
    if cursor is not None:
        pos = _decode_cursor(
            db_path,
            cursor,
            op="record_get",
            destination_id=destination.destination_id,
            filters=cursor_filters,
        )
        raw_offset = pos.get("o")
        if not isinstance(raw_offset, int) or raw_offset <= 0 or raw_offset >= total_bytes:
            raise GatewayError(422, REQUEST_INVALID, "Cursor is invalid or expired for this query.")
        offset = raw_offset

    chunk_limit = max_bytes if max_bytes is not None else MAX_TEXT_BYTES
    end = min(offset + chunk_limit, total_bytes)
    if end < total_bytes:
        while end > offset and (raw_bytes[end] & 0xC0) == 0x80:
            end -= 1
        if end <= offset:
            raise GatewayError(
                422, REQUEST_INVALID, "max_bytes is too small to fit a complete UTF-8 character."
            )

    chunk_text = raw_bytes[offset:end].decode("utf-8")
    is_partial = offset > 0 or end < total_bytes
    next_cursor = (
        _encode_cursor(
            db_path,
            op="record_get",
            destination_id=destination.destination_id,
            filters=cursor_filters,
            position={"o": end},
        )
        if end < total_bytes
        else None
    )

    capture = json.loads(row["capture_json"])
    return {
        "schema_version": "1.1",
        "record_id": row["record_id"],
        "revision": int(row["revision"]),
        "supersedes_revisions": json.loads(row["supersedes_revisions_json"]),
        "delivery_id": row["delivery_id"],
        "delivery_state": row["eff_state"],
        "intent": row["intent"],
        "text": chunk_text,
        "text_sha256": row["text_sha256"],
        "text_bytes": total_bytes,
        "is_partial": is_partial,
        "next_cursor": next_cursor,
        "source": row["source"],
        "language": row["language"],
        "review_state": row["review_state"],
        "capture_status": capture["status"],
        "end_reason": capture["end_reason"],
        "stored_at": row["stored_at"],
        "content_trust": CONTENT_TRUST,
    }


def _extract_snippet(text: str, pattern: re.Pattern[str], max_chars: int = 200) -> tuple[str, bool] | None:
    match = pattern.search(text)
    if match is None:
        return None
    if len(text) <= max_chars:
        return text, False
    match_len = match.end() - match.start()
    if match_len >= max_chars:
        return text[match.start() : match.start() + max_chars], True
    pad = (max_chars - match_len) // 2
    start = max(0, match.start() - pad)
    end = start + max_chars
    if end > len(text):
        end = len(text)
        start = max(0, end - max_chars)
    return text[start:end], True


def search_records(
    db_path: pathlib.Path,
    destination: AuthenticatedDestination,
    *,
    query: str,
    cursor: str | None = None,
    limit: int = DEFAULT_SEARCH_LIMIT,
    since: str | None = None,
    until: str | None = None,
    delivery_state: str | None = None,
    intent: str | None = None,
) -> dict[str, Any]:
    """Search authorized Records for the authenticated Destination with opaque cursor pagination."""
    since_iso = _normalize_timestamp_filter(since)
    until_iso = _normalize_timestamp_filter(until)
    filters = {
        "query": query,
        "since": since_iso,
        "until": until_iso,
        "delivery_state": delivery_state,
        "intent": intent,
    }
    after_pos = (
        _decode_cursor(
            db_path,
            cursor,
            op="record_search",
            destination_id=destination.destination_id,
            filters=filters,
        )
        if cursor is not None
        else None
    )

    candidate_rows = _query_visible_rows(
        db_path,
        destination,
        since_iso=since_iso,
        until_iso=until_iso,
        delivery_state=delivery_state,
        intent=intent,
        after_pos=after_pos,
        limit=None,
    )

    pattern = re.compile(re.escape(query), re.IGNORECASE)
    matched_rows: list[tuple[Any, str, bool]] = []
    for row in candidate_rows:
        extracted = _extract_snippet(row["text"], pattern)
        if extracted is None:
            continue
        snippet, truncated = extracted
        matched_rows.append((row, snippet, truncated))
        if len(matched_rows) > limit:
            break

    has_more = len(matched_rows) > limit
    page_items = matched_rows[:limit]
    matches = [
        {
            "record_id": row["record_id"],
            "revision": int(row["revision"]),
            "delivery_id": row["delivery_id"],
            "delivery_state": row["eff_state"],
            "intent": row["intent"],
            "snippet": snippet,
            "snippet_truncated": truncated,
            "text_bytes": len(row["text"].encode("utf-8")),
            "text_sha256": row["text_sha256"],
            "stored_at": row["stored_at"],
            "content_trust": CONTENT_TRUST,
        }
        for row, snippet, truncated in page_items
    ]

    next_cursor = None
    if has_more and page_items:
        last_row = page_items[-1][0]
        next_cursor = _encode_cursor(
            db_path,
            op="record_search",
            destination_id=destination.destination_id,
            filters=filters,
            position={"t": last_row["stored_at"], "r": last_row["record_id"]},
        )

    return {
        "schema_version": "1.1",
        "matches": matches,
        "next_cursor": next_cursor,
    }
