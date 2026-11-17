"""HTTP ASGI application for Auri Gateway (Ticket 01: loopback plaintext)."""
from __future__ import annotations

import json
import logging
import pathlib
from typing import Any

from starlette.applications import Starlette
from starlette.exceptions import HTTPException
from starlette.requests import Request
from starlette.responses import JSONResponse, Response
from starlette.routing import Route

from .contracts import UUID_RE, check_revision_semantic_rules, validate_schema
from .db import ensure_gateway_initialized
from .error_codes import GATEWAY_UNREACHABLE, REQUEST_INVALID, GatewayError, build_error_payload
from .mcp_endpoint import AgentMcpEndpoint
from .store import (
    authenticate_device,
    create_destination,
    delete_record,
    get_destination,
    issue_destination_credential,
    list_destinations,
    redeem_pairing_code,
    revoke_destination,
    store_revision,
)

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


def _validate_uuid_param(value: str, param_name: str) -> str:
    if not UUID_RE.match(value):
        raise GatewayError(
            422,
            REQUEST_INVALID,
            f"Path parameter {param_name} must be a lowercase UUID.",
        )
    return value


async def _ensure_empty_or_empty_object_body(request: Request) -> None:
    raw = await request.body()
    if not raw.strip():
        return
    try:
        payload = json.loads(raw.decode("utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError, ValueError) as exc:
        raise GatewayError(
            400,
            REQUEST_INVALID,
            "Request body must be empty or valid UTF-8 JSON.",
        ) from exc
    if payload != {}:
        raise GatewayError(
            422,
            REQUEST_INVALID,
            "Request body must be empty.",
        )


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
        if (
            not path_revision_raw.isascii()
            or not path_revision_raw.isdigit()
            or path_revision_raw.startswith("0")
        ):
            raise GatewayError(
                422,
                REQUEST_INVALID,
                "Path parameter revision must be a positive integer.",
            )
        path_revision = int(path_revision_raw)

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

    async def delete_record_endpoint(request: Request) -> JSONResponse:
        auth = authenticate_device(db_path, request.headers.get("authorization"))
        record_id = _validate_uuid_param(request.path_params["record_id"], "record_id")
        await _ensure_empty_or_empty_object_body(request)
        result = delete_record(db_path, auth, record_id)
        logger.info(
            "record_deleted record_id=%s cancelled_count=%s",
            record_id,
            len(result["cancelled_delivery_ids"]),
        )
        return JSONResponse(result)

    async def create_destination_endpoint(request: Request) -> JSONResponse:
        auth = authenticate_device(db_path, request.headers.get("authorization"))
        body = await _parse_json_object(request)
        _validate_contract_or_raise("destination.schema.json#/$defs/create_request", body)
        result = create_destination(db_path, auth, body)
        logger.info("destination_created destination_id=%s", result["destination_id"])
        return JSONResponse(result)

    async def list_destinations_endpoint(request: Request) -> JSONResponse:
        auth = authenticate_device(db_path, request.headers.get("authorization"))
        return JSONResponse(list_destinations(db_path, auth))

    async def get_destination_endpoint(request: Request) -> JSONResponse:
        auth = authenticate_device(db_path, request.headers.get("authorization"))
        destination_id = _validate_uuid_param(request.path_params["destination_id"], "destination_id")
        return JSONResponse(get_destination(db_path, auth, destination_id))

    async def issue_destination_credential_endpoint(request: Request) -> JSONResponse:
        auth = authenticate_device(db_path, request.headers.get("authorization"))
        destination_id = _validate_uuid_param(request.path_params["destination_id"], "destination_id")
        await _ensure_empty_or_empty_object_body(request)
        result = issue_destination_credential(db_path, auth, destination_id)
        logger.info(
            "destination_credential_issued destination_id=%s replaced_previous=%s",
            destination_id,
            result["replaced_previous"],
        )
        return JSONResponse(result)

    async def revoke_destination_endpoint(request: Request) -> JSONResponse:
        auth = authenticate_device(db_path, request.headers.get("authorization"))
        destination_id = _validate_uuid_param(request.path_params["destination_id"], "destination_id")
        result = revoke_destination(db_path, auth, destination_id)
        logger.info("destination_revoked destination_id=%s", destination_id)
        return JSONResponse(result)

    mcp_endpoint = AgentMcpEndpoint(db_path)

    async def gateway_error_handler(_request: Request, exc: GatewayError) -> JSONResponse:
        logger.info("request_rejected status=%s code=%s", exc.status_code, exc.code)
        return JSONResponse(exc.to_payload(), status_code=exc.status_code)

    async def http_exception_handler(_request: Request, exc: HTTPException) -> Response:
        if exc.status_code in {204, 304}:
            return Response(status_code=exc.status_code, headers=exc.headers)
        if exc.status_code == 404:
            code, message, retryable = REQUEST_INVALID, "Resource not found.", False
        elif exc.status_code == 405:
            code, message, retryable = REQUEST_INVALID, "Method not allowed.", False
        elif exc.status_code >= 500:
            code, message, retryable = GATEWAY_UNREACHABLE, "Internal gateway error.", True
        else:
            code, message, retryable = REQUEST_INVALID, "Request is invalid.", False
        logger.info("http_exception status=%s code=%s", exc.status_code, code)
        return JSONResponse(
            build_error_payload(code, message, retryable=retryable),
            status_code=exc.status_code,
            headers=exc.headers,
        )

    async def unhandled_exception_handler(_request: Request, exc: Exception) -> JSONResponse:
        logger.exception("unhandled_gateway_error", exc_info=exc)
        return JSONResponse(
            build_error_payload(
                GATEWAY_UNREACHABLE,
                "Internal gateway error.",
                retryable=True,
            ),
            status_code=500,
        )

    routes = [
        Route("/health", health, methods=["GET"]),
        Route("/v1/pairing/redeem", pairing_redeem, methods=["POST"]),
        Route("/v1/destinations", list_destinations_endpoint, methods=["GET"]),
        Route("/v1/destinations", create_destination_endpoint, methods=["POST"]),
        Route("/v1/destinations/{destination_id:str}", get_destination_endpoint, methods=["GET"]),
        Route("/v1/destinations/{destination_id:str}", revoke_destination_endpoint, methods=["DELETE"]),
        Route(
            "/v1/destinations/{destination_id:str}/credential",
            issue_destination_credential_endpoint,
            methods=["POST"],
        ),
        Route(
            "/v1/records/{record_id:str}",
            delete_record_endpoint,
            methods=["DELETE"],
        ),
        Route(
            "/v1/records/{record_id:str}/revisions/{revision:str}",
            put_revision,
            methods=["PUT"],
        ),
        Route("/mcp", endpoint=mcp_endpoint.asgi_app()),
    ]
    return Starlette(
        debug=False,
        routes=routes,
        exception_handlers={
            GatewayError: gateway_error_handler,
            HTTPException: http_exception_handler,
            500: unhandled_exception_handler,
            Exception: unhandled_exception_handler,
        },
        lifespan=mcp_endpoint.lifespan,
    )
