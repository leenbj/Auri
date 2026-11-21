"""Ticket 22 评审 B2：给“已经入库”的 Revision 追加 Delivery（预览确认的 Destination 在用户确认之后才有）。

为什么要有这个端点：网关原来只在 (record_id, revision) 第一次入库时创建 Delivery（缺口 G13）。手机为了不让“等用户确认”的预览目的地
拦住自动发送的目的地，必须让 Revision 先入库、之后再为确认了的目的地补 Delivery——对**同一份不可变的 Revision**，并且重复请求不产生第二份。

真实网关进程、真实 SQLite；请求与响应都对着 contracts/ 校验。
"""
from __future__ import annotations

import sqlite3
import uuid
from typing import Any

import httpx

from harness import (
    GatewayProcess,
    World,
    assert_valid_contract,
    gateway,  # noqa: F401
    make_submission,
    world,  # noqa: F401
)

DELIVERIES_ADDED = "revision.schema.json#/$defs/deliveries_added"
DELIVERY_ADDITIONS = "revision.schema.json#/$defs/delivery_additions"


def add_deliveries(
    gateway_process: GatewayProcess,
    token: str,
    record_id: str,
    revision: int | str,
    destination_ids: list[str],
    *,
    intent: str = "context",
    **overrides: Any,
) -> httpx.Response:
    body: dict[str, Any] = {"schema_version": "1.2", "intent": intent, "destination_ids": destination_ids}
    body.update(overrides)
    return httpx.post(
        f"{gateway_process.base_url}/v1/records/{record_id}/revisions/{revision}/deliveries",
        trust_env=False,
        json=body,
        headers={"Authorization": f"Bearer {token}"},
        timeout=5.0,
    )


def delivery_rows(gateway_process: GatewayProcess, record_id: str) -> list[tuple[str, int, str, str]]:
    with sqlite3.connect(gateway_process.data_dir / "gateway.db") as conn:
        return conn.execute(
            "SELECT destination_id, revision, state, intent FROM deliveries WHERE record_id = ? ORDER BY revision, created_at, delivery_id",
            (record_id,),
        ).fetchall()


def test_added_delivery_is_pending_claimable_once_and_leaves_the_stored_revision_untouched(world: World) -> None:
    preview = world.destination("要确认的")
    auto = world.destination("自动的")
    stored = world.publish(auto, text="明天检查小说记忆模块")  # 自动发送的先拿到；预览确认的还没有任何 Delivery
    record_id = stored["record_id"]
    preview_agent = world.agent(preview)
    assert preview_agent.claim()["deliveries"] == [], "确认之前，预览确认的 Destination 什么也领不到"

    response = add_deliveries(world.gw, world.device_token, record_id, 1, [preview.destination_id])

    assert response.status_code == 200, response.text
    added = response.json()
    assert_valid_contract(DELIVERIES_ADDED, added)
    assert (added["record_id"], added["revision"]) == (record_id, 1)
    assert [(d["destination_id"], d["revision"], d["state"]) for d in added["deliveries"]] == [(preview.destination_id, 1, "PENDING")]

    claimed = preview_agent.claim()["deliveries"]
    assert [d["delivery_id"] for d in claimed] == [added["deliveries"][0]["delivery_id"]]
    assert claimed[0]["text"] == "明天检查小说记忆模块", "领到的是同一份正文"
    assert claimed[0]["review_state"] == "not_reviewed"

    # Revision 本身不变：重复提交得到首次入库的那份结果（含当时的 deliveries），不因为后来追加了 Delivery 而变
    again = httpx.put(
        f"{world.gw.base_url}/v1/records/{record_id}/revisions/1",
        trust_env=False,
        json=_resubmission_of(stored),
        headers={"Authorization": f"Bearer {world.device_token}"},
        timeout=5.0,
    )
    assert again.status_code == 200, again.text
    replay = again.json()
    assert replay["duplicate"] is True
    assert (replay["operation_id"], replay["stored_at"], replay["text_sha256"]) == (stored["operation_id"], stored["stored_at"], stored["text_sha256"])
    assert replay["deliveries"] == stored["deliveries"], "首次入库的快照不被追加改写"


def _resubmission_of(stored: dict[str, Any]) -> dict[str, Any]:
    return make_submission(record_id=stored["record_id"], revision=stored["revision"], text="明天检查小说记忆模块", destination_ids=[])


