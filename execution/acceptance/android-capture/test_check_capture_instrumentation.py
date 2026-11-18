"""仪器测试日志核对器自身的测试：少跑、被跳过、半路崩溃、继承来的契约测试没跑，它都必须报出来。

证明核对器“读得懂真源码”的几条用真实的仓库目录（继承的 11 个契约测试、自己的 SQLite 与设备端测试都在预期清单里），
其余用手造的源码与日志，免得好日志也被判有问题时没人发现。
"""
import pathlib

import pytest

import check_capture_instrumentation as ci
from capture_support import all_pass, instrumentation_log as log_for

REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]
ANDROID = REPO_ROOT / "apps" / "android"
REAL_DIRS = [ANDROID / "platform" / "src" / "androidTest", ANDROID / "app" / "src" / "androidTest" / "kotlin" / "app" / "auri" / "android" / "capture"]


@pytest.fixture
def sources(tmp_path):
    """一个自己有 @Test 的类，加一个继承真契约基类的子类（它自己没有 @Test）。"""
    (tmp_path / "Own.kt").write_text('package demo\n\nclass OwnTest {\n    @Test\n    fun works() {}\n}\n', encoding="utf-8")
    (tmp_path / "Sub.kt").write_text("package demo.lib\n\nclass SubContractTest : RecordLibraryContract() {\n    override fun newLibrary() = TODO()\n}\n", encoding="utf-8")
    return [tmp_path]


def expected_for(sources) -> set:
    return ci.expected_tests(sources)


def test_a_subclass_of_the_contract_is_expected_to_run_every_inherited_test(sources):
    expected = expected_for(sources)
    inherited = {n for n in expected if n.startswith("demo.lib.SubContractTest#")}

    assert "demo.OwnTest#works" in expected
    assert len(inherited) == len(ci.contract_test_names()) >= 11


def test_a_log_where_everything_ran_and_passed_has_no_problems(sources):
    assert ci.check(log_for(all_pass(expected_for(sources))), sources) == []


def test_an_inherited_test_that_never_ran_is_reported(sources):
    entries = all_pass(expected_for(sources))
    missing = next(n for n in entries if n.startswith("demo.lib."))
    del entries[missing]

    problems = ci.check(log_for(entries), sources)

    assert len(problems) == 1 and missing in problems[0] and "没有这个测试的记录" in problems[0]


@pytest.mark.parametrize("code,word", [(-1, "出错"), (-2, "失败"), (-3, "被忽略"), (-4, "假设失败")])
def test_a_test_that_did_not_pass_is_reported_by_its_status(sources, code, word):
    entries = all_pass(expected_for(sources))
    entries["demo.OwnTest#works"] = [1, code]

    problems = ci.check(log_for(entries), sources)

    assert len(problems) == 1 and problems[0].startswith(f"demo.OwnTest#works: {word}"), problems


def test_a_test_that_started_but_never_finished_is_reported(sources):
    entries = all_pass(expected_for(sources))
    entries["demo.OwnTest#works"] = [1]

    assert any("只有开始记录" in p for p in ci.check(log_for(entries), sources))


def test_a_test_in_the_log_that_is_not_in_the_sources_is_reported(sources):
    entries = all_pass(expected_for(sources))
    entries["demo.OldTest#removedLongAgo"] = [1, 0]

    assert any("源码里找不到" in p and "removedLongAgo" in p for p in ci.check(log_for(entries), sources))


def test_empty_source_dirs_are_a_problem_not_a_pass(tmp_path):
    assert ci.check("", [tmp_path])


def test_a_contract_base_whose_tests_cannot_be_read_is_an_error_not_a_silent_undercount(tmp_path):
    odd = tmp_path / "Odd.kt"
    odd.write_text("abstract class RecordLibraryContract {\n    @Test\n    fun plainNameNotBackticked() {}\n}\n", encoding="utf-8")

    with pytest.raises(ValueError):
        ci.contract_test_names(odd)


def test_the_real_sources_expect_the_contract_tests_the_sqlite_tests_and_the_device_refusal_tests():
    expected = ci.expected_tests(REAL_DIRS)

    contract = {n for n in expected if n.startswith("app.auri.platform.library.SqliteRecordLibraryContractTest#")}
    assert len(contract) == len(ci.contract_test_names())
    assert any(n.startswith("app.auri.platform.library.SqliteRecordLibraryTest#") for n in expected)
    assert any(n.startswith("app.auri.android.capture.CaptureRefusalOnDeviceTest#") for n in expected)
    assert any(n.startswith("app.auri.android.capture.ForegroundServiceOnDeviceTest#") for n in expected)
    assert not any("TranscribeFileActivityTest" in n or "RealEngineTranscriptionTest" in n for n in expected), "Ticket 14 的设备测试要模型，不在本次验收里"


def test_command_line_exit_codes(sources, tmp_path, capsys):
    good = tmp_path / "good.log"
    good.write_text(log_for(all_pass(expected_for(sources))), encoding="utf-8")
    bad = tmp_path / "bad.log"
    bad.write_text("", encoding="utf-8")

    assert ci.main(["x", str(good), str(sources[0])]) == 0
    assert ci.main(["x", str(bad), str(sources[0])]) == 1
    assert ci.main(["x"]) == 2
    capsys.readouterr()
