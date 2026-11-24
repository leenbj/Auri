"""Ticket 22 review M4: the Gateway, not a phone's cached list, enforces the 50 active Destinations limit.

The phone used to check the limit against its own cached list and `take(50)` the targets of a publication, so a stale cache
(another phone had created Destinations meanwhile) or an older gateway let the 51st Destination exist and then silently
never receive a text. The rule is now authoritative here: a Tenant may have at most 50 active (not revoked) Destinations,
the 51st creation is refused visibly with `409 REQUEST_INVALID` and creates nothing. Revoked Destinations do not count.
"""
from __future__ import annotations

import concurrent.futures
import pathlib
import sqlite3
import sys

import httpx

from harness import (
    REPO_ROOT,
    GatewayProcess,
    assert_valid_contract,
    gateway,  # noqa: F401
    load_fixture,
)

if str(REPO_ROOT / "services" / "gateway") not in sys.path:
    sys.path.insert(0, str(REPO_ROOT / "services" / "gateway"))

LIMIT = 50


def _create(gw: GatewayProcess, token: str, name: str) -> httpx.Response:
    request = {**load_fixture("messages/valid/destination.create_request.preview-confirm.json")["instance"], "display_name": name}
    return httpx.post(
        f"{gw.base_url}/v1/destinations", trust_env=False, json=request,
        headers={"Authorization": f"Bearer {token}"}, timeout=10.0,
    )


def _list(gw: GatewayProcess, token: str) -> list[dict]:
    resp = httpx.get(f"{gw.base_url}/v1/destinations", trust_env=False, headers={"Authorization": f"Bearer {token}"}, timeout=10.0)
    assert resp.status_code == 200, resp.text
    return resp.json()["destinations"]


def _fill(gw: GatewayProcess, token: str) -> list[str]:
    ids = []
    for index in range(LIMIT):
        resp = _create(gw, token, f"Agent {index}")
        assert resp.status_code == 200, f"Destination {index + 1} must still fit: {resp.status_code} {resp.text}"
        ids.append(resp.json()["destination_id"])
    return ids


def test_the_51st_active_destination_is_refused_visibly_and_creates_nothing(gateway: GatewayProcess) -> None:
    token = gateway.pair_device("Owner Phone")["device_token"]
    _fill(gateway, token)

    refused = _create(gateway, token, "One too many")

    assert refused.status_code == 409, refused.text
    error = refused.json()
    assert_valid_contract("error.schema.json", error)
    assert error["code"] == "REQUEST_INVALID"
    assert error["retryable"] is False, "retrying cannot help until the user revokes one"
    assert error["required_action"], "the phone must be able to tell the user what to do"
    assert len(_list(gateway, token)) == LIMIT, "a refused request must not leave a 51st Destination behind"


def test_the_limit_is_shared_by_every_paired_phone_not_counted_per_phone(gateway: GatewayProcess) -> None:
    # The stale-cache case: the second phone has never seen the first phone's Destinations, only the Gateway knows the count.
    first = gateway.pair_device("Phone One")["device_token"]
    second = gateway.pair_device("Phone Two")["device_token"]
    for index in range(LIMIT // 2):
        assert _create(gateway, first, f"A{index}").status_code == 200
        assert _create(gateway, second, f"B{index}").status_code == 200

    assert _create(gateway, first, "late A").status_code == 409
    assert _create(gateway, second, "late B").status_code == 409
    assert len(_list(gateway, first)) == LIMIT


def test_a_revoked_destination_frees_exactly_one_slot(gateway: GatewayProcess) -> None:
    token = gateway.pair_device("Owner Phone")["device_token"]
    ids = _fill(gateway, token)
    revoked = httpx.delete(f"{gateway.base_url}/v1/destinations/{ids[0]}", trust_env=False, headers={"Authorization": f"Bearer {token}"}, timeout=10.0)
    assert revoked.status_code == 200, revoked.text

    assert _create(gateway, token, "takes the freed slot").status_code == 200
    assert _create(gateway, token, "no second slot").status_code == 409
    destinations = _list(gateway, token)
    assert len(destinations) == LIMIT + 1, "the revoked one stays listed"
    assert sum(1 for d in destinations if d["revoked_at"] is None) == LIMIT


def test_parallel_creations_never_exceed_the_limit(gateway: GatewayProcess) -> None:
    # 端到端的不变量：12 个客户端同时建 58 个，恰好 50 个成功。现在的 async 处理器在事件循环里一个一个跑，所以它证明不了 BEGIN IMMEDIATE 本身（改成 BEGIN 也不红，等价变异）；以后处理器若改成线程池而数名额不原子，它大概率变红
    token = gateway.pair_device("Owner Phone")["device_token"]

    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
        statuses = list(pool.map(lambda index: _create(gateway, token, f"P{index}").status_code, range(LIMIT + 8)))

    assert sorted(set(statuses)) == [200, 409]
    assert statuses.count(200) == LIMIT
    assert len(_list(gateway, token)) == LIMIT


def test_the_limit_counts_each_tenant_separately(tmp_path: pathlib.Path) -> None:
    from auri_gateway.db import ensure_gateway_initialized
    from auri_gateway.error_codes import GatewayError
    from auri_gateway.store import AuthenticatedDevice, create_destination

    db_path = ensure_gateway_initialized(tmp_path / "gateway-data")
    with sqlite3.connect(db_path) as conn:
        default_tenant = conn.execute("SELECT default_tenant_id FROM gateway_meta").fetchone()[0]
        conn.execute("INSERT INTO tenants (tenant_id, name, created_at) VALUES ('other-tenant', 'other', '2026-11-21T00:00:00Z')")
        for tenant in (default_tenant, "other-tenant"):
            conn.execute(
                "INSERT INTO devices (device_id, tenant_id, token_hash, platform, app_version, os_build, display_name, paired_at)"
                " VALUES (?, ?, ?, 'android', '0', '0', 'test', '2026-11-21T00:00:00Z')",
                (f"device-of-{tenant}", tenant, f"hash-of-{tenant}"),
            )
    body = load_fixture("messages/valid/destination.create_request.preview-confirm.json")["instance"]
    full = AuthenticatedDevice(f"device-of-{default_tenant}", default_tenant)
    other = AuthenticatedDevice("device-of-other-tenant", "other-tenant")
    for _ in range(LIMIT):
        create_destination(db_path, full, body)

    try:
        create_destination(db_path, full, body)
        raise AssertionError("the full tenant's 51st Destination must be refused")
    except GatewayError as refused:
        assert (refused.status_code, refused.code) == (409, "REQUEST_INVALID")
    assert create_destination(db_path, other, body)["destination_id"], "another tenant's count is its own"
