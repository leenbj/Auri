"""check_instrumentation.py 的测试：仪器测试日志里的“OK (N tests)”不足以证明该跑的都跑了、都过了。

被 @Ignore 掉的测试不计入 N；Assume 失败（等于跳过）却算“通过”。这个核对器按源码里的每个 @Test
逐个要求日志里出现“通过”的记录，所以删减、忽略、跳过、中途崩溃都会被抓到。
"""
import pathlib

import pytest

import check_instrumentation as ci

PKG = "app.auri.android"


def status_block(cls: str, test: str, code: int) -> str:
    return (
        f"INSTRUMENTATION_STATUS: class={PKG}.{cls}\nINSTRUMENTATION_STATUS: current=1\n"
        f"INSTRUMENTATION_STATUS: id=AndroidJUnitRunner\nINSTRUMENTATION_STATUS: numtests=3\n"
        f"INSTRUMENTATION_STATUS: stream=\nINSTRUMENTATION_STATUS: test={test}\nINSTRUMENTATION_STATUS_CODE: {code}\n"
    )


def passed(cls: str, test: str) -> str:
    return status_block(cls, test, 1) + status_block(cls, test, 0)


KOTLIN_A = """package app.auri.android

/** 注释里提到 @Test 不算。 */
@RunWith(AndroidJUnit4::class)
class AlphaTest {
    @Test
    fun first() {}

    @Test fun second() = Unit

    private fun helper() {}
}
"""

KOTLIN_B = """package app.auri.android

class BetaTest {
    @Test
    fun third() {}
}
"""


@pytest.fixture()
def sources(tmp_path):
    src = tmp_path / "androidTest"
    src.mkdir()
    (src / "AlphaTest.kt").write_text(KOTLIN_A, encoding="utf-8")
    (src / "BetaTest.kt").write_text(KOTLIN_B, encoding="utf-8")
    return src


FULL_LOG = passed("AlphaTest", "first") + passed("AlphaTest", "second") + passed("BetaTest", "third") + "\nOK (3 tests)\n"


def test_every_source_test_reported_as_passed_is_accepted(sources):
    assert ci.check(FULL_LOG, sources) == []


def test_expected_tests_are_read_from_the_sources_including_one_line_and_split_annotations(sources):
    assert ci.expected_tests(sources) == {
        f"{PKG}.AlphaTest#first", f"{PKG}.AlphaTest#second", f"{PKG}.BetaTest#third",
    }


def test_a_test_with_another_annotation_between_test_and_fun_is_still_expected(tmp_path):
    """@Ignore 单独写一行是合法写法：方法若因此从期望里消失，日志里的“被忽略”也就没人看了。"""
    src = tmp_path / "androidTest"
    src.mkdir()
    (src / "GammaTest.kt").write_text(
        'package app.auri.android\n\nclass GammaTest {\n'
        '    @Test\n    @Ignore("等设备")\n    fun parked() {}\n\n'
        '    @Test @Throws(Exception::class) fun risky() {}\n\n'
        '    @Test // 行尾注释\n    fun commented() {}\n'
        '}\n',
        encoding="utf-8",
    )

    assert ci.expected_tests(src) == {f"{PKG}.GammaTest#parked", f"{PKG}.GammaTest#risky", f"{PKG}.GammaTest#commented"}
    log = status_block("GammaTest", "parked", 1) + status_block("GammaTest", "parked", -3) \
        + passed("GammaTest", "risky") + passed("GammaTest", "commented")
    assert any("GammaTest#parked" in p and "忽略" in p for p in ci.check(log, src))


def test_a_test_annotation_the_parser_cannot_follow_fails_loudly_instead_of_being_dropped(tmp_path):
    src = tmp_path / "androidTest"
    src.mkdir()
    (src / "WeirdTest.kt").write_text(
        'package app.auri.android\n\nclass WeirdTest {\n    @Test\n    private val notAFunction = 1\n}\n', encoding="utf-8"
    )

    with pytest.raises(ValueError, match="WeirdTest"):
        ci.expected_tests(src)


def test_comment_markers_inside_string_literals_do_not_swallow_the_tests_between_them(tmp_path):
    """"files/*" 里的 /* 不是注释的开头：当成注释，会连后面的 @Test 一起抹掉，那个测试没跑也没人知道。"""
    src = tmp_path / "androidTest"
    src.mkdir()
    (src / "GlobTest.kt").write_text(
        'package app.auri.android\n\nclass GlobTest {\n'
        '    private val quote = \'"\'; private val glob = "files/*"\n'
        '    @Test\n    fun between() {}\n'
        '    private val close = "*/"\n'
        '    @Test\n    fun after() {}\n'
        '}\n',
        encoding="utf-8",
    )

    assert ci.expected_tests(src) == {f"{PKG}.GlobTest#between", f"{PKG}.GlobTest#after"}
    problems = ci.check(passed("GlobTest", "after"), src)  # between 没跑
    assert any("GlobTest#between" in p for p in problems), problems


def test_a_raw_string_holding_a_comment_marker_does_not_swallow_the_tests_after_it(tmp_path):
    src = tmp_path / "androidTest"
    src.mkdir()
    (src / "RawTest.kt").write_text(
        'package app.auri.android\n\nclass RawTest {\n'
        '    private val raw = """\n        带 "引号" 和 /* 注释开头\n    """\n'
        '    @Test\n    fun afterRaw() {}\n'
        '    private val close = "*/"\n'
        '    @Test\n    fun last() {}\n'
        '}\n',
        encoding="utf-8",
    )

    assert ci.expected_tests(src) == {f"{PKG}.RawTest#afterRaw", f"{PKG}.RawTest#last"}


