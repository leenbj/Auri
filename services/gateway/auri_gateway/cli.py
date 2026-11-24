"""CLI entrypoint for Auri Gateway (`serve`, `pair`, `renew-cert`, `migrate`)."""
from __future__ import annotations

import argparse
import io
import json
import logging
import pathlib
import sys
import tarfile

import qrcode
import uvicorn

from .app import create_app
from .db import MigrationError, ensure_gateway_initialized, open_connection
from .mdns import MdnsAdvertiser
from .network import (
    MDNS_SERVICE_TYPE,
    bind_tcp_sockets,
    build_pairing_addresses,
    clear_runtime_state,
    detect_lan_ips,
    is_loopback_ip,
    resolve_bind_hosts,
    write_runtime_state,
)
from .operations import backup_gateway, restore_gateway
from .store import (
    MAX_PAIRING_TTL_SECONDS,
    delete_revoked_device_records,
    issue_pairing_code,
    list_devices_and_credentials,
    revoke_credential,
    revoke_device,
)
from .tls import DEFAULT_CERT_VALIDITY_DAYS, ensure_tls_material, renew_certificate


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="auri_gateway", description="Auri Gateway CLI")
    subparsers = parser.add_subparsers(dest="command", required=True)

    serve_cmd = subparsers.add_parser("serve", help="Start the Gateway HTTPS server")
    serve_cmd.add_argument(
        "--data-dir",
        type=pathlib.Path,
        required=True,
        help="Persistent directory for SQLite database and gateway state",
    )
    serve_cmd.add_argument(
        "--host",
        action="append",
        dest="hosts",
        default=None,
        help="Bind host IP (repeatable; default binds loopback + detected private LAN IPs)",
    )
    serve_cmd.add_argument("--port", type=int, default=8443, help="Bind port (default: 8443)")
    serve_cmd.add_argument(
        "--plaintext-loopback",
        action="store_true",
        help="Development only: serve plaintext HTTP strictly on loopback (127.0.0.1 / ::1)",
    )
    serve_cmd.add_argument(
        "--no-mdns",
        action="store_true",
        help="Disable mDNS (_auri-gateway._tcp) service advertisement",
    )

    pair_cmd = subparsers.add_parser("pair", help="Generate a one-time short-lived pairing code")
    pair_cmd.add_argument(
        "--data-dir",
        type=pathlib.Path,
        required=True,
        help="Persistent directory used by the running Gateway",
    )
    pair_cmd.add_argument(
        "--ttl-seconds",
        type=int,
        default=300,
        help=f"Lifetime of the pairing code in seconds (1-{MAX_PAIRING_TTL_SECONDS}, default: 300)",
    )
    pair_cmd.add_argument(
        "--port",
        type=int,
        default=None,
        help="Gateway port to include in pairing addresses (defaults to running port or 8443)",
    )
    pair_cmd.add_argument(
        "--host",
        action="append",
        dest="hosts",
        default=None,
        help="Explicit host/IP to include in pairing addresses (repeatable)",
    )
    pair_cmd.add_argument(
        "--qr",
        action="store_true",
        help="Render the pairing payload as a terminal QR code (default in non-JSON mode)",
    )
    pair_cmd.add_argument(
        "--json",
        action="store_true",
        help="Output pairing payload as JSON matching contracts/schemas/pairing.schema.json",
    )

    renew_cmd = subparsers.add_parser(
        "renew-cert",
        help="Renew the self-signed TLS certificate using the existing private key",
    )
    renew_cmd.add_argument(
        "--data-dir",
        type=pathlib.Path,
        required=True,
        help="Persistent directory used by the Gateway",
    )
    renew_cmd.add_argument(
        "--validity-days",
        type=int,
        default=DEFAULT_CERT_VALIDITY_DAYS,
        help=f"Validity period in days for the renewed certificate (default: {DEFAULT_CERT_VALIDITY_DAYS})",
    )
    renew_cmd.add_argument(
        "--json",
        action="store_true",
        help="Output renewed certificate metadata as JSON",
    )

    migrate_cmd = subparsers.add_parser("migrate", help="Apply pending SQLite migrations")
    migrate_cmd.add_argument(
        "--data-dir",
        type=pathlib.Path,
        required=True,
        help="Persistent directory for SQLite database",
    )

    list_devices_cmd = subparsers.add_parser(
        "list-devices",
        help="List paired devices and the Destination credentials each device issued",
    )
    list_devices_cmd.add_argument(
        "--data-dir",
        type=pathlib.Path,
        required=True,
        help="Persistent directory used by the Gateway",
    )
    list_devices_cmd.add_argument(
        "--json",
        action="store_true",
        help="Output device and credential metadata as JSON",
    )

    revoke_device_cmd = subparsers.add_parser(
        "revoke-device",
        help="Revoke a paired device (does not revoke Destination credentials it created)",
    )
    revoke_device_cmd.add_argument(
        "--data-dir",
        type=pathlib.Path,
        required=True,
        help="Persistent directory used by the Gateway",
    )
    revoke_device_cmd.add_argument(
        "--device-id",
        required=True,
        help="UUID of the paired device to revoke",
    )
    revoke_device_cmd.add_argument(
        "--json",
        action="store_true",
        help="Output revocation result as JSON",
    )

    revoke_cred_cmd = subparsers.add_parser(
        "revoke-credential",
        help="Revoke a single Destination credential by credential_id",
    )
    revoke_cred_cmd.add_argument(
        "--data-dir",
        type=pathlib.Path,
        required=True,
        help="Persistent directory used by the Gateway",
    )
    revoke_cred_cmd.add_argument(
        "--credential-id",
        required=True,
        help="UUID of the Destination credential to revoke",
    )
    revoke_cred_cmd.add_argument(
        "--json",
        action="store_true",
        help="Output revocation result as JSON",
    )

    del_dev_records_cmd = subparsers.add_parser(
        "delete-device-records",
        help="Tombstone all Records belonging to a revoked device and cancel open Deliveries",
    )
    del_dev_records_cmd.add_argument(
        "--data-dir",
        type=pathlib.Path,
        required=True,
        help="Persistent directory used by the Gateway",
    )
    del_dev_records_cmd.add_argument(
        "--device-id",
        required=True,
        help="UUID of the revoked device whose Records should be deleted",
    )
    del_dev_records_cmd.add_argument(
        "--json",
        action="store_true",
        help="Output deletion summary as JSON",
    )

    backup_cmd = subparsers.add_parser(
        "backup",
        help="Create a consistent 0600 backup archive of the Gateway data directory",
    )
    backup_cmd.add_argument(
        "--data-dir",
        type=pathlib.Path,
        required=True,
        help="Persistent directory used by the Gateway",
    )
    backup_cmd.add_argument(
        "--output",
        type=pathlib.Path,
        required=True,
        help="Destination path for the backup .tar.gz archive",
    )
    backup_cmd.add_argument(
        "--json",
        action="store_true",
        help="Output backup metadata as JSON",
    )

    restore_cmd = subparsers.add_parser(
        "restore",
        help="Restore a Gateway backup archive into an empty target directory",
    )
    restore_cmd.add_argument(
        "--backup",
        type=pathlib.Path,
        required=True,
        help="Path to the backup .tar.gz archive",
    )
    restore_cmd.add_argument(
        "--data-dir",
        type=pathlib.Path,
        required=True,
        help="Empty or new target directory to restore into",
    )
    restore_cmd.add_argument(
        "--json",
        action="store_true",
        help="Output restore metadata as JSON",
    )
    return parser


