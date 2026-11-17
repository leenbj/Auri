"""Centralized error codes and unified error response builder (contracts 1.1)."""
from __future__ import annotations

from typing import Any

# Frozen codes in contracts/schemas/common.schema.json#/$defs/error_code (v1.1)
GATEWAY_AUTH_INVALID = "GATEWAY_AUTH_INVALID"
GATEWAY_AUTH_REVOKED = "GATEWAY_AUTH_REVOKED"
PAIRING_CODE_INVALID = "PAIRING_CODE_INVALID"
REQUEST_INVALID = "REQUEST_INVALID"
RECORD_VERSION_CONFLICT = "RECORD_VERSION_CONFLICT"
RECORD_NOT_ORIGIN_DEVICE = "RECORD_NOT_ORIGIN_DEVICE"
RECORD_DELETED = "RECORD_DELETED"


class GatewayError(Exception):
    """Domain or request error mapped to the unified error response format."""

    def __init__(
        self,
        status_code: int,
        code: str,
        message: str,
        *,
        retryable: bool = False,
        required_action: str | None = None,
        operation_id: str | None = None,
    ) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.code = code
        self.message = message
        self.retryable = retryable
        self.required_action = required_action
        self.operation_id = operation_id

    def to_payload(self) -> dict[str, Any]:
        return build_error_payload(
            self.code,
            self.message,
            retryable=self.retryable,
            required_action=self.required_action,
            operation_id=self.operation_id,
        )


def build_error_payload(
    code: str,
    message: str,
    *,
    retryable: bool = False,
    required_action: str | None = None,
    operation_id: str | None = None,
) -> dict[str, Any]:
    """Construct a response dict matching contracts/schemas/error.schema.json (1.1)."""
    return {
        "schema_version": "1.1",
        "code": code,
        "message": message[:500],
        "retryable": retryable,
        "required_action": required_action[:200] if required_action is not None else None,
        "operation_id": operation_id,
    }
