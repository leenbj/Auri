"""Ticket 22 第四轮 2：升级补撤销截止点（迁移 0005）遇到“分不清是轮换还是暂停”的凭证历史，一律补上（失败时关门）。

上一轮的补法把“旧凭证的撤销时间恰好等于新凭证的签发时间”认作轮换（轮换在同一瞬间撤销旧的、签发新的）。可旧库里只有墙上时间：
一次真实的暂停（撤销、过一阵再签发）如果撤销与重新签发的时间戳相同——时钟被调回去、时间精度太粗、数据被人动过——长得和轮换一模一样，
旧补法给它的截止点是空，替换凭证于是读得到撤销之前已确认的文字（`queries.py` 的列表/读取只看 Delivery，已确认的照样交出去）。

现在：只有**能证明是原子轮换**的历史才不补；证明 = 每一条非首凭证都恰好是紧挨着它之前签发的那一条（插入顺序）在同一瞬间被撤销、
同一 Destination 里没有两条凭证签发在同一刻、也没有哪次撤销的瞬间伴随着被取消的投递（后者是新版网关暂停的痕迹：撤销最后一条凭证会取消排着的投递，轮换不会）。
别的、含糊的一律补；读不出区别的单个相等对（数据里没有任何反证）按轮换处理——已有测试要求如此，也是旧库里唯一能区分的形状。

两层：1）用**真实的迁移 SQL**（0001–0004 建表、造行、再执行 0005 那个文件）逐种历史核对补出的截止点；2）真实网关进程上，
拿一次时间戳相同的真实暂停走完“升级—替换凭证读文字”，确认已确认的旧文字读不到。
"""
from __future__ import annotations

import contextlib
import dataclasses
import pathlib
import sqlite3

import pytest

from harness import McpAgent
from test_revocation_cutoff import OLD_TEXT, not_found, upgraded_gateway

MIGRATIONS = pathlib.Path(__file__).resolve().parents[2] / "services" / "gateway" / "auri_gateway" / "migrations"
BACKFILL = MIGRATIONS / "0005_destination_revocation_cutoff.sql"

TENANT = "tenant-1"
OTHER_TENANT = "tenant-2"


@dataclasses.dataclass(frozen=True)
class Credential:
    issued: str
    revoked: str | None = None


def at(day: str) -> str:
    return f"2026-{day}T00:00:00.000000Z"


@dataclasses.dataclass(frozen=True)
class History:
    """一个 Destination 的凭证历史（按插入顺序）与它的投递里被取消的那几条的取消时刻。"""

    credentials: tuple[Credential, ...]
    cancelled_at: tuple[str, ...] = ()
    stored_at: tuple[str, ...] = ()  # 同一 Tenant 里已有文字的入库时间
    expect: str | None = None  # None = 不补；字符串 = 补出的截止点
    slug: str = ""  # 参数化用例的 ASCII 编号（pytest 会把非 ASCII 的编号转义，变异检查要按名字认出是哪一种历史没过）


def legacy_database() -> sqlite3.Connection:
    """迁移 0001–0004 之后的库（迁移 0005 之前）：升级发生在这个形状的库上。"""
    conn = sqlite3.connect(":memory:")
    for path in sorted(MIGRATIONS.glob("*.sql")):
        if path.name < BACKFILL.name:
            conn.executescript(path.read_text(encoding="utf-8"))
    return conn


def add_destination(conn: sqlite3.Connection, tenant: str, destination_id: str, history: History) -> None:
    conn.execute(
        "INSERT INTO destinations (tenant_id, destination_id, display_name, send_mode, allowed_intents_json, created_at, created_by_device_id, credential_issued)"
        " VALUES (?, ?, ?, 'auto_send', '[\"context\"]', ?, 'device-1', 1)",
        (tenant, destination_id, destination_id, at("01-01")),
    )
    for number, cancelled_at in enumerate(history.cancelled_at, start=1):
        conn.execute(
            "INSERT INTO deliveries (delivery_id, tenant_id, record_id, revision, destination_id, state, intent, created_at, cancelled_at)"
            " VALUES (?, ?, ?, 1, ?, 'CANCELLED', 'context', ?, ?)",
            (f"{destination_id}-d{number}", tenant, f"{destination_id}-record-{number}", destination_id, at("01-01"), cancelled_at),
        )