def _load_gateway_id(data_dir: pathlib.Path) -> str:
    conn = open_connection(data_dir / "gateway.db")
    try:
        row = conn.execute("SELECT gateway_id FROM gateway_meta WHERE id = 1").fetchone()
        assert row is not None
        return str(row["gateway_id"])
    finally:
        conn.close()


def _cmd_serve(args: argparse.Namespace) -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    try:
        bind_hosts = resolve_bind_hosts(args.hosts)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 1

    if args.plaintext_loopback and not all(is_loopback_ip(h) for h in bind_hosts):
        print(
            "Refusing --plaintext-loopback on non-loopback addresses: "
            "plaintext HTTP is only allowed on local loopback (127.0.0.1 / ::1).",
            file=sys.stderr,
        )
        return 1

    try:
        app = create_app(args.data_dir)
    except MigrationError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    sockets, actual_port = bind_tcp_sockets(bind_hosts, args.port)
    write_runtime_state(args.data_dir, port=actual_port, hosts=bind_hosts)
    advertiser: MdnsAdvertiser | None = None
    try:
        if args.plaintext_loopback:
            config = uvicorn.Config(app, log_level="warning")
        else:
            san_ips = list(dict.fromkeys([*bind_hosts, *detect_lan_ips()]))
            tls_material = ensure_tls_material(args.data_dir, san_ips=san_ips)
            if not args.no_mdns:
                advertiser = MdnsAdvertiser(
                    gateway_id=_load_gateway_id(args.data_dir),
                    hosts=bind_hosts,
                    port=actual_port,
                )
                advertiser.start()
            config = uvicorn.Config(
                app,
                log_level="warning",
                ssl_keyfile=str(tls_material.key_path),
                ssl_certfile=str(tls_material.cert_path),
            )
        uvicorn.Server(config).run(sockets=sockets)
    finally:
        if advertiser is not None:
            advertiser.stop()
        clear_runtime_state(args.data_dir)
        for sock in sockets:
            try:
                sock.close()
            except OSError:
                pass
    return 0


