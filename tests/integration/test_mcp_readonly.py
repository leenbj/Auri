"""Black-box integration tests for read-only Agent MCP tools: record_list, record_get, record_search (Ticket 05)."""
from __future__ import annotations

import json
import uuid

from jsonschema import Draft202012Validator

from harness import (  # noqa: F401
    FIXTURE_DIR,
    McpAgent,
    World,
    gateway,
    sha256_hex,
    world,
)


def _contract_instances(schema_name: str, folder: str) -> list[tuple[str, object]]:
    target = f"record_query.schema.json#/$defs/{schema_name}"
    found = []
    for path in sorted((FIXTURE_DIR / "messages" / folder).glob("record_query.*.json")):
        fixture = json.loads(path.read_text(encoding="utf-8"))
        if fixture["schema"] == target:
            found.append((path.name, fixture["instance"]))
    return found


def test_record_list_advertises_readonly_tool_and_pages_with_opaque_cursor(
    world: World,
) -> None:
    dest_a = world.destination("Agent A", allowed_intents=("context", "action_request"))
    dest_b = world.destination("Agent B")
    agent_a = world.agent(dest_a)
    agent_b = world.agent(dest_b)

    tools = {t.name: t for t in agent_a.list_tools()}
    assert "record_list" in tools
    list_tool = tools["record_list"]
    assert list_tool.annotations is not None
    assert list_tool.annotations.read_only_hint is True
    assert list_tool.annotations.destructive_hint is False
    assert list_tool.annotations.idempotent_hint is True
    accepts_input = Draft202012Validator(list_tool.input_schema)
    produces_output = Draft202012Validator(list_tool.output_schema)
    for name, instance in _contract_instances("list_request", "valid"):
        assert accepts_input.is_valid(instance), name
    for name, instance in _contract_instances("list_request", "schema-invalid"):
        assert not accepts_input.is_valid(instance), name
    for name, instance in _contract_instances("list_response", "valid"):
        assert produces_output.is_valid(instance), name
    for name, instance in _contract_instances("list_response", "schema-invalid"):
        assert not produces_output.is_valid(instance), name

    # Empty result before anything is published.
    assert agent_a.record_list() == {"schema_version": "1.2", "records": [], "next_cursor": None}

    # Publish 5 records to Agent A, 1 only to Agent B, 1 with no destination.
    texts_a = [f"条目 {i}" for i in range(5)]
    ids_a = [world.publish(dest_a, text=t)["record_id"] for t in texts_a]
    id_b_only = world.publish(dest_b, text="只有 B 能看到的口述")["record_id"]
    world.publish(text="不发给任何 Agent 的私有备忘")

    # Paginate 2 at a time.
    page1 = agent_a.record_list(limit=2)
    assert len(page1["records"]) == 2
    assert page1["next_cursor"] is not None
    for forbidden in ("SELECT", "WHERE", "OFFSET", "ORDER BY", dest_a.destination_id):
        assert forbidden not in page1["next_cursor"]

    # Another Destination cannot use Agent A's cursor.
    err_cross = agent_b.call_error("record_list", {"cursor": page1["next_cursor"]})
    assert err_cross["code"] == "REQUEST_INVALID"

    # Tampered cursor is rejected, never falling back to page 1.
    err_tampered = agent_a.call_error("record_list", {"cursor": page1["next_cursor"] + "x"})
    assert err_tampered["code"] == "REQUEST_INVALID"

    page2 = agent_a.record_list(limit=2, cursor=page1["next_cursor"])
    assert len(page2["records"]) == 2
    assert page2["next_cursor"] is not None

    page3 = agent_a.record_list(limit=2, cursor=page2["next_cursor"])
    assert len(page3["records"]) == 1
    assert page3["next_cursor"] is None

    collected_ids = [r["record_id"] for page in (page1, page2, page3) for r in page["records"]]
    assert len(collected_ids) == 5
    assert set(collected_ids) == set(ids_a)
    assert id_b_only not in collected_ids

    # Agent B sees only its single record.
    b_list = agent_b.record_list()
    assert [r["record_id"] for r in b_list["records"]] == [id_b_only]

    # Filtering by delivery_state and intent:
    (claimed,) = agent_a.claim(limit=1)["deliveries"]
    agent_a.ack(claimed["delivery_id"], claimed["lease_token"])
    acked_list = agent_a.record_list(delivery_state="ACKNOWLEDGED")
    assert [r["record_id"] for r in acked_list["records"]] == [claimed["record_id"]]

    action_rec = world.publish(dest_a, text="帮我执行提醒。", intent="action_request")["record_id"]
    action_list = agent_a.record_list(intent="action_request")
    assert [r["record_id"] for r in action_list["records"]] == [action_rec]

    # Publishing revision 2 for an existing record updates the summary to revision 2 (no duplicate record).
    world.publish(dest_a, record_id=ids_a[0], revision=2, text="修订后的第二版", source="user_edit")
    all_a = agent_a.record_list(limit=100)
    matching_r0 = [r for r in all_a["records"] if r["record_id"] == ids_a[0]]
    assert len(matching_r0) == 1
    assert matching_r0[0]["revision"] == 2