def test_an_at_test_written_inside_a_string_literal_is_not_a_test(tmp_path):
    src = tmp_path / "androidTest"
    src.mkdir()
    (src / "DocTest.kt").write_text(
        'package app.auri.android\n\nclass DocTest {\n'
        '    private val sample = "写成 @Test fun phantom() 才算测试"\n'
        '    @Test\n    fun real() {}\n'
        '}\n',
        encoding="utf-8",
    )

    assert ci.expected_tests(src) == {f"{PKG}.DocTest#real"}
    assert ci.check(passed("DocTest", "real"), src) == []


def test_a_test_in_the_log_that_the_sources_do_not_have_is_a_problem(sources):
    """源码解析漏了它，或设备上装的是旧版测试 APK：无论哪种，“全部通过”都不可信。"""
    extra = passed("AlphaTest", "first") + passed("AlphaTest", "second") + passed("BetaTest", "third") \
        + passed("GhostTest", "appearsOnlyInTheLog") + status_block("GhostTest", "ignoredGhost", 1) + status_block("GhostTest", "ignoredGhost", -3)

    problems = ci.check(extra, sources)

    assert any("GhostTest#appearsOnlyInTheLog" in p and "源码里找不到" in p for p in problems), problems
    assert any("GhostTest#ignoredGhost" in p and "忽略" in p for p in problems), problems


def test_a_test_missing_from_the_log_is_reported_by_name(sources):
    log = passed("AlphaTest", "first") + passed("BetaTest", "third") + "\nOK (2 tests)\n"

    problems = ci.check(log, sources)

    assert any("AlphaTest#second" in p for p in problems), problems


def test_an_ignored_test_is_a_problem_even_though_the_runner_still_says_ok(sources):
    log = passed("AlphaTest", "first") + status_block("AlphaTest", "second", 1) + status_block("AlphaTest", "second", -3) \
        + passed("BetaTest", "third") + "\nOK (2 tests)\n"

    problems = ci.check(log, sources)

    assert any("AlphaTest#second" in p and "忽略" in p for p in problems), problems


def test_an_assumption_failure_counts_as_a_skip_not_a_pass(sources):
    log = passed("AlphaTest", "first") + passed("AlphaTest", "second") \
        + status_block("BetaTest", "third", 1) + status_block("BetaTest", "third", -4) + "\nOK (3 tests)\n"

    problems = ci.check(log, sources)

    assert any("BetaTest#third" in p and "假设" in p for p in problems), problems


def test_a_failed_test_is_a_problem(sources):
    log = passed("AlphaTest", "first") + status_block("AlphaTest", "second", 1) + status_block("AlphaTest", "second", -2) \
        + passed("BetaTest", "third")

    assert any("AlphaTest#second" in p and "失败" in p for p in ci.check(log, sources))


def test_a_test_that_started_but_never_finished_is_a_problem(sources):
    """进程中途崩溃时只有开始记录，没有结束记录。"""
    log = passed("AlphaTest", "first") + status_block("AlphaTest", "second", 1)

    problems = ci.check(log, sources)

    assert any("AlphaTest#second" in p and "没有结束" in p for p in problems), problems


def test_a_late_pass_does_not_hide_an_earlier_failure_of_the_same_test(sources):
    log = status_block("AlphaTest", "first", -2) + passed("AlphaTest", "first") + passed("AlphaTest", "second") + passed("BetaTest", "third")

    assert any("AlphaTest#first" in p for p in ci.check(log, sources))


def test_sources_without_any_test_are_a_problem_not_a_pass(tmp_path):
    empty = tmp_path / "empty"
    empty.mkdir()

    assert ci.check(FULL_LOG, empty) != []


def test_an_empty_log_reports_every_expected_test_as_missing(sources):
    problems = ci.check("", sources)

    assert len(problems) >= 3


def test_cli_exit_codes(sources, tmp_path, capsys):
    good = tmp_path / "good.log"
    good.write_text(FULL_LOG, encoding="utf-8")
    bad = tmp_path / "bad.log"
    bad.write_text(passed("AlphaTest", "first"), encoding="utf-8")

    assert ci.main(["check_instrumentation.py", str(good), str(sources)]) == 0
    assert ci.main(["check_instrumentation.py", str(bad), str(sources)]) == 1
    assert "AlphaTest#second" in capsys.readouterr().out
    assert ci.main(["check_instrumentation.py"]) == 2


def test_the_real_android_test_sources_contain_the_tests_the_acceptance_flow_relies_on():
    """源码目录路径写错或被清空时，核对会悄悄变成“没有要求”：钉住几个关键测试必须在期望里。"""
    names = ci.expected_tests(pathlib.Path(ci.__file__).resolve().parents[3] / "apps/android/app/src/androidTest")

    for required in (
        "ModelRefusalOnDeviceTest#oneFlippedByteInTheRealModelIsRefusedBeforeTheEngineIsTouched",
        "OfflineProofTest#theInstalledPackageRequestsInternetOnlyForModelDownloadAndNoOtherNetworkPermission",
        "RealEngineTranscriptionTest#chineseSampleIsTranscribed",
    ):
        assert any(n.endswith("." + required) for n in names), (required, sorted(names))
