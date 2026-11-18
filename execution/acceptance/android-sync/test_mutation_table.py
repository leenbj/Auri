"""变异表自身的离线检查：表不能悄悄过期。

mutation_check.py 里每条变异都要“恰好改一处生产代码”。生产代码一重构，原文找不到了，这条变异就会变成“什么都没改、
测试当然全绿”的假象。这里逐条核对：原文恰好出现一次、改动确实改了东西、预期变红的测试在源码里真的存在、
没有任何变异会碰到测试文件，并且“应用 → 还原”之后文件字节级不变。
"""
import pathlib
import re

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
