"""Black-box tests for Gateway CLI startup, persistence across restart, and migrations."""
from __future__ import annotations

import pathlib
import sqlite3
import sys
import uuid

import httpx

from harness import REPO_ROOT, GatewayProcess, assert_valid_contract, make_submission


def test_gateway_persists_paired_devices_and_revisions_across_restart(
    tmp_path: pathlib.Path,
) -> None:
    data_dir = tmp_path / "auri-data"
    gw1 = GatewayProcess(data_dir)
    gw1.start()
    try:
        health = httpx.get(f"{gw1.base_url}/health", timeout=5.0)
        assert health.status_code == 200
        assert health.json() == {"status": "ok"}

        paired = gw1.pair_device("Persisted Phone")
        token = paired["device_token"]
        gateway_id_1 = paired["gateway_id"]

        record_id = str(uuid.uuid4())
        sub = make_submission(record_id=record_id, revision=1, text="重启前入库的内容。")
        assert_valid_contract("revision.schema.json#/$defs/submission", sub)
        put_resp = httpx.put(
            f"{gw1.base_url}/v1/records/{record_id}/revisions/1",
            json=sub,
            headers={"Authorization": f"Bearer {token}"},
            timeout=5.0,
        )
        assert put_resp.status_code == 200
        stored_1 = put_resp.json()
        assert_valid_contract("revision.schema.json#/$defs/stored", stored_1)
        assert stored_1["duplicate"] is False
    finally:
        gw1.stop()

    assert (data_dir / "gateway.db").exists()

    # Restart Gateway on a new port pointing to the same persistent data_dir.
    gw2 = GatewayProcess(data_dir)
    gw2.start()
    try:
        # Existing device token still authenticates, duplicate submit returns the original stored result.
        dup_resp = httpx.put(
            f"{gw2.base_url}/v1/records/{record_id}/revisions/1",
            json=sub,
            headers={"Authorization": f"Bearer {token}"},
            timeout=5.0,
        )
        assert dup_resp.status_code == 200
        stored_dup = dup_resp.json()
        assert_valid_contract("revision.schema.json#/$defs/stored", stored_dup)
        assert stored_dup["duplicate"] is True
        assert stored_dup["operation_id"] == stored_1["operation_id"]
        assert stored_dup["stored_at"] == stored_1["stored_at"]

        # Pairing a second device after restart preserves the same gateway_id.
        paired_2 = gw2.pair_device("Second Phone")
        assert paired_2["gateway_id"] == gateway_id_1
    finally:
        gw2.stop()


def test_cli_migrate_initializes_database_and_supports_future_migrations(
    tmp_path: pathlib.Path,
) -> None:
    data_dir = tmp_path / "mig-data"
    gw = GatewayProcess(data_dir)
    migrate_result = gw.run_cli("migrate", "--data-dir", str(data_dir))
    assert migrate_result.returncode == 0

    db_path = data_dir / "gateway.db"
    assert db_path.exists()

    sys.path.insert(0, str(REPO_ROOT / "services" / "gateway"))
    from auri_gateway.db import apply_migrations, open_connection

    migrations_dir = tmp_path / "extra-migrations"
    migrations_dir.mkdir()
    real_migrations_dir = REPO_ROOT / "services" / "gateway" / "auri_gateway" / "migrations"
    for existing_sql in sorted(real_migrations_dir.glob("*.sql")):
        (migrations_dir / existing_sql.name).write_text(
            existing_sql.read_text(encoding="utf-8"),
            encoding="utf-8",
        )
    (migrations_dir / "0003_future_placeholder.sql").write_text(
        "CREATE TABLE IF NOT EXISTS sample_future_table (id TEXT PRIMARY KEY);\n",
        encoding="utf-8",
    )

    with open_connection(db_path) as conn:
        applied = apply_migrations(conn, migrations_dir=migrations_dir)
        assert applied == [(3, "0003_future_placeholder.sql")]
        assert apply_migrations(conn, migrations_dir=migrations_dir) == []
        versions = [
            row[0]
            for row in conn.execute("SELECT version FROM schema_migrations ORDER BY version").fetchall()
        ]
        assert versions == [1, 2, 3]
        tables = {
            row[0]
            for row in conn.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            ).fetchall()
        }
        assert "sample_future_table" in tables
