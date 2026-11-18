"""变异表自身的离线检查：表不能悄悄过期。

mutation_check.py 里每条变异都要“恰好改一处生产代码”。生产代码一重构，原文找不到了，这条变异就会变成“什么都没改、
测试当然全绿”的假象。这里逐条核对：原文恰好出现一次、改动确实改了东西、预期变红的测试在源码里真的存在、
没有任何变异会碰到测试文件，并且“应用 → 还原”之后文件字节级不变。
"""
import os
import pathlib
import re
import sys

import pytest

import mutation_check as mc

REPO = mc.REPO


@pytest.mark.parametrize("mutation", mc.MUTATIONS, ids=lambda m: m.id)
def test_mutation_edits_apply_exactly_once(mutation):
    for edit in mutation.edits:
        text = (REPO / edit.path).read_text(encoding="utf-8")
        assert text.count(edit.old) == 1, f"{mutation.id}: {edit.path} 里原文出现 {text.count(edit.old)} 次"
        assert edit.old != edit.new
        assert "/src/main/" in edit.path, "变异只能碰生产代码，不能碰测试"


@pytest.mark.parametrize("mutation", mc.MUTATIONS, ids=lambda m: m.id)
def test_every_expected_failing_test_exists_in_the_sources(mutation):
    assert mutation.must_fail, "一条变异必须指明哪些测试会因此变红"
    sources = "\n".join(p.read_text(encoding="utf-8") for p in (REPO / "apps" / "android").rglob("*.kt") if "/src/" in str(p) and "test" in str(p).lower())
    for needle in mutation.must_fail:
        assert re.search(re.escape(needle), sources), f"{mutation.id}: 找不到预期变红的测试 {needle!r}"


def test_mutation_ids_are_unique_and_documented():
    ids = [m.id for m in mc.MUTATIONS]
    assert len(ids) == len(set(ids))
    assert all(m.rule.strip() for m in mc.MUTATIONS)


@pytest.mark.parametrize("mutation", mc.MUTATIONS, ids=lambda m: m.id)
def test_apply_then_restore_leaves_files_byte_identical(mutation):
    paths = {REPO / e.path for e in mutation.edits}
    before = {p: p.read_bytes() for p in paths}
    originals = mc.apply_edits(mutation)
    try:
        assert any(p.read_bytes() != before[p] for p in paths), "应用变异后文件没有任何变化"
    finally:
        mc.restore(originals)
    assert {p: p.read_bytes() for p in paths} == before


def test_apply_refuses_ambiguous_or_missing_original_and_restores(tmp_path, monkeypatch):
    target = tmp_path / "apps" / "x" / "src" / "main" / "A.kt"
    target.parent.mkdir(parents=True)
    target.write_text("a a b", encoding="utf-8")
    monkeypatch.setattr(mc, "REPO", tmp_path)
    path = "apps/x/src/main/A.kt"
    with pytest.raises(ValueError):
        mc.apply_edits(mc.Mutation("amb", "r", (mc.Edit(path, "a", "z"),), (), "", ("t",)))
    with pytest.raises(ValueError):
        mc.apply_edits(mc.Mutation("missing", "r", (mc.Edit(path, "q", "z"),), (), "", ("t",)))
    # 第二处编辑失败时，第一处也必须被还原
    with pytest.raises(ValueError):
        mc.apply_edits(mc.Mutation("half", "r", (mc.Edit(path, "b", "c"), mc.Edit(path, "nothing", "z")), (), "", ("t",)))
    assert target.read_text(encoding="utf-8") == "a a b"


def _fake_checkout(root):
    source = root / "apps" / "android" / "sync" / "src" / "main" / "A.kt"
    source.parent.mkdir(parents=True)
    source.write_text("val x = 1\n", encoding="utf-8")
    for junk in ("apps/android/sync/build/out.class", ".git/config", ".venv/bin/python", ".serena/memory"):
        (root / junk).parent.mkdir(parents=True, exist_ok=True)
        (root / junk).write_text("junk", encoding="utf-8")
    return source


