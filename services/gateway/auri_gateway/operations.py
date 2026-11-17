"""Offline-safe online backup and verified restore operations for Auri Gateway."""
from __future__ import annotations

import hashlib
import io
import json
import os
import pathlib
import sqlite3
import tarfile
import tempfile
import uuid
from typing import Any

from .db import db_path_for, format_utc, utc_now

_BACKUP_FORMAT_VERSION = 1

# Relative paths permitted inside a Gateway backup archive and their restored POSIX modes.
_RESTORE_MODES: dict[str, int] = {
    "gateway.db": 0o600,
    "cursor.key": 0o600,
    "tls/gateway.key.pem": 0o600,
    "tls/gateway.cert.pem": 0o644,
}
_OPTIONAL_DATA_FILES = ("cursor.key", "tls/gateway.key.pem", "tls/gateway.cert.pem")


def _sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _atomic_write_file(path: pathlib.Path, data: bytes, mode: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    fd = os.open(tmp_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode)
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(tmp_path, mode)
        os.replace(tmp_path, path)
    except Exception:
        tmp_path.unlink(missing_ok=True)
        raise


def _check_sqlite_integrity(db_path: pathlib.Path) -> tuple[str, int]:
    conn = sqlite3.connect(str(db_path))
    try:
        integrity = conn.execute("PRAGMA integrity_check").fetchone()
        if integrity is None or integrity[0] != "ok":
            raise RuntimeError(f"SQLite integrity check failed for {db_path}.")
        meta_row = conn.execute("SELECT gateway_id FROM gateway_meta WHERE id = 1").fetchone()
        gateway_id = str(meta_row[0]) if meta_row is not None else ""
        ver_row = conn.execute(
            "SELECT COALESCE(MAX(version), 0) FROM schema_migrations"
        ).fetchone()
        schema_version = int(ver_row[0]) if ver_row is not None else 0
        return gateway_id, schema_version
    finally:
        conn.close()


def backup_gateway(data_dir: pathlib.Path, output_path: pathlib.Path) -> dict[str, Any]:
    """Create a consistent, 0600-protected backup archive of the Gateway data directory.

    Uses SQLite's online backup API (`Connection.backup`) so backups taken while `serve` is
    running capture committed WAL state cleanly without copying live WAL/SHM files.
    """
    db_path = db_path_for(data_dir)
    if not db_path.exists():
        raise FileNotFoundError(f"No gateway database found at {db_path}.")
    if output_path.exists() and output_path.is_dir():
        raise ValueError(f"Backup output path must be a file, not a directory: {output_path}")

    created_at = format_utc(utc_now())

    with tempfile.TemporaryDirectory(prefix="auri-backup-") as tmp_str:
        staging_dir = pathlib.Path(tmp_str)
        os.chmod(staging_dir, 0o700)
        staged_db = staging_dir / "gateway.db"

        src_conn = sqlite3.connect(str(db_path), timeout=10.0)
        try:
            src_conn.execute("PRAGMA busy_timeout = 10000")
            dst_conn = sqlite3.connect(str(staged_db))
            try:
                src_conn.backup(dst_conn)
            finally:
                dst_conn.close()
        finally:
            src_conn.close()

        gateway_id, schema_version = _check_sqlite_integrity(staged_db)

        files_to_pack: list[tuple[str, bytes, int]] = []
        db_bytes = staged_db.read_bytes()
        files_to_pack.append(("gateway.db", db_bytes, _RESTORE_MODES["gateway.db"]))

        for rel in _OPTIONAL_DATA_FILES:
            src_file = data_dir / rel
            if src_file.is_file():
                files_to_pack.append((rel, src_file.read_bytes(), _RESTORE_MODES[rel]))

        manifest = {
            "format_version": _BACKUP_FORMAT_VERSION,
            "gateway_id": gateway_id,
            "schema_version": schema_version,
            "created_at": created_at,
            "files": {
                rel: {
                    "sha256": _sha256_bytes(data),
                    "mode": f"{mode:04o}",
                    "size": len(data),
                }
                for rel, data, mode in files_to_pack
            },
        }
        manifest_bytes = json.dumps(manifest, indent=2, ensure_ascii=False).encode("utf-8")

        output_path.parent.mkdir(parents=True, exist_ok=True)
        tmp_archive = output_path.with_name(f".{output_path.name}.{uuid.uuid4().hex}.tmp")
        fd = os.open(tmp_archive, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        try:
            with os.fdopen(fd, "wb") as raw_handle:
                with tarfile.open(fileobj=raw_handle, mode="w:gz") as tf:
                    _add_tar_bytes(tf, "manifest.json", manifest_bytes, 0o600)
                    for rel, data, mode in files_to_pack:
                        _add_tar_bytes(tf, rel, data, mode)
                raw_handle.flush()
                os.fsync(raw_handle.fileno())
            os.chmod(tmp_archive, 0o600)
            os.replace(tmp_archive, output_path)
        except Exception:
            tmp_archive.unlink(missing_ok=True)
            raise

    return {
        "backup_path": str(output_path),
        "gateway_id": gateway_id,
        "schema_version": schema_version,
        "created_at": created_at,
        "files": [rel for rel, _, _ in files_to_pack],
    }


def _add_tar_bytes(tf: tarfile.TarFile, arcname: str, data: bytes, mode: int) -> None:
    info = tarfile.TarInfo(name=arcname)
    info.size = len(data)
    info.mode = mode
    tf.addfile(info, io.BytesIO(data))


def _validate_archive_member(member: tarfile.TarInfo) -> str:
    raw_name = member.name
    if raw_name.startswith("/") or "\\" in raw_name:
        raise ValueError(f"Unsafe path in backup archive: {raw_name!r}")
    parts = pathlib.PurePosixPath(raw_name).parts
    if any(part in ("", ".", "..") for part in parts):
        raise ValueError(f"Unsafe path component in backup archive: {raw_name!r}")
    normalized = "/".join(parts)
    if member.isdir():
        if normalized != "tls":
            raise ValueError(f"Unexpected directory in backup archive: {normalized!r}")
        return normalized
    if not member.isfile():
        raise ValueError(f"Unsupported non-regular entry in backup archive: {normalized!r}")
    if normalized != "manifest.json" and normalized not in _RESTORE_MODES:
        raise ValueError(f"Unexpected file in backup archive: {normalized!r}")
    return normalized


def restore_gateway(backup_path: pathlib.Path, data_dir: pathlib.Path) -> dict[str, Any]:
    """Restore a Gateway backup archive into an empty (or non-existent) target directory."""
    if not backup_path.is_file():
        raise FileNotFoundError(f"Backup archive not found: {backup_path}")
    if data_dir.exists():
        if not data_dir.is_dir():
            raise ValueError(f"Target data directory path is not a directory: {data_dir}")
        if any(data_dir.iterdir()):
            raise ValueError(
                f"Refusing to restore into non-empty directory {data_dir}: "
                "specify an empty or new directory."
            )

    extracted: dict[str, bytes] = {}
    with tarfile.open(backup_path, mode="r:gz") as tf:
        for member in tf.getmembers():
            normalized = _validate_archive_member(member)
            if member.isdir():
                continue
            handle = tf.extractfile(member)
            if handle is None:
                raise ValueError(f"Failed to read archive member: {normalized}")
            extracted[normalized] = handle.read()

    if "manifest.json" not in extracted:
        raise ValueError("Invalid backup archive: missing manifest.json.")
    manifest = json.loads(extracted["manifest.json"].decode("utf-8"))
    if manifest.get("format_version") != _BACKUP_FORMAT_VERSION:
        raise ValueError(f"Unsupported backup format version: {manifest.get('format_version')!r}")
    manifest_files = manifest.get("files")
    if not isinstance(manifest_files, dict) or "gateway.db" not in manifest_files:
        raise ValueError("Invalid backup manifest: missing gateway.db entry.")

    for rel, meta in manifest_files.items():
        if rel not in _RESTORE_MODES:
            raise ValueError(f"Unsupported file in backup manifest: {rel!r}")
        if rel not in extracted:
            raise ValueError(f"Backup archive missing file listed in manifest: {rel!r}")
        actual_sha = _sha256_bytes(extracted[rel])
        if actual_sha != meta.get("sha256"):
            raise ValueError(f"Checksum mismatch for {rel!r} in backup archive.")

    with tempfile.TemporaryDirectory(prefix="auri-restore-") as tmp_str:
        staged_db = pathlib.Path(tmp_str) / "gateway.db"
        staged_db.write_bytes(extracted["gateway.db"])
        gateway_id, schema_version = _check_sqlite_integrity(staged_db)

    data_dir.mkdir(parents=True, exist_ok=True)
    os.chmod(data_dir, 0o700)
    restored_files: list[str] = []
    for rel, data in extracted.items():
        if rel == "manifest.json":
            continue
        target_path = data_dir / rel
        if target_path.parent != data_dir:
            target_path.parent.mkdir(parents=True, exist_ok=True)
            os.chmod(target_path.parent, 0o700)
        _atomic_write_file(target_path, data, _RESTORE_MODES[rel])
        restored_files.append(rel)

    return {
        "data_dir": str(data_dir),
        "gateway_id": gateway_id,
        "schema_version": schema_version,
        "restored_files": sorted(restored_files),
    }
