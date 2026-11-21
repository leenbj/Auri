"""Ticket 22 再评审 R1：撤销凭证之后签发的替换凭证，在每一条 Agent 读取路径上都看不到撤销之前的文字。

以前（评审 B3 之后）：撤销最后一条凭证会取消排着的 Delivery，替换凭证领不到它们；但 `record_list` / `record_search` /
`record_get` 只按“这个 Destination 有没有 Delivery”查，已取消的、已确认的旧 Delivery 连同全文照样交给替换凭证，
`delivery_ack` 也认得旧租约。撤销往往就是因为旧凭证的持有者不再可信，替换凭证的持有者不该读到它之前的任何文字。
现在：Destination 记一个“撤销截止点”（暂停结束、签发替换凭证的那一刻；与库里已有文字的最晚入库时间取较大者，
所以网关时钟回拨也不会漏），入库时间不晚于它的文字对这个 Destination 的 Agent 一律不可见——列表、搜索、读取、领取、
续租、确认、拒收全部一样。没有暂停的轮换不设截止点（排队的文字照常交给新凭证）。
真实网关进程；Agent 一侧用官方 MCP 客户端，带的是替换凭证。
"""
from __future__ import annotations

import contextlib
import hashlib
import pathlib
import sqlite3

from harness import GatewayProcess, McpAgent, World, gateway, world  # noqa: F401
from test_credential_suspension import active_credential_id, add_deliveries, delivery_states, revoke_credential

OLD_TEXT = "撤销之前口述的"


def suspend_and_reissue(world: World, dest):
    """CLI 撤销最后一条凭证（Destination 暂停），手机再签发替换凭证。"""
    revoke_credential(world.gw, active_credential_id(world.gw, dest.destination_id))
    return world.rotate_credential(dest)


def not_found(agent, tool: str, arguments: dict) -> dict:
    error = agent.call_error(tool, arguments)
    assert error["code"] == "REQUEST_INVALID", error
    assert OLD_TEXT not in str(error), "拒绝的回答里也不能带出旧文字"
    return error


def test_the_replacement_credential_reads_nothing_from_before_the_revocation_on_any_path(world: World) -> None:
    dest = world.destination("被撤销凭证的")
    acked = world.publish(dest, text=f"{OLD_TEXT}（已确认的）")
    leased = world.publish(dest, text=f"{OLD_TEXT}（已领取的）")
    pending = world.publish(dest, text=f"{OLD_TEXT}（还没被领取的）")
    old_agent = world.agent(dest)
    first, second = old_agent.claim(limit=2)["deliveries"]
    old_agent.ack(first["delivery_id"], first["lease_token"])  # 第一条确认；第二条留在租约里；第三条还是 PENDING

    agent = world.agent(suspend_and_reissue(world, dest))

    assert agent.claim()["deliveries"] == [], "领取：撤销之前排着的一条也领不到"
    assert agent.record_list()["records"] == [], "列表：已取消、已确认的旧 Delivery 都不显示"
    assert agent.record_search(OLD_TEXT)["matches"] == [], "搜索：搜得到全文片段就是泄露"
    for stored in (acked, leased, pending):
        not_found(agent, "record_get", {"record_id": stored["record_id"]})
        not_found(agent, "record_get", {"record_id": stored["record_id"], "revision": 1})
    # 确认 / 续租 / 拒收：旧租约在替换凭证面前就是“没有这个 Delivery”，连已确认那条的重复确认也不再回答
    for delivery in (first, second):
        arguments = {"delivery_id": delivery["delivery_id"], "lease_token": delivery["lease_token"]}
        not_found(agent, "delivery_ack", arguments)
        not_found(agent, "delivery_renew_lease", arguments)
        not_found(agent, "delivery_nack", {**arguments, "reason": "测试", "retryable": True})


def test_text_stored_after_the_replacement_credential_is_visible_on_every_path(world: World) -> None:
    dest = world.destination("恢复之后的")
    world.publish(dest, text=f"{OLD_TEXT}（旧的）")
    agent = world.agent(suspend_and_reissue(world, dest))
    fresh = world.publish(dest, text="恢复之后口述的内容")

    listed = agent.record_list()["records"]
    assert [row["record_id"] for row in listed] == [fresh["record_id"]]
    assert [m["record_id"] for m in agent.record_search("口述")["matches"]] == [fresh["record_id"]]
    assert agent.record_get(fresh["record_id"])["text"] == "恢复之后口述的内容"
    claimed = agent.claim()["deliveries"]
    assert [d["record_id"] for d in claimed] == [fresh["record_id"]]
    assert agent.ack(claimed[0]["delivery_id"], claimed[0]["lease_token"])["state"] == "ACKNOWLEDGED"