def _cmd_pair(args: argparse.Namespace) -> int:
    try:
        code_info = issue_pairing_code(args.data_dir, ttl_seconds=args.ttl_seconds)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    tls_material = ensure_tls_material(args.data_dir)
    addresses = build_pairing_addresses(
        args.data_dir,
        port_override=args.port,
        host_overrides=args.hosts,
    )
    payload = {
        "schema_version": "1.2",
        "gateway_id": code_info["gateway_id"],
        "addresses": addresses,
        "mdns_service_type": MDNS_SERVICE_TYPE,
        "public_key_sha256": tls_material.public_key_sha256,
        "pairing_code": code_info["pairing_code"],
        "expires_at": code_info["expires_at"],
    }
    if args.json:
        print(json.dumps(payload, ensure_ascii=False))
        return 0

    qr = qrcode.QRCode(border=1)
    qr.add_data(json.dumps(payload, separators=(",", ":"), ensure_ascii=False))
    qr_buf = io.StringIO()
    qr.print_ascii(out=qr_buf, invert=True)
    addr_text = ", ".join(f"{a['host']}:{a['port']}" for a in addresses)
    print(f"Pairing code (expires {payload['expires_at']}): {payload['pairing_code']}")
    print(f"Gateway ID: {payload['gateway_id']}")
    print(f"Addresses: {addr_text}")
    print(f"mDNS service: {payload['mdns_service_type']}")
    print(f"Public key SHA-256: {payload['public_key_sha256']}")
    print(qr_buf.getvalue(), end="")
    return 0


def _cmd_renew_cert(args: argparse.Namespace) -> int:
    try:
        info = renew_certificate(args.data_dir, validity_days=args.validity_days)
    except (FileNotFoundError, ValueError) as exc:
        print(str(exc), file=sys.stderr)
        return 1
    if args.json:
        print(json.dumps(info, ensure_ascii=False))
    else:
        print(
            f"Certificate renewed (expires {info['not_valid_after']}, "
            f"public_key_sha256={info['public_key_sha256']})"
        )
    return 0


def _cmd_list_devices(args: argparse.Namespace) -> int:
    try:
        listing = list_devices_and_credentials(args.data_dir)
    except (FileNotFoundError, MigrationError) as exc:
        print(str(exc), file=sys.stderr)
        return 1
    if args.json:
        print(json.dumps(listing, ensure_ascii=False))
        return 0
    devices = listing["devices"]
    if not devices:
        print("No paired devices.")
        return 0
    for dev in devices:
        print(
            f"{dev['device_id']}  {dev['display_name']}  ({dev['platform']})  "
            f"[{dev['state']}]  paired_at={dev['paired_at']}  revoked_at={dev['revoked_at'] or '-'}"
        )
        creds = dev["credentials"]
        if not creds:
            print("  (no credentials)")
        else:
            for cred in creds:
                print(
                    f"  - credential={cred['credential_id']}  "
                    f"destination={cred['destination_id']} ({cred['destination_display_name']})  "
                    f"[{cred['state']}]  issued_at={cred['issued_at']}  revoked_at={cred['revoked_at'] or '-'}"
                )
    return 0


