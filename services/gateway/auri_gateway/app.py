"""HTTP ASGI application for Auri Gateway (Ticket 01: loopback plaintext)."""
from __future__ import annotations

import json
import logging
import pathlib
from typing import Any

from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.routing import Route

from .contracts import UUID_RE, check_revision_semantic_rules, validate_schema
from .db import ensure_gateway_initialized
from .error_codes import REQUEST_INVALID, GatewayError
from .store import authenticate_device, redeem_pairing_code, store_revision

logger = logging.getLogger("auri_gateway")


async def _parse_json_object(request: Request) -> dict[str, Any]:
    try:
        payload = await request.json()
    except (json.JSONDecodeError, UnicodeDecodeError, ValueError) as exc:
        raise GatewayError(
            400,
            REQUEST_INVALID,
            "Request body must be valid UTF-8 JSON.",
        ) from exc
    if not isinstance(payload, dict):
        raise GatewayError(
            422,
            REQUEST_INVALID,
            "Request body must be a JSON object.",
        )
    return payload


def _validate_contract_or_raise(schema_ref: str, body: dict[str, Any]) -> None:
    schema_errors = validate_schema(schema_ref, body)
    if schema_errors:
        raise GatewayError(
            422,
            REQUEST_INVALID,
            f"Request body failed schema validation: {', '.join(schema_errors)}",
        )


def create_app(data_dir: pathlib.Path) -> Starlette:
    """Create the ASGI app bound to a persistent SQLite data directory."""
    db_path = ensure_gateway_initialized(data_dir)

    async def health(_request: Request) -> JSONResponse:
        return JSONResponse({"status": "ok"})

    async def pairing_redeem(request: Request) -> JSONResponse:
        body = await _parse_json_object(request)
        _validate_contract_or_raise("pairing.schema.json#/$defs/redeem_request", body)
        result = redeem_pairing_code(db_path, body)
        logger.info("device_paired device_id=%s", result["device_id"])
        return JSONResponse(result)

    async def put_revision(request: Request) -> JSONResponse:
        auth = authenticate_device(db_path, request.headers.get("authorization"))

        path_record_id = request.path_params["record_id"]
        path_revision_raw = request.path_params["revision"]
        if not UUID_RE.match(path_record_id):
            raise GatewayError(
                422,
                REQUEST_INVALID,
                "Path parameter record_id must be a lowercase UUID.",
            )
        try:
            path_revision = int(path_revision_raw)
        except ValueError as exc:
            raise GatewayError(
                422,
                REQUEST_INVALID,
                "Path parameter revision must be a positive integer.",
            ) from exc
        if path_revision < 1:
            raise GatewayError(
                422,
                REQUEST_INVALID,
                "Path parameter revision must be >= 1.",
            )

        body = await _parse_json_object(request)
        _validate_contract_or_raise("revision.schema.json#/$defs/submission", body)

        if body["record_id"] != path_record_id or body["revision"] != path_revision:
            raise GatewayError(
                422,
                REQUEST_INVALID,
                "Path record_id and revision must match the request body.",
            )

        violations = check_revision_semantic_rules(body)
        if violations:
            raise GatewayError(
                422,
                REQUEST_INVALID,
                f"Request violated semantic rules: {', '.join(violations)}",
            )

        result = store_revision(db_path, auth, body)
        logger.info(
            "revision_stored operation_id=%s record_id=%s revision=%s duplicate=%s",
            result["operation_id"],
            result["record_id"],
            result["revision"],
            result["duplicate"],
        )
        return JSONResponse(result)

    async def gateway_error_handler(_request: Request, exc: GatewayError) -> JSONResponse:
        logger.info("request_rejected status=%s code=%s", exc.status_code, exc.code)
        return JSONResponse(exc.to_payload(), status_code=exc.status_code)

    routes = [
        Route("/health", health, methods=["GET"]),
        Route("/v1/pairing/redeem", pairing_redeem, methods=["POST"]),
        Route(
            "/v1/records/{record_id:str}/revisions/{revision:str}",
            put_revision,
            methods=["PUT"],
        ),
    ]
    return Starlette(
        routes=routes,
        exception_handlers={GatewayError: gateway_error_handler},
    )
