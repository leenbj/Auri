"""SQLite connection management, migration runner, and gateway bootstrap."""
from __future__ import annotations

import hashlib
import os
import pathlib
import sqlite3
import uuid
from datetime import datetime, timezone

MIGRATIONS_DIR = pathlib.Path(__file__).resolve().parent / "migrations"


class MigrationError(RuntimeError):
    """Raised when applying SQLite migrations fails; original database remains untouched."""


def _default_migrations_dir() -> pathlib.Path:
    env_dir = os.environ.get("AURI_GATEWAY_MIGRATIONS_DIR")
    if env_dir:
        return pathlib.Path(env_dir)
    return MIGRATIONS_DIR


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def format_utc(dt: datetime) -> str:
    """Format UTC datetime in ISO 8601 format accepted by common.schema.json."""
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


def parse_utc(value: str) -> datetime:
    normalized = value[:-1] + "+00:00" if value.endswith("Z") else value
    return datetime.fromisoformat(normalized).astimezone(timezone.utc)


def sha256_str(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def db_path_for(data_dir: pathlib.Path) -> pathlib.Path:
    return data_dir / "gateway.db"


def open_connection(db_path: pathlib.Path) -> sqlite3.Connection:
    conn = sqlite3.connect(db_path, timeout=5.0, isolation_level=None)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA journal_mode = WAL")
    conn.execute("PRAGMA busy_timeout = 5000")
    return conn


def apply_migrations(
    conn: sqlite3.Connection, migrations_dir: pathlib.Path = MIGRATIONS_DIR
) -> list[tuple[int, str]]:
    """Apply all unapplied SQL migrations in ascending numeric order."""
    applied: list[tuple[int, str]] = []
    conn.execute("BEGIN IMMEDIATE")
    try:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS schema_migrations (
                version INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                applied_at TEXT NOT NULL
            )
            """
        )
        existing_versions = {
            row["version"]
            for row in conn.execute("SELECT version FROM schema_migrations").fetchall()
        }
        migration_files = sorted(migrations_dir.glob("*.sql"))
        for path in migration_files:
            version_str, _, name_part = path.stem.partition("_")
            version = int(version_str)
            if version in existing_versions:
                continue
            sql = path.read_text(encoding="utf-8")
            # executescript commits any open transaction, so execute statements individually
            for statement in _split_sql_statements(sql):
                conn.execute(statement)
            conn.execute(
                "INSERT INTO schema_migrations (version, name, applied_at) VALUES (?, ?, ?)",
                (version, name_part or path.stem, format_utc(utc_now())),
            )
            applied.append((version, path.name))
        conn.execute("COMMIT")
    except Exception:
        conn.execute("ROLLBACK")
        raise
    return applied


def _split_sql_statements(sql: str) -> list[str]:
    statements: list[str] = []
    buffer: list[str] = []
    for line in sql.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("--"):
            continue
        buffer.append(line)
        if sqlite3.complete_statement("\n".join(buffer)):
            stmt = "\n".join(buffer).strip()
            if stmt:
                statements.append(stmt)
            buffer.clear()
    leftover = "\n".join(buffer).strip()
    if leftover:
        statements.append(leftover)
    return statements


def _pending_migration_paths(
    conn: sqlite3.Connection, migrations_dir: pathlib.Path
) -> list[pathlib.Path]:
    has_table = conn.execute(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'schema_migrations'"
    ).fetchone()
    if has_table is None:
        existing_versions: set[int] = set()
    else:
        existing_versions = {
            row[0] for row in conn.execute("SELECT version FROM schema_migrations").fetchall()
        }
    pending: list[pathlib.Path] = []
    for path in sorted(migrations_dir.glob("*.sql")):
        version_str, _, _ = path.stem.partition("_")
        if int(version_str) not in existing_versions:
            pending.append(path)
    return pending


def _has_gateway_meta(conn: sqlite3.Connection) -> bool:
    has_table = conn.execute(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'gateway_meta'"
    ).fetchone()
    if has_table is None:
        return False
    return conn.execute("SELECT 1 FROM gateway_meta WHERE id = 1").fetchone() is not None


def _ensure_gateway_meta(conn: sqlite3.Connection) -> None:
    conn.execute("BEGIN IMMEDIATE")
    try:
        row = conn.execute("SELECT gateway_id FROM gateway_meta WHERE id = 1").fetchone()
        if row is None:
            now = format_utc(utc_now())
            gateway_id = str(uuid.uuid4())
            tenant_id = str(uuid.uuid4())
            conn.execute(
                "INSERT INTO tenants (tenant_id, name, created_at) VALUES (?, ?, ?)",
                (tenant_id, "default", now),
            )
            conn.execute(
                """
                INSERT INTO gateway_meta (id, gateway_id, default_tenant_id, created_at)
                VALUES (1, ?, ?, ?)
                """,
                (gateway_id, tenant_id, now),
            )
        conn.execute("COMMIT")
    except Exception:
        conn.execute("ROLLBACK")
        raise


def _unlink_sqlite_with_sidecars(path: pathlib.Path) -> None:
    for suffix in ("", "-wal", "-shm"):
        pathlib.Path(f"{path}{suffix}").unlink(missing_ok=True)


def _checkpoint_and_switch_to_delete_mode(db_path: pathlib.Path) -> None:
    conn = sqlite3.connect(db_path, timeout=5.0, isolation_level=None)
    try:
        conn.execute("PRAGMA busy_timeout = 5000")
        conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        conn.execute("PRAGMA journal_mode = DELETE")
    finally:
        conn.close()
    for suffix in ("-wal", "-shm"):
        pathlib.Path(f"{db_path}{suffix}").unlink(missing_ok=True)


def ensure_gateway_initialized(
    data_dir: pathlib.Path,
    migrations_dir: pathlib.Path | None = None,
) -> pathlib.Path:
    """Create data_dir if needed, safely apply pending migrations on a copy, and bootstrap gateway_meta."""
    resolved_migrations_dir = migrations_dir or _default_migrations_dir()
    data_dir.mkdir(parents=True, exist_ok=True)
    db_path = db_path_for(data_dir)

    if db_path.exists():
        check_conn = sqlite3.connect(db_path, timeout=5.0, isolation_level=None)
        try:
            check_conn.execute("PRAGMA busy_timeout = 5000")
            pending = _pending_migration_paths(check_conn, resolved_migrations_dir)
            has_meta = _has_gateway_meta(check_conn)
            if not pending and has_meta:
                return db_path

            staging_path = data_dir / f".gateway.db.migrate.{uuid.uuid4().hex}.tmp"
            fd = os.open(staging_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            os.close(fd)
            backup_conn = sqlite3.connect(staging_path)
            try:
                check_conn.backup(backup_conn)
            finally:
                backup_conn.close()
        finally:
            check_conn.close()
    else:
        staging_path = data_dir / f".gateway.db.migrate.{uuid.uuid4().hex}.tmp"
        fd = os.open(staging_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        os.close(fd)

    try:
        staging_conn = open_connection(staging_path)
        try:
            apply_migrations(staging_conn, migrations_dir=resolved_migrations_dir)
            _ensure_gateway_meta(staging_conn)
            integrity = staging_conn.execute("PRAGMA integrity_check").fetchone()
            if integrity is None or integrity[0] != "ok":
                raise MigrationError("Migrated database failed SQLite integrity check.")
        finally:
            staging_conn.close()
        _checkpoint_and_switch_to_delete_mode(staging_path)
    except Exception as exc:
        _unlink_sqlite_with_sidecars(staging_path)
        if isinstance(exc, MigrationError):
            raise
        raise MigrationError(
            f"Database migration failed; original database at {db_path} left untouched: {exc}"
        ) from exc

    if db_path.exists():
        _checkpoint_and_switch_to_delete_mode(db_path)
    os.chmod(staging_path, 0o600)
    os.replace(staging_path, db_path)
    return db_path
