"""Persistent gateway operations for pairing, authentication, and revision storage."""
from __future__ import annotations

import json
import pathlib
import secrets
import uuid
from dataclasses import dataclass
from datetime import timedelta
from typing import Any

from .db import ensure_gateway_initialized, format_utc, open_connection, parse_utc, sha256_str, utc_now
from .delivery_machine import DESTINATION_REVOKED, step
from .error_codes import (
    GATEWAY_AUTH_INVALID,
    GATEWAY_AUTH_REVOKED,
    PAIRING_CODE_INVALID,
    RECORD_DELETED,
    RECORD_NOT_ORIGIN_DEVICE,
    RECORD_VERSION_CONFLICT,
    REQUEST_INVALID,
    GatewayError,
)


@dataclass(frozen=True)
class AuthenticatedDevice:
    device_id: str
    tenant_id: str


@dataclass(frozen=True)
class AuthenticatedDestination:
    destination_id: str
    tenant_id: str
    allowed_intents: tuple[str, ...]
    credential_hash: str  # the credential presented; write operations check it is still current


def issue_pairing_code(data_dir: pathlib.Path, *, ttl_seconds: int = 300) -> dict[str, Any]:
    """Generate a one-time short-lived pairing code and persist only its SHA-256 hash."""
    if ttl_seconds < 1:
        raise ValueError("ttl_seconds must be at least 1")
    db_path = ensure_gateway_initialized(data_dir)
    pairing_code = secrets.token_urlsafe(24)
    code_hash = sha256_str(pairing_code)
    pairing_code_id = str(uuid.uuid4())
    now = utc_now()
    created_at = format_utc(now)
    expires_at = format_utc(now + timedelta(seconds=ttl_seconds))

    conn = open_connection(db_path)
    try:
        conn.execute("BEGIN IMMEDIATE")
        try:
            meta = conn.execute(
                "SELECT gateway_id, default_tenant_id FROM gateway_meta WHERE id = 1"
            ).fetchone()
            assert meta is not None
            conn.execute(
                """
                INSERT INTO pairing_codes (
                    pairing_code_id, code_hash, tenant_id, created_at, expires_at
                ) VALUES (?, ?, ?, ?, ?)
                """,
                (pairing_code_id, code_hash, meta["default_tenant_id"], created_at, expires_at),
            )
            conn.execute("COMMIT")
            return {
                "gateway_id": meta["gateway_id"],
                "pairing_code": pairing_code,
                "expires_at": expires_at,
            }
        except Exception:
            conn.execute("ROLLBACK")
            raise
    finally:
        conn.close()


