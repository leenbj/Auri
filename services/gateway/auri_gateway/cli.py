"""CLI entrypoint for Auri Gateway (`serve`, `pair`, `migrate`)."""
from __future__ import annotations

import argparse
import json
import logging
import pathlib
import sys

import uvicorn

from .app import create_app
from .db import ensure_gateway_initialized
from .store import issue_pairing_code


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="auri_gateway", description="Auri Gateway CLI")
    subparsers = parser.add_subparsers(dest="command", required=True)

    serve_cmd = subparsers.add_parser("serve", help="Start the Gateway HTTP server")
    serve_cmd.add_argument(
        "--data-dir",
        type=pathlib.Path,
        required=True,
        help="Persistent directory for SQLite database and gateway state",
    )
    serve_cmd.add_argument("--host", default="127.0.0.1", help="Bind host (default: 127.0.0.1)")
    serve_cmd.add_argument("--port", type=int, default=8080, help="Bind port (default: 8080)")

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
        help="Lifetime of the pairing code in seconds (default: 300)",
    )
    pair_cmd.add_argument(
        "--json",
        action="store_true",
        help="Output pairing code details as JSON",
    )

    migrate_cmd = subparsers.add_parser("migrate", help="Apply pending SQLite migrations")
    migrate_cmd.add_argument(
        "--data-dir",
        type=pathlib.Path,
        required=True,
        help="Persistent directory for SQLite database",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)

    if args.command == "serve":
        logging.basicConfig(
            level=logging.INFO,
            format="%(asctime)s %(levelname)s %(name)s %(message)s",
        )
        app = create_app(args.data_dir)
        uvicorn.run(app, host=args.host, port=args.port, log_level="warning")
        return 0

    if args.command == "pair":
        result = issue_pairing_code(args.data_dir, ttl_seconds=args.ttl_seconds)
        if args.json:
            print(json.dumps(result, ensure_ascii=False))
        else:
            print(f"Pairing code (expires {result['expires_at']}): {result['pairing_code']}")
        return 0

    if args.command == "migrate":
        db_path = ensure_gateway_initialized(args.data_dir)
        print(f"Database ready at {db_path}")
        return 0

    return 2


if __name__ == "__main__":
    sys.exit(main())