def add_revision(conn: sqlite3.Connection, tenant: str, name: str, stored_at: str) -> None:
    conn.execute(
        "INSERT INTO revisions (tenant_id, record_id, revision, origin_device_id, operation_id, state, stored_at, text, text_sha256, source, language,"
        " review_state, execution_location, segments_json, capture_json, intent, destination_ids_json, superseded_revisions_json, deliveries_json)"
        " VALUES (?, ?, 1, 'device-1', ?, 'stored', ?, 't', 'x', 'asr', 'zh', 'unreviewed', 'local', '[]', '{}', 'context', '[]', '[]', '[]')",
        (tenant, f"{name}-record", f"{name}-operation", stored_at),
    )


def backfilled(histories: dict[str, History], *, other_tenant_stored: tuple[str, ...] = ()) -> dict[str, str | None]:
    """造库、执行真实的迁移 0005，返回每个 Destination 补出的截止点。"""
    with contextlib.closing(legacy_database()) as conn:
        for destination_id, history in histories.items():
            add_destination(conn, TENANT, destination_id, history)
            for number, stored_at in enumerate(history.stored_at, start=1):
                add_revision(conn, TENANT, f"{destination_id}-text-{number}", stored_at)
        # 凭证按“每个 Destination 的第 n 条”一轮一轮地插：不同 Destination 的凭证在 rowid 上交错，判断必须只看自己的历史
        for round_number in range(max(len(history.credentials) for history in histories.values())):
            for destination_id, history in histories.items():
                if round_number < len(history.credentials):
                    credential = history.credentials[round_number]
                    conn.execute(
                        "INSERT INTO destination_credentials (credential_id, tenant_id, destination_id, token_hash, issued_by_device_id, issued_at, revoked_at)"
                        " VALUES (?, ?, ?, ?, 'device-1', ?, ?)",
                        (f"{destination_id}-c{round_number + 1}", TENANT, destination_id, f"{destination_id}-hash-{round_number + 1}", credential.issued, credential.revoked),
                    )
        for number, stored_at in enumerate(other_tenant_stored, start=1):
            add_revision(conn, OTHER_TENANT, f"other-{number}", stored_at)
        conn.executescript(BACKFILL.read_text(encoding="utf-8"))
        return dict(conn.execute("SELECT destination_id, revocation_cutoff FROM destinations"))


