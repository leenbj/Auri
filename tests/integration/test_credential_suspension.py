"""Ticket 22 评审 B3：撤销凭证 = 暂停这个 Destination 接收新文字，直到签发新凭证。

以前：`revoke-credential` 只给凭证那一行盖章；资格检查与建 Delivery 只看“Destination 有没有被撤销”，所以撤销凭证之后
(1) 新文字照样为这个 Destination 建 Delivery，越攒越多；(2) 之后签发的新凭证一上来就能领到撤销之前、撤销期间攒下的所有文字。
现在（保守的决定，写在 contracts/README.md 与 OPERATIONS.md）：
- 签发过凭证、却一条有效凭证都没有的 Destination 是“暂停”的（只由撤销凭证造成，从库里的凭证状态现算，不单独存）；
- 撤销的那一刻，它还没被领取（PENDING）和租约中（LEASED）的 Delivery 被取消（沿用状态机的 DESTINATION_REVOKED 事件，不加新状态）；
- 暂停期间 publication / 追加里点名它不报错（手机可能还不知道，整条被拒会让文字永远进不了网关），只是不为它建 Delivery；
- 签发新凭证才恢复，只对**之后**入库的文字生效：撤销之前、撤销期间的文字永远不会补给新凭证；
- Destination 资源里新增可选的 `credential_active`，手机据此显示“已暂停”。
真实网关进程、真实 SQLite；响应对着 contracts/ 校验。
"""
from __future__ import annotations

import json
import sqlite3
from typing import Any

import httpx

from harness import (
    GatewayProcess,
    World,
    assert_valid_contract,
    make_submission,
    gateway,  # noqa: F401
    world,  # noqa: F401
)

RESOURCE = "destination.schema.json#/$defs/resource"


def credential_ids(gw: GatewayProcess, destination_id: str) -> list[tuple[str, str]]:
    listing = json.loads(gw.run_cli("list-devices", "--data-dir", str(gw.data_dir), "--json").stdout)
    return [
        (c["credential_id"], c["state"])
        for d in listing["devices"]
        for c in d["credentials"]
        if c["destination_id"] == destination_id
    ]


def active_credential_id(gw: GatewayProcess, destination_id: str) -> str:
    active = [cid for cid, state in credential_ids(gw, destination_id) if state == "active"]
    assert len(active) == 1, active
    return active[0]


def revoke_credential(gw: GatewayProcess, credential_id: str) -> dict[str, Any]:
    result = gw.run_cli("revoke-credential", "--data-dir", str(gw.data_dir), "--credential-id", credential_id, "--json")
    assert result.returncode == 0, result.stderr
    return json.loads(result.stdout)


def delivery_states(gw: GatewayProcess, destination_id: str) -> list[tuple[str, str]]:
    """这个 Destination 上所有 Delivery 的 (record_id, state)，按建立先后。"""
    with sqlite3.connect(gw.data_dir / "gateway.db") as conn:
        return conn.execute(
            "SELECT record_id, state FROM deliveries WHERE destination_id = ? ORDER BY created_at, delivery_id", (destination_id,)
        ).fetchall()


def stored_revisions(gw: GatewayProcess) -> list[str]:
    with sqlite3.connect(gw.data_dir / "gateway.db") as conn:
        return [row[0] for row in conn.execute("SELECT text FROM revisions ORDER BY stored_at")]