def redeem_pairing_code(db_path: pathlib.Path, body: dict[str, Any]) -> dict[str, Any]:
    """Exchange a valid unused pairing code for a one-time device_token (hashed at rest)."""
    code_hash = sha256_str(body["pairing_code"])
    device_info = body["device"]
    now = utc_now()
    now_iso = format_utc(now)

    conn = open_connection(db_path)
    try:
        conn.execute("BEGIN IMMEDIATE")
        try:
            meta = conn.execute("SELECT gateway_id FROM gateway_meta WHERE id = 1").fetchone()
            assert meta is not None

            row = conn.execute(
                """
                SELECT pairing_code_id, tenant_id, expires_at, redeemed_at
                FROM pairing_codes
                WHERE code_hash = ?
                """,
                (code_hash,),
            ).fetchone()
            if row is None or row["redeemed_at"] is not None or parse_utc(row["expires_at"]) <= now:
                # Single uniform code and message so responses do not reveal whether a code is unknown, used, or expired.
                raise GatewayError(
                    401,
                    PAIRING_CODE_INVALID,
                    "Pairing code is invalid, expired, or already used.",
                    required_action="Generate a new pairing code on the gateway CLI.",
                )

            device_id = str(uuid.uuid4())
            device_token = f"auri_dev_{secrets.token_urlsafe(32)}"
            token_hash = sha256_str(device_token)

            cursor = conn.execute(
                """
                UPDATE pairing_codes
                SET redeemed_at = ?, redeemed_device_id = ?
                WHERE pairing_code_id = ? AND redeemed_at IS NULL
                """,
                (now_iso, device_id, row["pairing_code_id"]),
            )
            if cursor.rowcount != 1:
                raise GatewayError(
                    401,
                    PAIRING_CODE_INVALID,
                    "Pairing code is invalid, expired, or already used.",
                    required_action="Generate a new pairing code on the gateway CLI.",
                )

            conn.execute(
                """
                INSERT INTO devices (
                    device_id, tenant_id, token_hash, platform, app_version, os_build, display_name, paired_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    device_id,
                    row["tenant_id"],
                    token_hash,
                    device_info["platform"],
                    device_info["app_version"],
                    device_info["os_build"],
                    device_info["display_name"],
                    now_iso,
                ),
            )
            conn.execute("COMMIT")
            return {
                "schema_version": "1.1",
                "gateway_id": meta["gateway_id"],
                "device_id": device_id,
                "device_token": device_token,
                "paired_at": now_iso,
            }
        except Exception:
            conn.execute("ROLLBACK")
            raise
    finally:
        conn.close()


def _extract_bearer_token_hash(
    auth_header: str | None,
    *,
    missing_action: str,
    format_action: str,
) -> str:
    if not auth_header:
        raise GatewayError(
            401,
            GATEWAY_AUTH_INVALID,
            "Missing Authorization header.",
            required_action=missing_action,
        )
    scheme, _, token = auth_header.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        raise GatewayError(
            401,
            GATEWAY_AUTH_INVALID,
            "Invalid Authorization header format.",
            required_action=format_action,
        )
    return sha256_str(token.strip())


def authenticate_device(db_path: pathlib.Path, auth_header: str | None) -> AuthenticatedDevice:
    """Resolve (device_id, tenant_id) strictly from the Bearer token hash in SQLite."""
    token_hash = _extract_bearer_token_hash(
        auth_header,
        missing_action="Pair the device and provide a Bearer device token.",
        format_action="Provide an Authorization: Bearer <device_token> header.",
    )
    conn = open_connection(db_path)
    try:
        row = conn.execute(
            "SELECT device_id, tenant_id, revoked_at FROM devices WHERE token_hash = ?",
            (token_hash,),
        ).fetchone()
    finally:
        conn.close()

    if row is None:
        raise GatewayError(
            401,
            GATEWAY_AUTH_INVALID,
            "Device token is invalid.",
            required_action="Re-pair the device with a new pairing code.",
        )
    if row["revoked_at"] is not None:
        raise GatewayError(
            401,
            GATEWAY_AUTH_REVOKED,
            "Device token has been revoked.",
            required_action="Re-pair the device on the gateway.",
        )
    return AuthenticatedDevice(device_id=row["device_id"], tenant_id=row["tenant_id"])


def authenticate_destination(
    db_path: pathlib.Path, auth_header: str | None
) -> AuthenticatedDestination:
    """Resolve (destination_id, tenant_id) strictly from a Destination Bearer credential hash."""
    token_hash = _extract_bearer_token_hash(
        auth_header,
        missing_action="Provide a Bearer destination credential.",
        format_action="Provide an Authorization: Bearer <destination_credential> header.",
    )
    conn = open_connection(db_path)
    try:
        row = conn.execute(
            """
            SELECT c.destination_id,
                   c.tenant_id,
                   c.revoked_at AS cred_revoked_at,
                   d.revoked_at AS dest_revoked_at,
                   d.allowed_intents_json
            FROM destination_credentials AS c
            JOIN destinations AS d
              ON d.tenant_id = c.tenant_id AND d.destination_id = c.destination_id
            WHERE c.token_hash = ?
            """,
            (token_hash,),
        ).fetchone()
    finally:
        conn.close()

    if row is None:
        raise GatewayError(
            401,
            GATEWAY_AUTH_INVALID,
            "Destination credential is invalid.",
            required_action="Issue a new credential for this destination.",
        )
    if row["cred_revoked_at"] is not None or row["dest_revoked_at"] is not None:
        raise _credential_revoked()
    return AuthenticatedDestination(
        destination_id=row["destination_id"],
        tenant_id=row["tenant_id"],
        allowed_intents=tuple(json.loads(row["allowed_intents_json"])),
        credential_hash=token_hash,
    )


def require_current_credential(conn: Any, destination: AuthenticatedDestination) -> None:
    """Check again, inside the caller's write transaction, that the credential is still valid.

    `authenticate_destination` ran when the request's headers arrived. The credential may have
    been rotated or the Destination revoked since, so every operation that writes calls this
    while it holds the write lock: rotation and revocation are writes too, so the two are ordered.
    """
    row = conn.execute(
        """
        SELECT c.revoked_at AS cred_revoked_at, d.revoked_at AS dest_revoked_at
        FROM destination_credentials AS c
        JOIN destinations AS d
          ON d.tenant_id = c.tenant_id AND d.destination_id = c.destination_id
        WHERE c.token_hash = ? AND c.tenant_id = ? AND c.destination_id = ?
        """,
        (destination.credential_hash, destination.tenant_id, destination.destination_id),
    ).fetchone()
    if row is None or row["cred_revoked_at"] is not None or row["dest_revoked_at"] is not None:
        raise _credential_revoked()


def _credential_revoked() -> GatewayError:
    return GatewayError(
        401,
        GATEWAY_AUTH_REVOKED,
        "Destination credential has been revoked or rotated.",
        required_action="Use the latest credential or re-create the destination.",
    )


def _validate_publication_destinations(
    conn: Any, tenant_id: str, destination_ids: list[str], intent: str
) -> None:
    """Verify every target destination exists in tenant, is active, and permits intent."""
    if not destination_ids:
        return
    placeholders = ", ".join("?" for _ in destination_ids)
    rows = conn.execute(
        f"""
        SELECT destination_id, allowed_intents_json, revoked_at
        FROM destinations
        WHERE tenant_id = ? AND destination_id IN ({placeholders})
        """,
        (tenant_id, *destination_ids),
    ).fetchall()
    by_id = {row["destination_id"]: row for row in rows}
    for dest_id in destination_ids:
        row = by_id.get(dest_id)
        if row is None:
            raise GatewayError(
                422,
                REQUEST_INVALID,
                "Target destination does not exist.",
            )
        if row["revoked_at"] is not None:
            raise GatewayError(
                422,
                REQUEST_INVALID,
                "Target destination has been revoked.",
            )
        allowed_intents = json.loads(row["allowed_intents_json"])
        if intent not in allowed_intents:
            raise GatewayError(
                422,
                REQUEST_INVALID,
                "Target destination does not allow this publication intent.",
            )


def store_revision(
    db_path: pathlib.Path, auth: AuthenticatedDevice, submission: dict[str, Any]
) -> dict[str, Any]:
    """Persist an immutable revision idempotently for the authenticated tenant & origin device."""
    tenant_id = auth.tenant_id
    record_id = submission["record_id"]
    revision = submission["revision"]
    content = submission["content"]
    capture = submission["capture"]
    publication = submission["publication"]
    now_iso = format_utc(utc_now())

    conn = open_connection(db_path)
    try:
        conn.execute("BEGIN IMMEDIATE")
        try:
            record_row = conn.execute(
                """
                SELECT origin_device_id, deleted_at
                FROM records
                WHERE tenant_id = ? AND record_id = ?
                """,
                (tenant_id, record_id),
            ).fetchone()

            if record_row is not None:
                if record_row["deleted_at"] is not None:
                    raise GatewayError(
                        410,
                        RECORD_DELETED,
                        "Record has been deleted and cannot accept new revisions.",
                    )
                if record_row["origin_device_id"] != auth.device_id:
                    raise GatewayError(
                        403,
                        RECORD_NOT_ORIGIN_DEVICE,
                        "Only the device that created this record may submit revisions.",
                    )
            else:
                conn.execute(
                    """
                    INSERT INTO records (tenant_id, record_id, origin_device_id, created_at)
                    VALUES (?, ?, ?, ?)
                    """,
                    (tenant_id, record_id, auth.device_id, now_iso),
                )

            existing = conn.execute(
                """
                SELECT operation_id, stored_at, text_sha256,
                       superseded_revisions_json, deliveries_json
                FROM revisions
                WHERE tenant_id = ? AND record_id = ? AND revision = ?
                """,
                (tenant_id, record_id, revision),
            ).fetchone()

            if existing is not None:
                if existing["text_sha256"] != content["text_sha256"]:
                    raise GatewayError(
                        409,
                        RECORD_VERSION_CONFLICT,
                        "Revision already exists with different text_sha256.",
                        required_action="Submit the updated text under a higher revision number.",
                    )
                conn.execute("COMMIT")
                return {
                    "schema_version": "1.1",
                    "operation_id": existing["operation_id"],
                    "record_id": record_id,
                    "revision": revision,
                    "text_sha256": existing["text_sha256"],
                    "stored_at": existing["stored_at"],
                    "duplicate": True,
                    "superseded_revisions": json.loads(existing["superseded_revisions_json"]),
                    "deliveries": json.loads(existing["deliveries_json"]),
                }

            destination_ids = list(publication["destination_ids"])
            _validate_publication_destinations(conn, tenant_id, destination_ids, publication["intent"])

            prior_published = conn.execute(
                """
                SELECT revision
                FROM revisions
                WHERE tenant_id = ? AND record_id = ? AND revision < ? AND state = 'PUBLISHED'
                ORDER BY revision ASC
                """,
                (tenant_id, record_id, revision),
            ).fetchall()
            superseded_revisions = [int(r["revision"]) for r in prior_published]
            if superseded_revisions:
                conn.execute(
                    """
                    UPDATE revisions
                    SET state = 'SUPERSEDED'
                    WHERE tenant_id = ? AND record_id = ? AND revision < ? AND state = 'PUBLISHED'
                    """,
                    (tenant_id, record_id, revision),
                )

            higher_exists = conn.execute(
                """
                SELECT 1 FROM revisions
                WHERE tenant_id = ? AND record_id = ? AND revision > ?
                LIMIT 1
                """,
                (tenant_id, record_id, revision),
            ).fetchone()
            state = "SUPERSEDED" if higher_exists is not None else "PUBLISHED"

            operation_id = str(uuid.uuid4())
            deliveries: list[dict[str, Any]] = [
                {
                    "delivery_id": str(uuid.uuid4()),
                    "destination_id": dest_id,
                    "revision": revision,
                    "state": "PENDING",
                }
                for dest_id in destination_ids
            ]
            conn.execute(
                """
                INSERT INTO revisions (
                    tenant_id, record_id, revision, origin_device_id,
                    operation_id, state, stored_at,
                    text, text_sha256, source, language, review_state,
                    execution_location, engine, model_id, model_version,
                    confidence, segments_json, capture_json,
                    intent, destination_ids_json,
                    superseded_revisions_json, deliveries_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    tenant_id,
                    record_id,
                    revision,
                    auth.device_id,
                    operation_id,
                    state,
                    now_iso,
                    content["text"],
                    content["text_sha256"],
                    content["source"],
                    content["language"],
                    content["review_state"],
                    content["execution_location"],
                    content["engine"],
                    content["model_id"],
                    content["model_version"],
                    content["confidence"],
                    json.dumps(content["segments"], ensure_ascii=False),
                    json.dumps(capture, ensure_ascii=False),
                    publication["intent"],
                    json.dumps(destination_ids),
                    json.dumps(superseded_revisions),
                    json.dumps(deliveries),
                ),
            )
            for item in deliveries:
                conn.execute(
                    """
                    INSERT INTO deliveries (
                        delivery_id, tenant_id, record_id, revision, destination_id,
                        state, intent, supersedes_revisions_json, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        item["delivery_id"],
                        tenant_id,
                        record_id,
                        revision,
                        item["destination_id"],
                        item["state"],
                        publication["intent"],
                        json.dumps(superseded_revisions),
                        now_iso,
                    ),
                )
            conn.execute("COMMIT")
            return {
                "schema_version": "1.1",
                "operation_id": operation_id,
                "record_id": record_id,
                "revision": revision,
                "text_sha256": content["text_sha256"],
                "stored_at": now_iso,
                "duplicate": False,
                "superseded_revisions": superseded_revisions,
                "deliveries": deliveries,
            }
        except Exception:
            conn.execute("ROLLBACK")
            raise
    finally:
        conn.close()


def _destination_row_to_resource(row: Any) -> dict[str, Any]:
    return {
        "schema_version": "1.1",
        "destination_id": row["destination_id"],
        "display_name": row["display_name"],
        "send_mode": row["send_mode"],
        "allowed_intents": json.loads(row["allowed_intents_json"]),
        "created_at": row["created_at"],
        "created_by_device_id": row["created_by_device_id"],
        "credential_issued": bool(row["credential_issued"]),
        "revoked_at": row["revoked_at"],
    }


def create_destination(
    db_path: pathlib.Path, auth: AuthenticatedDevice, body: dict[str, Any]
) -> dict[str, Any]:
    """Create a Destination scoped to the authenticated tenant and creator device."""
    destination_id = str(uuid.uuid4())
    now_iso = format_utc(utc_now())
    allowed_intents = list(body["allowed_intents"])

    conn = open_connection(db_path)
    try:
        conn.execute("BEGIN IMMEDIATE")
        try:
            conn.execute(
                """
                INSERT INTO destinations (
                    tenant_id, destination_id, display_name, send_mode,
                    allowed_intents_json, created_at, created_by_device_id,
                    credential_issued, revoked_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 0, NULL)
                """,
                (
                    auth.tenant_id,
                    destination_id,
                    body["display_name"],
                    body["send_mode"],
                    json.dumps(allowed_intents),
                    now_iso,
                    auth.device_id,
                ),
            )
            row = conn.execute(
                """
                SELECT destination_id, display_name, send_mode, allowed_intents_json,
                       created_at, created_by_device_id, credential_issued, revoked_at
                FROM destinations
                WHERE tenant_id = ? AND destination_id = ?
                """,
                (auth.tenant_id, destination_id),
            ).fetchone()
            assert row is not None
            conn.execute("COMMIT")
            return _destination_row_to_resource(row)
        except Exception:
            conn.execute("ROLLBACK")
            raise
    finally:
        conn.close()


def list_destinations(db_path: pathlib.Path, auth: AuthenticatedDevice) -> dict[str, Any]:
    """List all Destinations for the authenticated tenant without echoing credentials."""
    conn = open_connection(db_path)
    try:
        rows = conn.execute(
            """
            SELECT destination_id, display_name, send_mode, allowed_intents_json,
                   created_at, created_by_device_id, credential_issued, revoked_at
            FROM destinations
            WHERE tenant_id = ?
            ORDER BY created_at ASC, destination_id ASC
            """,
            (auth.tenant_id,),
        ).fetchall()
    finally:
        conn.close()
    return {
        "schema_version": "1.1",
        "destinations": [_destination_row_to_resource(row) for row in rows],
    }


def get_destination(
    db_path: pathlib.Path, auth: AuthenticatedDevice, destination_id: str
) -> dict[str, Any]:
    """Return a single Destination for the authenticated tenant without echoing credentials."""
    conn = open_connection(db_path)
    try:
        row = conn.execute(
            """
            SELECT destination_id, display_name, send_mode, allowed_intents_json,
                   created_at, created_by_device_id, credential_issued, revoked_at
            FROM destinations
            WHERE tenant_id = ? AND destination_id = ?
            """,
            (auth.tenant_id, destination_id),
        ).fetchone()
    finally:
        conn.close()
    if row is None:
        raise GatewayError(
            404,
            REQUEST_INVALID,
            "Destination was not found.",
        )
    return _destination_row_to_resource(row)


def issue_destination_credential(
    db_path: pathlib.Path, auth: AuthenticatedDevice, destination_id: str
) -> dict[str, Any]:
    """Issue or rotate the Bearer credential for a Destination; store only its SHA-256 hash."""
    now_iso = format_utc(utc_now())
    credential = f"auri_dst_{secrets.token_urlsafe(32)}"
    token_hash = sha256_str(credential)
    credential_id = str(uuid.uuid4())

    conn = open_connection(db_path)
    try:
        conn.execute("BEGIN IMMEDIATE")
        try:
            dest = conn.execute(
                """
                SELECT credential_issued, revoked_at
                FROM destinations
                WHERE tenant_id = ? AND destination_id = ?
                """,
                (auth.tenant_id, destination_id),
            ).fetchone()
            if dest is None:
                raise GatewayError(
                    404,
                    REQUEST_INVALID,
                    "Destination was not found.",
                )
            if dest["revoked_at"] is not None:
                raise GatewayError(
                    409,
                    REQUEST_INVALID,
                    "Cannot issue a credential for a revoked destination.",
                )

            replaced_previous = bool(dest["credential_issued"])
            if replaced_previous:
                conn.execute(
                    """
                    UPDATE destination_credentials
                    SET revoked_at = ?
                    WHERE tenant_id = ? AND destination_id = ? AND revoked_at IS NULL
                    """,
                    (now_iso, auth.tenant_id, destination_id),
                )

            conn.execute(
                """
                INSERT INTO destination_credentials (
                    credential_id, tenant_id, destination_id, token_hash,
                    issued_by_device_id, issued_at, revoked_at
                ) VALUES (?, ?, ?, ?, ?, ?, NULL)
                """,
                (
                    credential_id,
                    auth.tenant_id,
                    destination_id,
                    token_hash,
                    auth.device_id,
                    now_iso,
                ),
            )
            conn.execute(
                """
                UPDATE destinations
                SET credential_issued = 1
                WHERE tenant_id = ? AND destination_id = ?
                """,
                (auth.tenant_id, destination_id),
            )
            conn.execute("COMMIT")
            return {
                "schema_version": "1.1",
                "destination_id": destination_id,
                "credential": credential,
                "issued_at": now_iso,
                "replaced_previous": replaced_previous,
            }
        except Exception:
            conn.execute("ROLLBACK")
            raise
    finally:
        conn.close()


def revoke_destination(
    db_path: pathlib.Path, auth: AuthenticatedDevice, destination_id: str
) -> dict[str, Any]:
    """Revoke a Destination, all its active credentials, and any pending/leased deliveries."""
    now_iso = format_utc(utc_now())
    conn = open_connection(db_path)
    try:
        conn.execute("BEGIN IMMEDIATE")
        try:
            row = conn.execute(
                """
                SELECT destination_id, display_name, send_mode, allowed_intents_json,
                       created_at, created_by_device_id, credential_issued, revoked_at
                FROM destinations
                WHERE tenant_id = ? AND destination_id = ?
                """,
                (auth.tenant_id, destination_id),
            ).fetchone()
            if row is None:
                raise GatewayError(
                    404,
                    REQUEST_INVALID,
                    "Destination was not found.",
                )
            if row["revoked_at"] is not None:
                conn.execute("COMMIT")
                return _destination_row_to_resource(row)

            conn.execute(
                """
                UPDATE destinations
                SET revoked_at = ?
                WHERE tenant_id = ? AND destination_id = ?
                """,
                (now_iso, auth.tenant_id, destination_id),
            )
            conn.execute(
                """
                UPDATE destination_credentials
                SET revoked_at = ?
                WHERE tenant_id = ? AND destination_id = ? AND revoked_at IS NULL
                """,
                (now_iso, auth.tenant_id, destination_id),
            )
            delivery_rows = conn.execute(
                """
                SELECT delivery_id, state
                FROM deliveries
                WHERE tenant_id = ? AND destination_id = ?
                """,
                (auth.tenant_id, destination_id),
            ).fetchall()
            for delivery_row in delivery_rows:
                outcome = step(delivery_row["state"], DESTINATION_REVOKED)
                if outcome.kind != "transition":
                    continue
                conn.execute(
                    """
                    UPDATE deliveries
                    SET state = ?,
                        cancelled_at = ?,
                        lease_token_hash = NULL,
                        lease_expires_at = NULL
                    WHERE delivery_id = ?
                    """,
                    (outcome.state, now_iso, delivery_row["delivery_id"]),
                )
            updated = conn.execute(
                """
                SELECT destination_id, display_name, send_mode, allowed_intents_json,
                       created_at, created_by_device_id, credential_issued, revoked_at
                FROM destinations
                WHERE tenant_id = ? AND destination_id = ?
                """,
                (auth.tenant_id, destination_id),
            ).fetchone()
            assert updated is not None
            conn.execute("COMMIT")
            return _destination_row_to_resource(updated)
        except Exception:
            conn.execute("ROLLBACK")
            raise
    finally:
        conn.close()