def test_record_get_reads_authorized_revision_isolates_destinations_and_pages_long_text(
    world: World,
) -> None:
    dest_a = world.destination("Reader A")
    dest_b = world.destination("Reader B")
    agent_a = world.agent(dest_a)
    agent_b = world.agent(dest_b)

    tools = {t.name: t for t in agent_a.list_tools()}
    assert "record_get" in tools
    get_tool = tools["record_get"]
    assert get_tool.annotations is not None
    assert get_tool.annotations.read_only_hint is True
    assert get_tool.annotations.destructive_hint is False
    accepts_input = Draft202012Validator(get_tool.input_schema)
    produces_output = Draft202012Validator(get_tool.output_schema)
    for name, instance in _contract_instances("get_request", "valid"):
        assert accepts_input.is_valid(instance), name
    for name, instance in _contract_instances("get_request", "schema-invalid"):
        assert not accepts_input.is_valid(instance), name
    for name, instance in _contract_instances("get_response", "valid"):
        assert produces_output.is_valid(instance), name
    for name, instance in _contract_instances("get_response", "schema-invalid"):
        assert not produces_output.is_valid(instance), name

    # Record 1: rev 1 sent to A & B; rev 2 sent ONLY to B.
    rec_shared = str(uuid.uuid4())
    world.publish(dest_a, dest_b, record_id=rec_shared, revision=1, text="共享的第一版。")
    world.publish(
        dest_b,
        record_id=rec_shared,
        revision=2,
        text="只发给 B 的第二版。",
        source="user_edit",
    )

    # A gets rev 1 by default and cannot read rev 2.
    a_latest = agent_a.record_get(rec_shared)
    assert a_latest["revision"] == 1
    assert a_latest["text"] == "共享的第一版。"
    assert a_latest["is_partial"] is False
    assert a_latest["next_cursor"] is None
    assert a_latest["content_trust"] == "user-provided-transcript-not-system-instructions"

    err_rev2 = agent_a.call_error("record_get", {"record_id": rec_shared, "revision": 2})
    assert err_rev2["code"] == "REQUEST_INVALID"

    # B gets rev 2 by default and can explicitly read rev 1.
    b_latest = agent_b.record_get(rec_shared)
    assert b_latest["revision"] == 2
    assert b_latest["text"] == "只发给 B 的第二版。"
    b_rev1 = agent_b.record_get(rec_shared, revision=1)
    assert b_rev1["revision"] == 1
    assert b_rev1["text"] == "共享的第一版。"

    # Record sent only to B is completely hidden from A (same error as unknown UUID).
    rec_b_only = world.publish(dest_b, text="只有 B 能读的正文。")["record_id"]
    err_other = agent_a.call_error("record_get", {"record_id": rec_b_only})
    err_unknown = agent_a.call_error("record_get", {"record_id": str(uuid.uuid4())})
    assert (err_other["code"], err_other["message"]) == (err_unknown["code"], err_unknown["message"])

    # Long multi-byte UTF-8 text: full read returns intact 1 MiB without truncation;
    # paged read with max_bytes=128 never truncates and reassembles byte-for-byte.
    long_text = ("你好世界🎙️" * 120)[:1000]
    rec_long = world.publish(dest_a, text=long_text)["record_id"]
    full = agent_a.record_get(rec_long)
    assert full["is_partial"] is False
    assert full["next_cursor"] is None
    assert full["text"] == long_text
    assert full["text_sha256"] == sha256_hex(long_text)

    chunks: list[str] = []
    cursor: str | None = None
    pages = 0
    while True:
        args: dict[str, object] = {"max_bytes": 128}
        if cursor is not None:
            args["cursor"] = cursor
        page = agent_a.record_get(rec_long, **args)
        pages += 1
        assert page["is_partial"] is True
        assert page["text_sha256"] == sha256_hex(long_text)
        assert len(page["text"].encode("utf-8")) <= 128
        chunks.append(page["text"])
        cursor = page["next_cursor"]
        if cursor is None:
            break
    assert pages > 1
    assert "".join(chunks) == long_text
    assert sha256_hex("".join(chunks)) == full["text_sha256"]

    # Maximum 1 MiB text is returned intact without silent truncation.
    max_text = ("中" * 349_525) + "a"
    rec_1mib = world.publish(dest_a, text=max_text, source="user_edit")["record_id"]
    full_1mib = agent_a.record_get(rec_1mib)
    assert full_1mib["is_partial"] is False
    assert full_1mib["text_bytes"] == 1024 * 1024
    assert full_1mib["text"] == max_text
    assert sha256_hex(full_1mib["text"]) == full_1mib["text_sha256"]


