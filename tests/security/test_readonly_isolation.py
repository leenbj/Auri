"""Black-box security & isolation tests for read-only MCP tools and query endpoints (Ticket 05)."""
from __future__ import annotations

import json
import pathlib
import sys
import uuid
from typing import Any

import httpx
import pytest

_INTEGRATION_DIR = pathlib.Path(__file__).resolve().parents[1] / "integration"
if str(_INTEGRATION_DIR) not in sys.path:
    sys.path.append(str(_INTEGRATION_DIR))

from harness import (  # noqa: E402
    FIXTURE_DIR,
    GatewayProcess,
    World,
    assert_valid_contract,
    gateway,  # noqa: F401
    world,  # noqa: F401
)

_TOOL_FOR_REQUEST = {
    "list_request": "record_list",
    "get_request": "record_get",
    "search_request": "record_search",
}


def _schema_invalid_query_requests() -> list[tuple[str, str, dict[str, Any]]]:
    found = []
    for path in sorted((FIXTURE_DIR / "messages" / "schema-invalid").glob("record_query.*.json")):
        fixture = json.loads(path.read_text(encoding="utf-8"))
        request_name = fixture["schema"].rsplit("/", 1)[-1]
        if request_name in _TOOL_FOR_REQUEST:
            found.append((path.stem, _TOOL_FOR_REQUEST[request_name], fixture["instance"]))
    return found


INVALID_QUERY_INPUTS = _schema_invalid_query_requests()


@pytest.mark.parametrize(
    ("tool", "arguments"),
    [(tool, instance) for _, tool, instance in INVALID_QUERY_INPUTS],
    ids=[name for name, _, _ in INVALID_QUERY_INPUTS],
)
def test_contract_invalid_readonly_tool_input_is_rejected(
    world: World, tool: str, arguments: dict[str, Any]
) -> None:
    dest = world.destination()
    world.publish(dest, text="受保护的口述内容。")
    agent = world.agent(dest)

    error = agent.call_error(tool, arguments)

    assert error["code"] == "REQUEST_INVALID"
    assert error["retryable"] is False


def test_readonly_tools_enforce_strict_destination_isolation_and_reject_identity_fields(
    world: World,
) -> None:
    mine = world.destination("Mine")
    other = world.destination("Other")
    other_text = f"机密内容-{uuid.uuid4()}"
    other_rec = world.publish(other, text=other_text)["record_id"]
    my_agent = world.agent(mine)

    # Self-reported identity fields are rejected on all three read-only tools.
    for self_reported in (
        {"destination_id": other.destination_id},
        {"tenant_id": str(uuid.uuid4())},
        {"agent_id": "other-agent"},
    ):
        assert my_agent.call_error("record_list", self_reported)["code"] == "REQUEST_INVALID"
        assert (
            my_agent.call_error("record_get", {"record_id": other_rec, **self_reported})["code"]
            == "REQUEST_INVALID"
        )
        assert (
            my_agent.call_error("record_search", {"query": "机密", **self_reported})["code"]
            == "REQUEST_INVALID"
        )

    # Destination `mine` cannot see `other`'s record via list, search, or get.
    assert my_agent.record_list()["records"] == []
    assert my_agent.record_search("机密")["matches"] == []
    err_get = my_agent.call_error("record_get", {"record_id": other_rec})
    err_missing = my_agent.call_error("record_get", {"record_id": str(uuid.uuid4())})
    assert err_get["code"] == "REQUEST_INVALID"
    assert (err_get["code"], err_get["message"]) == (err_missing["code"], err_missing["message"])


def test_readonly_and_query_logs_never_contain_text_queries_or_credentials(
    world: World,
) -> None:
    dictated = f"私密查询正文-{uuid.uuid4()}"
    search_term = f"私密词-{uuid.uuid4()}"
    dest = world.destination()
    stored = world.publish(dest, text=f"{dictated} {search_term}")
    agent = world.agent(dest)

    headers = {"Authorization": f"Bearer {world.device_token}"}
    assert httpx.get(f"{world.gw.base_url}/v1/capabilities", trust_env=False, headers=headers, timeout=5.0).status_code == 200
    assert (
        httpx.get(
            f"{world.gw.base_url}/v1/operations/{stored['operation_id']}", trust_env=False,
            headers=headers,
            timeout=5.0,
        ).status_code
        == 200
    )
    assert (
        httpx.get(
            f"{world.gw.base_url}/v1/records/{stored['record_id']}/status", trust_env=False,
            headers=headers,
            timeout=5.0,
        ).status_code
        == 200
    )

    agent.record_list()
    agent.record_get(stored["record_id"])
    agent.record_search(search_term)
    agent.call_error("record_get", {"record_id": str(uuid.uuid4())})

    stdout, stderr = world.gw.stop()
    output = stdout + stderr
    for name, secret in {
        "dictated text": dictated,
        "search query": search_term,
        "device token": world.device_token,
        "destination credential": dest.credential,
    }.items():
        assert secret not in output, f"gateway output leaked {name}"


def test_revoked_device_token_is_rejected_on_all_new_http_query_endpoints(
    gateway: GatewayProcess,
) -> None:
    paired = gateway.pair_device("Soon Revoked Phone")
    device_id = paired["device_id"]
    headers = {"Authorization": f"Bearer {paired['device_token']}"}
    stored = gateway.publish(paired["device_token"], [], text="吊销前入库。")

    # Revoke device directly in SQLite (no HTTP device-revoke endpoint in V1).
    import sqlite3

    with sqlite3.connect(gateway.data_dir / "gateway.db") as conn:
        conn.execute(
            "UPDATE devices SET revoked_at = '2026-11-18T00:00:00Z' WHERE device_id = ?",
            (device_id,),
        )

    for path in (
        "/v1/capabilities",
        f"/v1/operations/{stored['operation_id']}",
        f"/v1/records/{stored['record_id']}/status",
    ):
        resp = httpx.get(f"{gateway.base_url}{path}", trust_env=False, headers=headers, timeout=5.0)
        assert resp.status_code == 401
        body = resp.json()
        assert_valid_contract("error.schema.json", body)
        assert body["code"] == "GATEWAY_AUTH_REVOKED"
