"""CLI entrypoint for Auri Gateway (`serve`, `pair`, `renew-cert`, `migrate`)."""
from __future__ import annotations

import argparse
import io
import json
import logging
import pathlib
import sys

import qrcode
import uvicorn

from .app import create_app
from .db import ensure_gateway_initialized, open_connection
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
from .store import MAX_PAIRING_TTL_SECONDS, issue_pairing_code
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

    app = create_app(args.data_dir)
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
        "schema_version": "1.1",
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
        db_path = ensure_gateway_initialized(args.data_dir)
        print(f"Database ready at {db_path}")
        return 0
    return 2


if __name__ == "__main__":
    sys.exit(main())