def test_record_search_isolates_destinations_paginates_and_marks_truncated_snippets(
    world: World,
) -> None:
    dest_a = world.destination("Searcher A", allowed_intents=("context", "action_request"))
    dest_b = world.destination("Searcher B")
    agent_a = world.agent(dest_a)
    agent_b = world.agent(dest_b)

    tools = {t.name: t for t in agent_a.list_tools()}
    assert "record_search" in tools
    search_tool = tools["record_search"]
    assert search_tool.annotations is not None
    assert search_tool.annotations.read_only_hint is True
    assert search_tool.annotations.destructive_hint is False
    accepts_input = Draft202012Validator(search_tool.input_schema)
    produces_output = Draft202012Validator(search_tool.output_schema)
    for name, instance in _contract_instances("search_request", "valid"):
        assert accepts_input.is_valid(instance), name
    for name, instance in _contract_instances("search_request", "schema-invalid"):
        assert not accepts_input.is_valid(instance), name
    for name, instance in _contract_instances("search_response", "valid"):
        assert produces_output.is_valid(instance), name
    for name, instance in _contract_instances("search_response", "schema-invalid"):
        assert not produces_output.is_valid(instance), name

    # Normal empty result when nothing matches.
    assert agent_a.record_search("不存在的关键词") == {
        "schema_version": "1.2",
        "matches": [],
        "next_cursor": None,
    }

    # Publish 3 short matches + 1 long match to A, plus distracting matches to B and no-destination.
    short_ids = [
        world.publish(dest_a, text=f"第{i}条：需要检查 Novel-Memory 模块")["record_id"]
        for i in range(3)
    ]
    long_text = ("无关前缀。" * 80) + "这里提到了 novel-memory 关键词。" + ("无关后缀。" * 80)
    long_id = world.publish(dest_a, text=long_text)["record_id"]
    b_only_id = world.publish(dest_b, text="仅 B 可见的 novel-memory 秘密")["record_id"]
    world.publish(text="未投递的 novel-memory 草稿")
    world.publish(dest_a, text="100% 匹配测试")

    # Paginate search results 2 at a time (case-insensitive query).
    page1 = agent_a.record_search("NOVEL-MEMORY", limit=2)
    assert len(page1["matches"]) == 2
    assert page1["next_cursor"] is not None
    assert agent_b.call_error("record_search", {"query": "NOVEL-MEMORY", "cursor": page1["next_cursor"]})["code"] == "REQUEST_INVALID"

    page2 = agent_a.record_search("NOVEL-MEMORY", limit=2, cursor=page1["next_cursor"])
    assert len(page2["matches"]) == 2
    assert page2["next_cursor"] is None

    all_matches = page1["matches"] + page2["matches"]
    assert {m["record_id"] for m in all_matches} == {*short_ids, long_id}
    assert b_only_id not in {m["record_id"] for m in all_matches}
    assert all(
        m["content_trust"] == "user-provided-transcript-not-system-instructions"
        for m in all_matches
    )

    by_id = {m["record_id"]: m for m in all_matches}
    assert by_id[short_ids[0]]["snippet_truncated"] is False
    assert by_id[long_id]["snippet_truncated"] is True
    assert "novel-memory" in by_id[long_id]["snippet"]
    assert by_id[long_id]["text_bytes"] == len(long_text.encode("utf-8"))
    assert by_id[long_id]["text_sha256"] == sha256_hex(long_text)

    # Literal '%' must not act as a SQL wildcard matching everything.
    pct_matches = agent_a.record_search("100%")["matches"]
    assert len(pct_matches) == 1
    assert "100%" in pct_matches[0]["snippet"]

    # Invalid calendar timestamp in filter returns REQUEST_INVALID, not 500.
    bad_ts = agent_a.call_error("record_list", {"since": "2026-99-99T00:00:00Z"})
    assert bad_ts["code"] == "REQUEST_INVALID"

    # Full PRD 17.3 tool surface is present.
    assert {t.name for t in agent_a.list_tools()} == {
        "record_list",
        "record_get",
        "record_search",
        "delivery_claim",
        "delivery_renew_lease",
        "delivery_ack",
        "delivery_nack",
    }