def test_replaying_the_same_addition_creates_no_second_delivery_even_after_the_agent_claimed_it(world: World) -> None:
    preview = world.destination("要确认的")
    record_id = world.publish(text="只入库").get("record_id")

    first = add_deliveries(world.gw, world.device_token, record_id, 1, [preview.destination_id])
    second = add_deliveries(world.gw, world.device_token, record_id, 1, [preview.destination_id])
    assert first.status_code == second.status_code == 200
    assert first.json()["deliveries"] == second.json()["deliveries"], "重复请求回答同一份 Delivery"

    agent = world.agent(preview)
    claimed = agent.claim()["deliveries"]
    assert len(claimed) == 1
    third = add_deliveries(world.gw, world.device_token, record_id, 1, [preview.destination_id])
    assert third.status_code == 200
    assert [(d["delivery_id"], d["state"]) for d in third.json()["deliveries"]] == [(claimed[0]["delivery_id"], "LEASED")], "已被领走的：回答现状，不另建一份"
    assert agent.claim()["deliveries"] == []

    rows = delivery_rows(world.gw, record_id)
    assert [(r[0], r[1]) for r in rows] == [(preview.destination_id, 1)], "网关上每个 (Revision, Destination) 只有一份 Delivery"


def test_one_request_can_add_several_destinations_and_answers_in_request_order(world: World) -> None:
    first, second = world.destination("甲"), world.destination("乙")
    record_id = world.publish(first, text="先给甲").get("record_id")

    response = add_deliveries(world.gw, world.device_token, record_id, 1, [second.destination_id, first.destination_id])

    assert response.status_code == 200, response.text
    deliveries = response.json()["deliveries"]
    assert [d["destination_id"] for d in deliveries] == [second.destination_id, first.destination_id], "按请求里的顺序回答，已有的（甲）也在里面"
    assert [d["state"] for d in deliveries] == ["PENDING", "PENDING"]
    assert len({d["delivery_id"] for d in deliveries}) == 2
    assert len(delivery_rows(world.gw, record_id)) == 2


def test_a_bad_destination_in_the_request_creates_nothing_for_the_good_ones(world: World) -> None:
    good = world.destination("好的")
    record_id = world.publish(text="只入库").get("record_id")

    response = add_deliveries(world.gw, world.device_token, record_id, 1, [good.destination_id, str(uuid.uuid4())])

    assert response.status_code == 422, response.text
    assert_valid_contract("error.schema.json", response.json())
    assert response.json()["code"] == "REQUEST_INVALID"
    assert delivery_rows(world.gw, record_id) == [], "整个请求要么全成、要么一个都不建"


def test_revoked_destination_and_disallowed_intent_are_refused(world: World) -> None:
    revoked = world.destination("已撤销的")
    memo_only = world.destination("只收备忘", allowed_intents=("context",))
    record_id = world.publish(text="只入库").get("record_id")
    world.revoke(revoked)

    on_revoked = add_deliveries(world.gw, world.device_token, record_id, 1, [revoked.destination_id])
    on_intent = add_deliveries(world.gw, world.device_token, record_id, 1, [memo_only.destination_id], intent="action_request")

    for response in (on_revoked, on_intent):
        assert response.status_code == 422, response.text
        assert response.json()["code"] == "REQUEST_INVALID"
    assert delivery_rows(world.gw, record_id) == []


def test_the_delivery_carries_the_intent_of_the_addition_request(world: World) -> None:
    both = world.destination("两种都收", allowed_intents=("context", "action_request"))
    record_id = world.publish(text="入库时标的是备忘", intent="context").get("record_id")

    response = add_deliveries(world.gw, world.device_token, record_id, 1, [both.destination_id], intent="action_request")

    assert response.status_code == 200, response.text
    assert [(r[0], r[3]) for r in delivery_rows(world.gw, record_id)] == [(both.destination_id, "action_request")]
    claimed = world.agent(both).claim()["deliveries"]
    assert claimed[0]["intent"] == "action_request", "Agent 看到的标签是这份 Delivery 自己的"


def test_a_delivery_added_for_an_older_revision_is_born_cancelled_when_a_newer_one_already_reached_that_destination(world: World) -> None:
    newer_target, other = world.destination("收到新版的"), world.destination("没收到新版的")
    record_id = world.publish(text="第一版").get("record_id")
    world.gw.publish(world.device_token, [newer_target.destination_id], record_id=record_id, revision=2, text="第二版")

    response = add_deliveries(world.gw, world.device_token, record_id, 1, [newer_target.destination_id, other.destination_id])

    assert response.status_code == 200, response.text
    states = {d["destination_id"]: d["state"] for d in response.json()["deliveries"]}
    assert states == {newer_target.destination_id: "CANCELLED", other.destination_id: "PENDING"}
    claimed = world.agent(newer_target).claim()["deliveries"]
    assert [d["revision"] for d in claimed] == [2], "收到新版的只会领到新版"