# 每一种历史：名字说的是它的形状，括号里是旧补法与现在补法的分歧（如果有）
HISTORIES: dict[str, History] = {
    "从没签发第二条（只有一条有效凭证）": History((Credential(at("01-01")),), slug="single-credential"),
    "只撤销过一条、还没恢复（现在是暂停，恢复时由网关补）": History((Credential(at("01-01"), at("01-02")),), slug="revoked-not-resumed"),
    "撤销之后隔了一阵才签发替换（真实的暂停）": History(
        (Credential(at("01-01"), at("01-02")), Credential(at("01-03"))), expect=at("01-03"), slug="real-suspension",
    ),
    "只轮换一次：旧凭证在新凭证签发的同一瞬间被撤销（数据里没有反证，不补）": History(
        (Credential(at("01-01"), at("01-03")), Credential(at("01-03"))), slug="single-rotation",
    ),
    "连续轮换三次：每一环都是同一瞬间，各环时刻不同": History(
        (Credential(at("01-01"), at("01-03")), Credential(at("01-03"), at("01-05")), Credential(at("01-05"))), slug="three-rotations",
    ),
    "时钟被调回去之后只轮换：旧凭证的撤销早于它自己的签发，新凭证与撤销同一瞬间": History(
        (Credential(at("02-01"), at("01-20")), Credential(at("01-20"))), slug="rotation-after-rollback",
    ),
    # ---- 旧补法漏掉、现在补的（第四轮 2）----
    "【撤销与重新签发时间戳相同、但那一瞬间有被取消的投递】这是新版网关暂停的痕迹，不是轮换": History(
        (Credential(at("01-01"), at("01-03")), Credential(at("01-03"))), cancelled_at=(at("01-03"),), expect=at("01-03"), slug="cancel-trace",
    ),
    "【同一 Destination 里两条凭证签发在同一刻】先后不明": History(
        (Credential(at("01-01"), at("01-03")), Credential(at("01-03"), at("01-03")), Credential(at("01-03"))), expect=at("01-03"), slug="same-issue-instant",
    ),
    "【新凭证的签发时间与更早一条的撤销时间相同，可它的上一条是另一个时刻被撤销的】巧合的相等不能当轮换": History(
        (Credential(at("01-01"), at("01-03")), Credential(at("01-03"), at("01-04")), Credential(at("01-03"))), expect=at("01-04"), slug="coincidence-and-shared-issue",
    ),
    "【每条凭证的签发时刻都碰巧等于另一条凭证的撤销时刻，可紧挨着它之前的那条不是那一刻撤销的】只问“有没有哪条在那一刻被撤销”认不出这种巧合": History(
        (Credential(at("01-01"), at("01-05")), Credential(at("01-02"), at("01-03")), Credential(at("01-05"), at("01-02"))), expect=at("01-05"), slug="coincidence-everywhere",
    ),
    "【新凭证签发时，紧挨着它之前的那条在数据里还没被撤销】轮换一定会撤销，数据对不上": History(
        (Credential(at("01-01")), Credential(at("01-03"))), expect=at("01-03"), slug="predecessor-not-revoked",
    ),
    # 与已有的“时钟回拨后的暂停”用例同形，但没有被取消的投递：那条用例的数据里有取消的痕迹，规则 (d) 也会补，单独认不出规则 (b) 是否还在
    "时钟被调回去之后补发的替换凭证：签发时间早于旧凭证的签发与撤销时间，数据里没有任何取消的痕迹（真实的暂停）": History(
        (Credential(at("02-01"), at("02-02")), Credential(at("01-15"))), expect=at("02-02"), slug="suspension-after-rollback",
    ),
    # ---- 反证只在“撤销之后又签发”时才算：不能让普通的取消把正常的轮换都补成暂停 ----
    "轮换，库里有一条与任何撤销时刻都无关的已取消投递（例如删除了一条 Record）": History(
        (Credential(at("01-01"), at("01-03")), Credential(at("01-03"))), cancelled_at=(at("01-02"),), slug="rotation-unrelated-cancel",
    ),
    "最后一条凭证被撤销、当场取消了排着的投递，之后没有新凭证（正在暂停，不是已结束的暂停）": History(
        (Credential(at("01-01"), at("01-03")),), cancelled_at=(at("01-03"),), slug="cancel-without-later-credential",
    ),
}


@pytest.mark.parametrize("name", [pytest.param(name, id=history.slug) for name, history in HISTORIES.items()])
def test_the_backfill_only_leaves_a_destination_uncut_when_its_history_proves_an_atomic_rotation(name: str) -> None:
    history = HISTORIES[name]

    cutoffs = backfilled({"dest": history})

    assert cutoffs["dest"] == history.expect, name


def test_the_cutoff_of_an_ambiguous_history_covers_every_text_already_stored_in_the_tenant_and_nothing_from_other_tenants() -> None:
    ambiguous = History(
        (Credential(at("01-01"), at("01-03")), Credential(at("01-03"))), cancelled_at=(at("01-03"),),
        stored_at=(at("01-02"), "2026-06-01T00:00:00.000000Z"),  # 时钟跑在前面时入库的，比所有凭证时刻都晚
    )

    cutoffs = backfilled({"dest": ambiguous}, other_tenant_stored=("2099-01-01T00:00:00.000000Z",))

    assert cutoffs["dest"] == "2026-06-01T00:00:00.000000Z"


def test_each_destination_is_judged_by_its_own_history_even_when_the_credentials_are_interleaved() -> None:
    cutoffs = backfilled({"suspended": HISTORIES["撤销之后隔了一阵才签发替换（真实的暂停）"], "rotated": HISTORIES["只轮换一次：旧凭证在新凭证签发的同一瞬间被撤销（数据里没有反证，不补）"]})

    assert cutoffs == {"suspended": at("01-03"), "rotated": None}


def test_a_cancelled_delivery_of_another_destination_does_not_make_a_rotation_look_like_a_suspension() -> None:
    rotated = HISTORIES["只轮换一次：旧凭证在新凭证签发的同一瞬间被撤销（数据里没有反证，不补）"]
    suspended = History((Credential(at("01-01"), at("01-03")), Credential(at("01-04"))), cancelled_at=(at("01-03"),), expect=at("01-04"))

    cutoffs = backfilled({"rotated": rotated, "suspended": suspended})

    assert cutoffs == {"rotated": None, "suspended": at("01-04")}