def _cmd_revoke_device(args: argparse.Namespace) -> int:
    try:
        info = revoke_device(args.data_dir, args.device_id)
    except (FileNotFoundError, LookupError, MigrationError) as exc:
        print(str(exc), file=sys.stderr)
        return 1
    if args.json:
        print(json.dumps(info, ensure_ascii=False))
    else:
        status = "already revoked" if info["already_revoked"] else "revoked"
        print(
            f"Device {info['device_id']} ({info['display_name']}) {status} at {info['revoked_at']}."
        )
    return 0


def _cmd_revoke_credential(args: argparse.Namespace) -> int:
    try:
        info = revoke_credential(args.data_dir, args.credential_id)
    except (FileNotFoundError, LookupError, MigrationError) as exc:
        print(str(exc), file=sys.stderr)
        return 1
    if args.json:
        print(json.dumps(info, ensure_ascii=False))
    else:
        status = "already revoked" if info["already_revoked"] else "revoked"
        print(
            f"Credential {info['credential_id']} for destination {info['destination_id']} "
            f"({info['destination_display_name']}) {status} at {info['revoked_at']}."
        )
    return 0


def _cmd_delete_device_records(args: argparse.Namespace) -> int:
    try:
        summary = delete_revoked_device_records(args.data_dir, args.device_id)
    except (FileNotFoundError, LookupError, ValueError, MigrationError) as exc:
        print(str(exc), file=sys.stderr)
        return 1
    if args.json:
        print(json.dumps(summary, ensure_ascii=False))
    else:
        print(
            f"Deleted {len(summary['deleted_record_ids'])} record(s) for revoked device "
            f"{summary['device_id']} ({summary['display_name']}); "
            f"cancelled {len(summary['cancelled_delivery_ids'])} open delivery(s)."
        )
    return 0


def _cmd_backup(args: argparse.Namespace) -> int:
    try:
        info = backup_gateway(args.data_dir, args.output)
    except (FileNotFoundError, ValueError, RuntimeError, OSError) as exc:
        print(str(exc), file=sys.stderr)
        return 1
    if args.json:
        print(json.dumps(info, ensure_ascii=False))
    else:
        print(
            f"Backup created at {info['backup_path']} "
            f"(gateway_id={info['gateway_id']}, schema_version={info['schema_version']}, "
            f"files={', '.join(info['files'])})."
        )
    return 0


def _cmd_restore(args: argparse.Namespace) -> int:
    try:
        info = restore_gateway(args.backup, args.data_dir)
    except (FileNotFoundError, ValueError, RuntimeError, OSError, tarfile.TarError) as exc:
        print(str(exc), file=sys.stderr)
        return 1
    if args.json:
        print(json.dumps(info, ensure_ascii=False))
    else:
        print(
            f"Restored gateway_id={info['gateway_id']} (schema_version={info['schema_version']}) "
            f"into {info['data_dir']} (files={', '.join(info['restored_files'])})."
        )
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)

    if args.command == "serve":
        return _cmd_serve(args)
    if args.command == "pair":
        return _cmd_pair(args)
    if args.command == "renew-cert":
        return _cmd_renew_cert(args)
    if args.command == "migrate":
        try:
            db_path = ensure_gateway_initialized(args.data_dir)
        except MigrationError as exc:
            print(str(exc), file=sys.stderr)
            return 1
        print(f"Database ready at {db_path}")
        return 0
    if args.command == "list-devices":
        return _cmd_list_devices(args)
    if args.command == "revoke-device":
        return _cmd_revoke_device(args)
    if args.command == "revoke-credential":
        return _cmd_revoke_credential(args)
    if args.command == "delete-device-records":
        return _cmd_delete_device_records(args)
    if args.command == "backup":
        return _cmd_backup(args)
    if args.command == "restore":
        return _cmd_restore(args)
    return 2


if __name__ == "__main__":
    sys.exit(main())
