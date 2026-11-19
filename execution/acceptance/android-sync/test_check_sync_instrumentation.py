"""仪器测试日志核对器自身的测试：少跑、被跳过、半路崩溃、继承来的合同测试没跑，它都必须报出来。

“读得懂真源码”的几条用真实的仓库目录（SqliteSyncJobStoreTest 继承的 6 个合同测试、进程被杀与 Keystore 测试都在预期清单里），
其余用手造的源码与日志，免得好日志也被判有问题时没人发现。
"""
import importlib.util
import pathlib

import pytest

import check_sync_instrumentation as ci

_HERE = pathlib.Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("capture_support", _HERE.parent / "android-capture" / "capture_support.py")
_support = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_support)
all_pass = _support.all_pass
log_for = _support.instrumentation_log

REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]
REAL_DIR = REPO_ROOT / "apps" / "android" / "syncplatform" / "src" / "androidTest"


@pytest.fixture
def sources(tmp_path):
    """一个自己有 @Test 的类，加一个继承真合同基类的子类（它自己没有 @Test）。"""
    (tmp_path / "Own.kt").write_text('package demo\n\nclass OwnTest {\n    @Test\n    fun works() {}\n}\n', encoding="utf-8")
    (tmp_path / "Sub.kt").write_text("package demo.jobs\n\nclass SubJobStoreTest : SyncJobStoreContract() {\n    override fun newStore() = TODO()\n}\n", encoding="utf-8")
    return [tmp_path]


def test_a_subclass_of_the_contract_is_expected_to_run_every_inherited_test(sources):
    expected = ci.expected_tests(sources)
    inherited = {n for n in expected if n.startswith("demo.jobs.SubJobStoreTest#")}

    assert "demo.OwnTest#works" in expected
    assert len(inherited) == len(ci.contract_test_names()) >= 6
    assert "demo.jobs.SubJobStoreTest#enqueue_creates_a_pending_job_and_is_idempotent" in inherited


def test_a_log_where_everything_ran_and_passed_has_no_problems(sources):
    assert ci.check(log_for(all_pass(ci.expected_tests(sources))), sources) == []


def test_an_inherited_test_that_never_ran_is_reported(sources):
    entries = all_pass(ci.expected_tests(sources))
    missing = next(n for n in entries if n.startswith("demo.jobs."))
    del entries[missing]

    problems = ci.check(log_for(entries), sources)

    assert len(problems) == 1 and missing in problems[0] and "没有这个测试的记录" in problems[0]


@pytest.mark.parametrize("code,word", [(-1, "出错"), (-2, "失败"), (-3, "被忽略"), (-4, "假设失败")])
def test_a_test_that_did_not_pass_is_reported_by_its_status(sources, code, word):
    entries = all_pass(ci.expected_tests(sources))
    entries["demo.OwnTest#works"] = [1, code]

    problems = ci.check(log_for(entries), sources)

    assert len(problems) == 1 and problems[0].startswith(f"demo.OwnTest#works: {word}"), problems


def test_a_test_that_started_but_never_finished_is_reported(sources):
    entries = all_pass(ci.expected_tests(sources))
    entries["demo.OwnTest#works"] = [1]

    problems = ci.check(log_for(entries), sources)

    assert len(problems) == 1 and "只有开始记录" in problems[0]


def test_a_test_in_the_log_but_not_in_the_source_is_reported(sources):
    entries = all_pass(ci.expected_tests(sources))
    entries["demo.StaleTest#oldTest"] = [1, 0]

    problems = ci.check(log_for(entries), sources)

    assert len(problems) == 1 and "demo.StaleTest#oldTest" in problems[0] and "旧版测试 APK" in problems[0]


def test_an_empty_source_dir_is_an_error_not_a_pass(tmp_path):
    problems = ci.check(log_for({}), [tmp_path])

    assert len(problems) == 1 and "没有找到任何 @Test" in problems[0]


def test_a_contract_whose_tests_cannot_all_be_read_fails_loudly(tmp_path):
    odd = tmp_path / "Contract.kt"
    odd.write_text("abstract class SyncJobStoreContract {\n    @Test\n    fun `带反引号的名字`() {}\n}\n", encoding="utf-8")

    with pytest.raises(ValueError):
        ci.contract_test_names(odd)


def test_the_real_sources_are_read_completely():
    expected = ci.expected_tests([REAL_DIR])

    for name in (
        "app.auri.syncplatform.SqliteSyncJobStoreTest#enqueue_creates_a_pending_job_and_is_idempotent",
        "app.auri.syncplatform.SqliteSyncJobStoreTest#observe_emits_the_latest_list_after_every_change",
        "app.auri.syncplatform.ProcessKillSurvivalTest#事务中途被杀_Revision_与任务一个都没有留下",
        "app.auri.syncplatform.KeystoreSecretBoxTest#封好的数据能原样打开",
    ):
        assert name in expected, name
    assert len([n for n in expected if n.startswith("app.auri.syncplatform.SqliteSyncJobStoreTest#")]) == len(ci.contract_test_names())