def test_addition_requests_are_refused_for_unknown_deleted_foreign_and_malformed_targets(world: World) -> None:
    destination = world.destination("某个目的地")
    record_id = world.publish(text="会被删除的").get("record_id")
    token = world.device_token

    unknown_record = add_deliveries(world.gw, token, str(uuid.uuid4()), 1, [destination.destination_id])
    unknown_revision = add_deliveries(world.gw, token, record_id, 7, [destination.destination_id])
    for response in (unknown_record, unknown_revision):
        assert response.status_code == 404, response.text
        assert response.json()["code"] == "REQUEST_INVALID"

    other_phone = world.gw.pair_device("另一台手机")["device_token"]
    foreign = add_deliveries(world.gw, other_phone, record_id, 1, [destination.destination_id])
    assert foreign.status_code == 403 and foreign.json()["code"] == "RECORD_NOT_ORIGIN_DEVICE"

    as_agent = add_deliveries(world.gw, destination.credential, record_id, 1, [destination.destination_id])
    assert as_agent.status_code == 401 and as_agent.json()["code"] == "GATEWAY_AUTH_INVALID", "Destination 凭证不能给自己追加投递"
    no_auth = httpx.post(
        f"{world.gw.base_url}/v1/records/{record_id}/revisions/1/deliveries",
        trust_env=False,
        json={"schema_version": "1.2", "intent": "context", "destination_ids": [destination.destination_id]},
        timeout=5.0,
    )
    assert no_auth.status_code == 401

    for revision in ("0", "01", "-1", "x"):
        bad_path = add_deliveries(world.gw, token, record_id, revision, [destination.destination_id])
        assert bad_path.status_code == 422, (revision, bad_path.text)
    not_uuid = add_deliveries(world.gw, token, "not-a-uuid", 1, [destination.destination_id])
    assert not_uuid.status_code == 422

    deleted = httpx.delete(f"{world.gw.base_url}/v1/records/{record_id}", trust_env=False, headers={"Authorization": f"Bearer {token}"}, timeout=5.0)
    assert deleted.status_code == 200
    after_delete = add_deliveries(world.gw, token, record_id, 1, [destination.destination_id])
    assert after_delete.status_code == 410 and after_delete.json()["code"] == "RECORD_DELETED", "墓碑之后不再接受任何追加"
    assert [r for r in delivery_rows(world.gw, record_id) if r[2] != "CANCELLED"] == []


def test_malformed_addition_bodies_are_rejected_by_the_contract(world: World) -> None:
    destination = world.destination("某个目的地")
    record_id = world.publish(text="只入库").get("record_id")
    token = world.device_token
    ok = destination.destination_id

    bodies = {
        "没有目的地": {"destination_ids": []},
        "超过 50 个": {"destination_ids": [str(uuid.uuid4()) for _ in range(51)]},
        "重复的目的地": {"destination_ids": [ok, ok]},
        "自报租户": {"destination_ids": [ok], "tenant_id": "someone-else"},
        "没有标签": {"destination_ids": [ok], "intent": None},
        "没认出来的标签": {"destination_ids": [ok], "intent": "execute"},
        "目的地不是 UUID": {"destination_ids": ["nope"]},
    }
    for name, overrides in bodies.items():
        body = {"schema_version": "1.2", "intent": "context", "destination_ids": [ok], **overrides}
        if body["intent"] is None:
            del body["intent"]
        response = httpx.post(
            f"{world.gw.base_url}/v1/records/{record_id}/revisions/1/deliveries",
            trust_env=False,
            json=body,
            headers={"Authorization": f"Bearer {token}"},
            timeout=5.0,
        )
        assert response.status_code == 422, (name, response.text)
        assert response.json()["code"] == "REQUEST_INVALID", name
    assert delivery_rows(world.gw, record_id) == []


def test_exactly_fifty_destinations_can_be_added_in_one_request(world: World) -> None:
    record_id = world.publish(text="只入库").get("record_id")
    destinations = [world.destination(f"目的地 {i}") for i in range(50)]

    response = add_deliveries(world.gw, world.device_token, record_id, 1, [d.destination_id for d in destinations])

    assert response.status_code == 200, response.text
    assert len(response.json()["deliveries"]) == 50
    assert len(delivery_rows(world.gw, record_id)) == 50


def test_a_valid_request_body_matches_the_contract_it_is_documented_by(world: World) -> None:
    body = {"schema_version": "1.2", "intent": "context", "destination_ids": [str(uuid.uuid4())]}
    assert_valid_contract(DELIVERY_ADDITIONS, body)