# ---- 真实网关进程：时间戳相同的真实暂停，升级之后替换凭证读不到已确认的旧文字 ----


def test_an_upgrade_hides_the_acknowledged_texts_of_a_suspension_whose_revoke_and_reissue_share_a_timestamp(tmp_path: pathlib.Path) -> None:
    """第四轮 2：旧凭证 01-03 被撤销（暂停，排着的那条当场被取消），替换凭证也签发在 01-03：同一个时间戳。
    旧补法把它当轮换，截止点是空，替换凭证的列表与读取交出已确认的那条旧文字。"""

    def seed(gateway, token):
        dest = gateway.create_agent_destination(token, "时间戳相同的暂停")
        acked = gateway.publish(token, [dest.destination_id], text=f"{OLD_TEXT}（已确认的）")
        waiting = gateway.publish(token, [dest.destination_id], text=f"{OLD_TEXT}（暂停时还排着的）")
        old_agent = McpAgent(gateway.base_url, dest.credential)
        first = old_agent.claim(limit=1)["deliveries"][0]
        old_agent.ack(first["delivery_id"], first["lease_token"])
        return dest, acked, waiting

    def rewrite(conn, add_credential, seeded):
        dest, acked, waiting = seeded
        conn.execute("UPDATE destination_credentials SET issued_at = '2025-12-31T00:00:00.000000Z', revoked_at = ? WHERE destination_id = ?", (at("01-03"), dest.destination_id))
        conn.execute("UPDATE revisions SET stored_at = ? WHERE record_id IN (?, ?)", (at("01-02"), acked["record_id"], waiting["record_id"]))
        conn.execute(
            "UPDATE deliveries SET state = 'CANCELLED', cancelled_at = ?, lease_token_hash = NULL, lease_expires_at = NULL"
            " WHERE destination_id = ? AND state IN ('PENDING', 'LEASED')",
            (at("01-03"), dest.destination_id),
        )
        add_credential(dest.destination_id, "replacement-with-the-same-timestamp", at("01-03"))

    with upgraded_gateway(tmp_path, seed, rewrite) as (gateway, _token, (_dest, acked, waiting)):
        agent = McpAgent(gateway.base_url, "replacement-with-the-same-timestamp")
        assert agent.record_list()["records"] == [], "已确认的旧文字不能出现在替换凭证的列表里"
        assert agent.record_search(OLD_TEXT)["matches"] == []
        not_found(agent, "record_get", {"record_id": acked["record_id"]})
        not_found(agent, "record_get", {"record_id": waiting["record_id"]})
        assert agent.claim()["deliveries"] == []


# ---- 第四轮 F6：迁移 0006 把截止点迁到持久单调的入库顺序（store_seq） ----


