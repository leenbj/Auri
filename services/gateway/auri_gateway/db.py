"""SQLite connection management, migration runner, and gateway bootstrap."""
from __future__ import annotations

import hashlib
import pathlib
import sqlite3
import uuid
from datetime import datetime, timezone

MIGRATIONS_DIR = pathlib.Path(__file__).resolve().parent / "migrations"


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


def ensure_gateway_initialized(data_dir: pathlib.Path) -> pathlib.Path:
    """Create data_dir if needed, apply pending migrations, and bootstrap gateway_meta."""
    data_dir.mkdir(parents=True, exist_ok=True)
    db_path = db_path_for(data_dir)
    conn = open_connection(db_path)
    try:
        apply_migrations(conn)
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
    finally:
        conn.close()
    return db_path