def get_destination(world: World, destination_id: str) -> dict[str, Any]:
    response = httpx.get(
        f"{world.gw.base_url}/v1/destinations/{destination_id}",
        trust_env=False,
        headers={"Authorization": f"Bearer {world.device_token}"},
        timeout=5.0,
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert_valid_contract(RESOURCE, body)
    return body


def add_deliveries(world: World, record_id: str, destination_ids: list[str]) -> httpx.Response:
    return httpx.post(
        f"{world.gw.base_url}/v1/records/{record_id}/revisions/1/deliveries",
        trust_env=False,
        json={"schema_version": "1.2", "intent": "context", "destination_ids": destination_ids},
        headers={"Authorization": f"Bearer {world.device_token}"},
        timeout=5.0,
    )


def test_revoking_the_credential_cancels_what_was_waiting_and_a_new_credential_cannot_claim_it(world: World) -> None:
    dest = world.destination("被撤销凭证的")
    acknowledged = world.publish(dest, text="撤销之前就已确认的")["record_id"]
    leased_a = world.publish(dest, text="撤销之前已被领走、租约还在的（一）")["record_id"]
    leased_b = world.publish(dest, text="撤销之前已被领走、租约还在的（二）")["record_id"]
    agent = world.agent(dest)
    first, _, _ = agent.claim(limit=3)["deliveries"]  # 按建立先后领三条
    agent.ack(first["delivery_id"], first["lease_token"])  # 第一条确认掉；后两条留在租约里
    pending = world.publish(dest, text="撤销之前入库、还没被领取的")["record_id"]
    assert delivery_states(world.gw, dest.destination_id) == [
        (acknowledged, "ACKNOWLEDGED"), (leased_a, "LEASED"), (leased_b, "LEASED"), (pending, "PENDING"),
    ]

    revoke_credential(world.gw, active_credential_id(world.gw, dest.destination_id))

    assert delivery_states(world.gw, dest.destination_id) == [
        (acknowledged, "ACKNOWLEDGED"),  # 已确认的是历史，不动
        (leased_a, "CANCELLED"),  # 租约中的被取消
        (leased_b, "CANCELLED"),
        (pending, "CANCELLED"),  # 还没被领取的被取消
    ]
    renewed = world.rotate_credential(dest)
    assert world.agent(renewed).claim()["deliveries"] == [], "新凭证领不到撤销之前攒下的任何文字"


def test_text_published_while_suspended_is_stored_but_never_delivered_not_even_after_a_new_credential(world: World) -> None:
    dest = world.destination("暂停中的")
    revoke_credential(world.gw, active_credential_id(world.gw, dest.destination_id))

    stored = world.publish(dest, text="暂停期间口述的内容")

    assert stored["deliveries"] == [], "不报错、不拒收：文字照常入库，只是没有为它建 Delivery"
    assert "暂停期间口述的内容" in stored_revisions(world.gw)
    assert delivery_states(world.gw, dest.destination_id) == []
    added = add_deliveries(world, stored["record_id"], [dest.destination_id])
    assert added.status_code == 200, added.text
    assert added.json()["deliveries"] == [], "追加也一样：暂停的 Destination 不建 Delivery，也不让整个请求失败"

    renewed = world.rotate_credential(dest)

    assert world.agent(renewed).claim()["deliveries"] == [], "恢复之后，暂停期间的文字也不会补发"
    assert delivery_states(world.gw, dest.destination_id) == []
    after = world.publish(dest, text="恢复之后口述的内容")
    claimed = world.agent(renewed).claim()["deliveries"]
    assert [d["record_id"] for d in claimed] == [after["record_id"]], "恢复只对之后入库的文字生效"


def test_suspension_only_skips_that_destination_the_others_in_the_same_publication_still_get_theirs(world: World) -> None:
    suspended = world.destination("暂停的")
    healthy = world.destination("正常的")
    revoke_credential(world.gw, active_credential_id(world.gw, suspended.destination_id))

    stored = world.publish(suspended, healthy, text="一条文字，两个目标")

    assert [d["destination_id"] for d in stored["deliveries"]] == [healthy.destination_id]
    assert [d["record_id"] for d in world.agent(healthy).claim()["deliveries"]] == [stored["record_id"]]


def test_the_destination_resource_says_whether_a_credential_is_active(world: World) -> None:
    created = httpx.post(
        f"{world.gw.base_url}/v1/destinations",
        trust_env=False,
        json={"schema_version": "1.2", "display_name": "刚创建的", "send_mode": "preview_confirm", "allowed_intents": ["context"]},
        headers={"Authorization": f"Bearer {world.device_token}"},
        timeout=5.0,
    )
    assert created.status_code == 200, created.text
    resource = created.json()
    assert_valid_contract(RESOURCE, resource)
    assert (resource["credential_issued"], resource["credential_active"]) == (False, False)
    destination_id = resource["destination_id"]
    issued = httpx.post(
        f"{world.gw.base_url}/v1/destinations/{destination_id}/credential",
        trust_env=False,
        headers={"Authorization": f"Bearer {world.device_token}"},
        timeout=5.0,
    )
    assert issued.status_code == 200, issued.text
    assert (get_destination(world, destination_id)["credential_issued"], get_destination(world, destination_id)["credential_active"]) == (True, True)

    revoke_credential(world.gw, active_credential_id(world.gw, destination_id))

    suspended = get_destination(world, destination_id)
    assert (suspended["credential_issued"], suspended["credential_active"], suspended["revoked_at"]) == (True, False, None)
    listed = httpx.get(
        f"{world.gw.base_url}/v1/destinations", trust_env=False, headers={"Authorization": f"Bearer {world.device_token}"}, timeout=5.0
    ).json()["destinations"]
    assert [d["credential_active"] for d in listed if d["destination_id"] == destination_id] == [False]
    for item in listed:
        assert_valid_contract(RESOURCE, item)

    again = httpx.post(
        f"{world.gw.base_url}/v1/destinations/{destination_id}/credential",
        trust_env=False,
        headers={"Authorization": f"Bearer {world.device_token}"},
        timeout=5.0,
    )
    assert again.status_code == 200, again.text
    assert get_destination(world, destination_id)["credential_active"] is True, "签发新凭证就恢复"


def test_revoking_the_destination_itself_still_says_no_credential_is_active(world: World) -> None:
    dest = world.destination("整个撤销的")

    world.revoke(dest)

    resource = get_destination(world, dest.destination_id)
    assert resource["revoked_at"] is not None
    assert resource["credential_active"] is False


def test_revoking_a_credential_that_was_already_replaced_does_not_suspend_the_destination(world: World) -> None:
    dest = world.destination("轮换过的")
    old_credential_id = active_credential_id(world.gw, dest.destination_id)
    waiting = world.publish(dest, text="还在排队的")["record_id"]
    renewed = world.rotate_credential(dest)  # 轮换会让旧凭证失效；新凭证是有效的

    result = revoke_credential(world.gw, old_credential_id)

    assert result["already_revoked"] is True
    assert get_destination(world, dest.destination_id)["credential_active"] is True, "还有一条有效凭证：不暂停"
    assert dict(delivery_states(world.gw, dest.destination_id))[waiting] == "PENDING", "什么也没取消"
    assert [d["record_id"] for d in world.agent(renewed).claim()["deliveries"]] == [waiting]


def test_a_destination_that_never_had_a_credential_is_not_suspended_it_keeps_collecting_for_its_first_credential(world: World) -> None:
    created = httpx.post(
        f"{world.gw.base_url}/v1/destinations",
        trust_env=False,
        json={"schema_version": "1.2", "display_name": "还没有凭证的", "send_mode": "auto_send", "allowed_intents": ["context"]},
        headers={"Authorization": f"Bearer {world.device_token}"},
        timeout=5.0,
    )
    destination_id = created.json()["destination_id"]
    submission = make_submission(destination_ids=[destination_id], text="凭证签发之前入库的")
    stored = httpx.put(
        f"{world.gw.base_url}/v1/records/{submission['record_id']}/revisions/1",
        trust_env=False,
        json=submission,
        headers={"Authorization": f"Bearer {world.device_token}"},
        timeout=5.0,
    )
    assert stored.status_code == 200, stored.text
    assert [d["destination_id"] for d in stored.json()["deliveries"]] == [destination_id], "从没签发过凭证的不算暂停：照旧建 Delivery"


def test_a_suspended_destination_with_different_allowed_intents_is_skipped_not_refused(world: World) -> None:
    """OCR-M26/M28：暂停中的 Destination 即使不接收当前 intent，首次入库与追加 Delivery 也应跳过它而不 422 拒收整条请求。"""
    suspended = world.destination("只收备忘但已暂停的", allowed_intents=["context"])
    healthy = world.destination("正常收请求执行的", allowed_intents=["context", "action_request"])
    revoke_credential(world.gw, active_credential_id(world.gw, suspended.destination_id))

    submission = make_submission(
        destination_ids=[suspended.destination_id, healthy.destination_id],
        text="请求执行：暂停的目标应跳过，正常目标照常建",
    )
    submission["publication"]["intent"] = "action_request"
    stored = httpx.put(
        f"{world.gw.base_url}/v1/records/{submission['record_id']}/revisions/1",
        trust_env=False,
        json=submission,
        headers={"Authorization": f"Bearer {world.device_token}"},
        timeout=5.0,
    )
    assert stored.status_code == 200, stored.text
    assert [d["destination_id"] for d in stored.json()["deliveries"]] == [healthy.destination_id]

    preview = world.publish(text="先入库再追加请求执行")
    added = httpx.post(
        f"{world.gw.base_url}/v1/records/{preview['record_id']}/revisions/1/deliveries",
        trust_env=False,
        json={"schema_version": "1.2", "destination_ids": [suspended.destination_id, healthy.destination_id], "intent": "action_request"},
        headers={"Authorization": f"Bearer {world.device_token}"},
        timeout=5.0,
    )
    assert added.status_code == 200, added.text
    assert [d["destination_id"] for d in added.json()["deliveries"]] == [healthy.destination_id]