def test_snapshot_copies_sources_but_not_build_outputs_or_tool_directories(tmp_path):
    source_root, snapshot = tmp_path / "repo", tmp_path / "snap"
    _fake_checkout(source_root)
    mc.sync_snapshot(source_root, snapshot)
    assert (snapshot / "apps/android/sync/src/main/A.kt").read_text(encoding="utf-8") == "val x = 1\n"
    for junk in ("apps/android/sync/build", ".git", ".venv", ".serena"):
        assert not (snapshot / junk).exists(), junk


def test_resyncing_repairs_a_mutation_left_behind_and_keeps_build_outputs(tmp_path):
    source_root, snapshot = tmp_path / "repo", tmp_path / "snap"
    source = _fake_checkout(source_root)
    mc.sync_snapshot(source_root, snapshot)
    leftover = snapshot / "apps/android/sync/src/main/A.kt"
    leftover.write_text("val x = 2\n", encoding="utf-8")  # 一个被杀掉的变异运行留下的东西：长度相同、修改时间也相同，只有校验和能发现
    stat = source.stat()
    os.utime(leftover, ns=(stat.st_atime_ns, stat.st_mtime_ns))
    (snapshot / "apps/android/sync/build").mkdir()
    (snapshot / "apps/android/sync/build/cache").write_text("incremental", encoding="utf-8")
    stale = snapshot / "apps/android/sync/src/main/Gone.kt"
    stale.write_text("val gone = 1\n", encoding="utf-8")

    mc.sync_snapshot(source_root, snapshot)

    assert leftover.read_text(encoding="utf-8") == "val x = 1\n"
    assert not stale.exists(), "真实工作树里已经没有的文件，快照里也要没有"
    assert (snapshot / "apps/android/sync/build/cache").read_text(encoding="utf-8") == "incremental", "构建产物要保留，Gradle 才能增量构建"


def test_default_run_hands_over_to_the_snapshot_and_never_edits_the_real_tree(tmp_path, monkeypatch):
    source_root, snapshot = tmp_path / "repo", tmp_path / "snap"
    source = _fake_checkout(source_root)
    monkeypatch.setattr(mc, "REPO", source_root)
    monkeypatch.setattr(mc, "SNAPSHOT_DIR", snapshot)
    launched = []
    real_run = mc.subprocess.run

    def fake_run(command, **kwargs):
        if command[0] == "rsync":
            return real_run(command, **kwargs)
        launched.append(command)
        return mc.subprocess.CompletedProcess(command, 7)

    monkeypatch.setattr(mc.subprocess, "run", fake_run)
    before = source.read_bytes()

    code = mc.main(["--only", mc.MUTATIONS[0].id])

    assert code == 7, "子进程的退出码要原样传出去"
    assert launched == [[sys.executable, str(snapshot / mc.SCRIPT_RELATIVE), "--in-place", "--only", mc.MUTATIONS[0].id]]
    assert source.read_bytes() == before
    assert (snapshot / "apps/android/sync/src/main/A.kt").exists()


def test_match_selects_mutations_by_regex_and_only_still_narrows(capsys):
    assert mc.main(["--list", "--match", "^(engine-|machine-)"]) == 0
    listed = [line.split("\t")[0] for line in capsys.readouterr().out.splitlines()]
    assert listed and all(i.startswith(("engine-", "machine-")) for i in listed)
    assert set(listed) == {m.id for m in mc.MUTATIONS if m.id.startswith(("engine-", "machine-"))}

    assert mc.main(["--list", "--match", "^(engine-|machine-)", "--only", listed[0], "pin-accepts-any-key"]) == 0
    assert [line.split("\t")[0] for line in capsys.readouterr().out.splitlines()] == [listed[0]]


def test_failed_tests_reads_junit_xml(tmp_path):
    (tmp_path / "TEST-a.B.xml").write_text(
        '<testsuite><testcase classname="a.B" name="ok"/>'
        '<testcase classname="a.B" name="bad"><failure message="x"/></testcase>'
        '<testcase classname="a.B" name="boom"><error message="y"/></testcase></testsuite>',
        encoding="utf-8",
    )
    total, failed = mc.failed_tests(tmp_path)
    assert total == 3
    assert failed == ["B.bad", "B.boom"]