def test_rotating_without_a_suspension_hides_nothing(world: World) -> None:
    dest = world.destination("只轮换的")
    waiting = world.publish(dest, text=f"{OLD_TEXT}（轮换时还在排队的）")
    agent = world.agent(world.rotate_credential(dest))  # 轮换总留着一条有效凭证：不是暂停，没有截止点

    assert [row["record_id"] for row in agent.record_list()["records"]] == [waiting["record_id"]]
    assert [m["record_id"] for m in agent.record_search(OLD_TEXT)["matches"]] == [waiting["record_id"]]
    assert agent.record_get(waiting["record_id"])["text"].startswith(OLD_TEXT)
    assert [d["record_id"] for d in agent.claim()["deliveries"]] == [waiting["record_id"]]


def test_each_new_suspension_moves_the_cutoff_forward(world: World) -> None:
    dest = world.destination("暂停两次的")
    agent = world.agent(suspend_and_reissue(world, dest))
    between = world.publish(dest, text="两次暂停之间口述的")
    assert [row["record_id"] for row in agent.record_list()["records"]] == [between["record_id"]]

    second = world.agent(suspend_and_reissue(world, dest))
    after = world.publish(dest, text="第二次恢复之后口述的")

    assert [row["record_id"] for row in second.record_list()["records"]] == [after["record_id"]]
    not_found(second, "record_get", {"record_id": between["record_id"]})


def test_a_text_stored_while_the_gateway_clock_ran_ahead_is_still_older_than_the_cutoff(world: World) -> None:
    dest = world.destination("时钟回拨的")
    ahead = world.publish(dest, text=f"{OLD_TEXT}（入库时网关时钟跑在前面）")
    with sqlite3.connect(world.gw.data_dir / "gateway.db") as conn:  # 时钟曾经快过，后来被校正回来
        conn.execute("UPDATE revisions SET stored_at = '2099-01-01T00:00:00.000000Z' WHERE record_id = ?", (ahead["record_id"],))

    agent = world.agent(suspend_and_reissue(world, dest))

    assert agent.record_list()["records"] == []
    not_found(agent, "record_get", {"record_id": ahead["record_id"]})
    assert agent.claim()["deliveries"] == []


def test_a_suspension_that_ended_before_the_upgrade_gets_its_cutoff_from_the_credential_history(tmp_path: pathlib.Path) -> None:
    """旧库里已经发生过的暂停：升级（迁移 0005）时按凭证历史补上截止点；只是轮换的 Destination 不设。

    先用当前网关造数据，再把库退回“迁移 0005 之前”的样子（去掉那一列、删掉迁移记录），重启时迁移会重新执行。
    """
    data_dir = tmp_path / "upgrade-gw"
    first = GatewayProcess(data_dir)
    first.start()
    try:
        token = first.pair_device("Legacy Phone")["device_token"]
        suspended = first.create_agent_destination(token, "暂停过的")
        rotated = first.create_agent_destination(token, "只轮换过的")
        before = first.publish(token, [suspended.destination_id], text=f"{OLD_TEXT}（暂停之前入库）")
        after = first.publish(token, [suspended.destination_id], text="暂停结束之后入库")
        kept = first.publish(token, [rotated.destination_id], text="轮换之前入库、一直排着的")
    finally:
        first.stop()

    def sha(value: str) -> str:
        return hashlib.sha256(value.encode("utf-8")).hexdigest()

    with contextlib.closing(sqlite3.connect(data_dir / "gateway.db")) as conn:
        tenant_id, device_id = conn.execute("SELECT tenant_id, device_id FROM devices").fetchone()

        def add_credential(destination_id: str, token_value: str, issued_at: str) -> None:
            conn.execute(
                "INSERT INTO destination_credentials (credential_id, tenant_id, destination_id, token_hash, issued_by_device_id, issued_at)"
                " VALUES (?, ?, ?, ?, ?, ?)",
                (f"cred-{token_value}", tenant_id, destination_id, sha(token_value), device_id, issued_at),
            )

        conn.execute("ALTER TABLE destinations DROP COLUMN revocation_cutoff")
        conn.execute("DELETE FROM schema_migrations WHERE version = 5")
        conn.execute("UPDATE destination_credentials SET issued_at = '2025-12-31T00:00:00.000000Z'")  # 原凭证签发在一切之前
        # 暂停：旧凭证 01-02 被撤销，替换凭证 01-03 才签发；暂停之前的文字入库于 01-01，之后那条留在真实时间
        conn.execute("UPDATE revisions SET stored_at = '2026-01-01T00:00:00.000000Z' WHERE record_id = ?", (before["record_id"],))
        conn.execute("UPDATE destination_credentials SET revoked_at = '2026-01-02T00:00:00.000000Z' WHERE destination_id = ?", (suspended.destination_id,))
        add_credential(suspended.destination_id, "replacement-after-suspension", "2026-01-03T00:00:00.000000Z")
        # 轮换：旧凭证在新凭证签发的同一刻被撤销，不是暂停
        conn.execute("UPDATE destination_credentials SET revoked_at = '2026-01-03T00:00:00.000000Z' WHERE destination_id = ?", (rotated.destination_id,))
        add_credential(rotated.destination_id, "replacement-after-rotation", "2026-01-03T00:00:00.000000Z")
        conn.commit()

    upgraded = GatewayProcess(data_dir)
    upgraded.start()
    try:
        agent = McpAgent(upgraded.base_url, "replacement-after-suspension")
        assert [row["record_id"] for row in agent.record_list()["records"]] == [after["record_id"]]
        not_found(agent, "record_get", {"record_id": before["record_id"]})
        assert [d["record_id"] for d in agent.claim()["deliveries"]] == [after["record_id"]]
        rotation_agent = McpAgent(upgraded.base_url, "replacement-after-rotation")
        assert [d["record_id"] for d in rotation_agent.claim()["deliveries"]] == [kept["record_id"]]
    finally:
        upgraded.stop()


