"""真网关 JVM 测试（:sync:realGatewayTest）的 Python 入口：真实跑 Gradle，再核对 JUnit 报告。

为什么要再核对一遍：Gradle 说“成功”不等于“源码里的每个 @Test 都真的跑了”——被过滤掉、被忽略、整个类没被收进来，
Gradle 都照样成功。所以这里把源码里的 @Test 逐个数出来，和报告逐个对：少一个、跳过一个、失败一个都算不过。

前置条件见 README.md：JAVA_HOME（先 `source ~/.config/auri/android-env.sh`）、仓库 .venv；缺少时测试失败而不是跳过。
"""
import importlib.util
import pathlib
import re
import xml.etree.ElementTree as ET

import pytest

_HERE = pathlib.Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("capture_support", _HERE.parent / "android-capture" / "capture_support.py")
_support = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_support)

REPO_ROOT = _support.REPO_ROOT
SOURCES = REPO_ROOT / "apps" / "android" / "sync" / "src" / "test" / "kotlin" / "app" / "auri" / "sync" / "realgateway"
RESULTS = REPO_ROOT / "apps" / "android" / "sync" / "build" / "test-results" / "realGatewayTest"

TEST_NAME = re.compile(r"@Test\s*\n\s*fun\s+`([^`]+)`")


def source_tests(source_dir: pathlib.Path) -> list:
    return sorted(name for path in source_dir.rglob("*.kt") for name in TEST_NAME.findall(path.read_text(encoding="utf-8")))


def problems(source_dir: pathlib.Path, results_dir: pathlib.Path) -> list:
    """报告与源码对不上的地方；空列表 = 每个 @Test 都跑了而且通过。"""
    expected = source_tests(source_dir)
    if not expected:
        return [f"{source_dir} 里一个 @Test 都没找到（路径或写法变了？）"]
    ran, found = {}, []
    for xml in sorted(results_dir.glob("TEST-*.xml")):
        for case in ET.parse(xml).getroot().iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                state = "失败"
            elif case.find("skipped") is not None:
                state = "被跳过"
            else:
                state = "通过"
            ran[case.get("name")] = state
    for name in expected:
        if name not in ran:
            found.append(f"没跑：{name}")
        elif ran[name] != "通过":
            found.append(f"{ran[name]}：{name}")
    for name in ran:
        if name not in expected:
            found.append(f"报告里有源码里没有的测试：{name}")
    return found


def test_every_real_gateway_test_in_the_sources_runs_and_passes():
    done = _support.gradle("--no-daemon", ":sync:realGatewayTest", timeout=1200)
    assert done.returncode == 0, (done.stdout + done.stderr)[-4000:]
    assert problems(SOURCES, RESULTS) == []


# ---- 核对器自己：好报告判好，坏报告（少、跳过、失败、多）都要报出来 ----

def _suite(tmp_path, source_names, cases):
    src, out = tmp_path / "src", tmp_path / "out"
    src.mkdir()
    out.mkdir()
    (src / "T.kt").write_text("".join(f"    @Test\n    fun `{n}`() {{}}\n" for n in source_names), encoding="utf-8")
    body = "".join(f'<testcase name="{n}" classname="T">{inner}</testcase>' for n, inner in cases)
    (out / "TEST-T.xml").write_text(f"<testsuite>{body}</testsuite>", encoding="utf-8")
    return src, out


def test_a_report_with_every_test_passing_has_no_problems(tmp_path):
    assert problems(*_suite(tmp_path, ["a", "b"], [("a", ""), ("b", "")])) == []


@pytest.mark.parametrize(
    "cases, expected",
    [
        ([("a", "")], "没跑：b"),
        ([("a", ""), ("b", "<skipped/>")], "被跳过：b"),
        ([("a", ""), ("b", "<failure/>")], "失败：b"),
        ([("a", ""), ("b", "<error/>")], "失败：b"),
        ([("a", ""), ("b", ""), ("c", "")], "报告里有源码里没有的测试：c"),
    ],
)
def test_a_report_that_does_not_match_the_sources_is_reported(tmp_path, cases, expected):
    assert expected in problems(*_suite(tmp_path, ["a", "b"], cases))


def test_sources_without_any_test_are_a_problem_not_a_pass(tmp_path):
    assert problems(*_suite(tmp_path, [], [])) != []