def test_migration_0006_assigns_monotonic_store_seq_and_converts_existing_cutoffs_conservatively() -> None:
    """在真实 0001–0005 建出的库上执行 0006：已有 Revision 按插入顺序拿 1..N，已有截止点的 Destination 保守迁到本 Tenant 的最大序号，
    没设截止点（只轮换）的仍为 NULL，没有文字的 Tenant 迁到 0，跨 Tenant 不串号。"""
    monotonic_migration = MIGRATIONS / "0006_monotonic_revocation_cutoff.sql"
    assert monotonic_migration.exists(), "缺少迁移 0006_monotonic_revocation_cutoff.sql"

    with contextlib.closing(legacy_database()) as conn:
        add_destination(conn, TENANT, "suspended", HISTORIES["撤销之后隔了一阵才签发替换（真实的暂停）"])
        add_destination(conn, TENANT, "rotated", HISTORIES["只轮换一次：旧凭证在新凭证签发的同一瞬间被撤销（数据里没有反证，不补）"])
        add_destination(conn, OTHER_TENANT, "empty-tenant-suspended", HISTORIES["撤销之后隔了一阵才签发替换（真实的暂停）"])
        add_revision(conn, TENANT, "t1-first", "2099-01-01T00:00:00.000000Z")
        add_revision(conn, TENANT, "t1-second", at("01-02"))
        add_revision(conn, "tenant-3", "t3-later", at("01-03"))
        for dest_id, tenant, creds in (
            ("suspended", TENANT, HISTORIES["撤销之后隔了一阵才签发替换（真实的暂停）"].credentials),
            ("rotated", TENANT, HISTORIES["只轮换一次：旧凭证在新凭证签发的同一瞬间被撤销（数据里没有反证，不补）"].credentials),
            ("empty-tenant-suspended", OTHER_TENANT, HISTORIES["撤销之后隔了一阵才签发替换（真实的暂停）"].credentials),
        ):
            for idx, cred in enumerate(creds, start=1):
                conn.execute(
                    "INSERT INTO destination_credentials (credential_id, tenant_id, destination_id, token_hash, issued_by_device_id, issued_at, revoked_at)"
                    " VALUES (?, ?, ?, ?, 'device-1', ?, ?)",
                    (f"{dest_id}-c{idx}", tenant, dest_id, f"{dest_id}-h{idx}", cred.issued, cred.revoked),
                )
        conn.executescript(BACKFILL.read_text(encoding="utf-8"))
        conn.executescript(monotonic_migration.read_text(encoding="utf-8"))

        seqs = dict(conn.execute("SELECT record_id, store_seq FROM revisions").fetchall())
        assert seqs == {"t1-first-record": 1, "t1-second-record": 2, "t3-later-record": 3}
        last_seq = conn.execute("SELECT last_seq FROM revision_sequence WHERE id = 1").fetchone()[0]
        assert last_seq == 3
        cutoffs = dict(conn.execute("SELECT destination_id, revocation_cutoff_seq FROM destinations").fetchall())
        assert cutoffs == {
            "suspended": 2,  # 本 Tenant 的最大序号（不含 tenant-3 的 3）
            "rotated": None,  # 只轮换不设截止点
            "empty-tenant-suspended": 0,  # 本 Tenant 尚无文字：0，之后新入库的序号 >= 4 > 0
        }


def test_an_upgrade_from_0005_with_future_stored_at_hides_old_and_delivers_new(tmp_path: pathlib.Path) -> None:
    """真实网关进程从 0001–0005（含 2099 时间戳的旧文字与已补的墙上时间截止点）升到 0006：
    升级前的旧文字（含 2099 那条）全部不可见；升级之后新入库的文字（哪怕当前时间早于 2099）照常建 Delivery 并可领取。"""
    from harness import GatewayProcess

    legacy_migrations = tmp_path / "migrations-0005"
    legacy_migrations.mkdir()
    for path in sorted(MIGRATIONS.glob("*.sql")):
        if path.name <= BACKFILL.name:
            (legacy_migrations / path.name).write_text(path.read_text(encoding="utf-8"), encoding="utf-8")

    data_dir = tmp_path / "gw-0005-to-0006"
    old_gw = GatewayProcess(data_dir, env_overrides={"AURI_GATEWAY_MIGRATIONS_DIR": str(legacy_migrations)})
    old_gw.start()
    try:
        token = old_gw.pair_device("Phone")["device_token"]
        dest = old_gw.create_agent_destination(token, "旧版升级的")
        ahead = old_gw.publish(token, [dest.destination_id], text=f"{OLD_TEXT}（旧版 2099）")
        with contextlib.closing(sqlite3.connect(data_dir / "gateway.db")) as conn:
            conn.execute("UPDATE revisions SET stored_at = '2099-01-01T00:00:00.000000Z' WHERE record_id = ?", (ahead["record_id"],))
            conn.execute("UPDATE destinations SET revocation_cutoff = '2099-01-01T00:00:00.000000Z' WHERE destination_id = ?", (dest.destination_id,))
            conn.commit()
    finally:
        old_gw.stop()

    upgraded = GatewayProcess(data_dir)
    upgraded.start()
    try:
        agent = McpAgent(upgraded.base_url, dest.credential)
        assert agent.record_list()["records"] == []
        not_found(agent, "record_get", {"record_id": ahead["record_id"]})
        assert agent.claim()["deliveries"] == []

        fresh = upgraded.publish(token, [dest.destination_id], text="升级 0006 之后新入库的")
        assert [d["destination_id"] for d in fresh["deliveries"]] == [dest.destination_id]
        assert [d["record_id"] for d in agent.claim()["deliveries"]] == [fresh["record_id"]]
        assert agent.record_get(fresh["record_id"])["text"] == "升级 0006 之后新入库的"
    finally:
        upgraded.stop()