# ---- R2：延迟到达的“追加 Delivery”不能把撤销之前入库的文字送给替换凭证 ----


def test_an_addition_before_any_suspension_still_creates_the_delivery(world: World) -> None:
    dest = world.destination("没暂停过的")
    stored = world.publish(text="先入库、预览确认之后才追加")  # 入库时不点名任何目标（预览确认的做法）

    added = add_deliveries(world, stored["record_id"], [dest.destination_id])

    assert added.status_code == 200, added.text
    assert [d["destination_id"] for d in added.json()["deliveries"]] == [dest.destination_id]
    assert [d["record_id"] for d in world.agent(dest).claim()["deliveries"]] == [stored["record_id"]]


def test_an_addition_during_the_suspension_creates_nothing(world: World) -> None:
    dest = world.destination("暂停中的")
    stored = world.publish(text="暂停之前入库、暂停期间才追加")
    revoke_credential(world.gw, active_credential_id(world.gw, dest.destination_id))

    added = add_deliveries(world, stored["record_id"], [dest.destination_id])

    assert added.status_code == 200, added.text
    assert added.json()["deliveries"] == []
    assert delivery_states(world.gw, dest.destination_id) == []


def test_a_delayed_addition_after_the_replacement_credential_creates_nothing_for_an_older_revision(world: World) -> None:
    dest = world.destination("恢复之后才收到追加的")
    healthy = world.destination("没暂停的")
    old = world.publish(text=f"{OLD_TEXT}（撤销之前入库，用户迟迟没点确认）")
    agent = world.agent(suspend_and_reissue(world, dest))

    added = add_deliveries(world, old["record_id"], [dest.destination_id, healthy.destination_id])

    assert added.status_code == 200, added.text
    assert [d["destination_id"] for d in added.json()["deliveries"]] == [healthy.destination_id], "只跳过截止点之前的那个，别的目标照常建"
    assert delivery_states(world.gw, dest.destination_id) == [], "没有为它建 PENDING：状态页不会把一条永远领不到的文字说成已入库"
    assert agent.claim()["deliveries"] == []
    assert agent.record_list()["records"] == []
    assert [d["record_id"] for d in world.agent(healthy).claim()["deliveries"]] == [old["record_id"]]


def test_an_addition_for_a_text_stored_after_the_replacement_credential_works(world: World) -> None:
    dest = world.destination("恢复之后的")
    agent = world.agent(suspend_and_reissue(world, dest))
    fresh = world.publish(text="恢复之后入库、再追加")

    added = add_deliveries(world, fresh["record_id"], [dest.destination_id])

    assert added.status_code == 200, added.text
    assert [d["destination_id"] for d in added.json()["deliveries"]] == [dest.destination_id]
    assert [d["record_id"] for d in agent.claim()["deliveries"]] == [fresh["record_id"]]


def test_a_first_store_that_is_not_later_than_the_cutoff_creates_no_delivery_either(world: World) -> None:
    dest = world.destination("时钟回拨的")
    ahead = world.publish(dest, text=f"{OLD_TEXT}（入库时网关时钟跑在前面）")
    with sqlite3.connect(world.gw.data_dir / "gateway.db") as conn:
        conn.execute("UPDATE revisions SET stored_at = '2099-01-01T00:00:00.000000Z' WHERE record_id = ?", (ahead["record_id"],))
    agent = world.agent(suspend_and_reissue(world, dest))

    stored = world.publish(dest, text="时钟校正之后入库的")

    assert stored["deliveries"] == [], "它的入库时间也不晚于截止点：看不见，所以不建一条永远领不到的 PENDING"
    assert agent.claim()["deliveries"] == []
