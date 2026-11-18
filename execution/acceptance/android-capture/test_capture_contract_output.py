"""Ticket 16：Kotlin 采集 → 转录产出的 JSON 满足真实的数据合同。

这里运行真实的 Gradle 单元测试 ContractOutputTest（在 :capture 里，用真实的协调器与管线，只有系统边缘是假的），
让 Kotlin 把三个场景写成 JSON 文件，再用 capture_contract_check（真实 contracts/schemas/ + 语义规则 + 文件间一致性）逐个校验。

前置条件见 README.md；缺少 JAVA_HOME 或依赖时测试失败而不是跳过。
"""
import json

import pytest

import capture_contract_check as cc
from capture_support import gradle


@pytest.fixture(scope="module")
def jvm_output(tmp_path_factory):
    out = tmp_path_factory.mktemp("contract-out") / "jvm"
    result = gradle(
        ":capture:cleanTest",
        ":capture:test",
        "--tests",
        "app.auri.capture.contract.ContractOutputTest",
        f"-PauriContractOut={out}",
    )
    assert result.returncode == 0, (result.stdout + result.stderr)[-4000:]
    return out


def test_every_scenario_the_kotlin_tests_wrote_satisfies_the_real_contract(jvm_output):
    # strict：Kotlin 多写出来的场景目录也要有人登记，否则它的输出没被校验
    assert cc.check_tree(jvm_output, cc.EXPECTED_JVM_SCENARIOS, strict=True) == []


def test_text_outside_the_basic_plane_hashes_the_same_in_kotlin_and_python(jvm_output):
    """正文含 😀（UTF-16 代理对）。Kotlin 与 Python 对同一段文字必须得到同一个 SHA-256（SR-01）。"""
    content = json.loads((jvm_output / "complete-with-text" / "revision-content.json").read_text(encoding="utf-8"))

    assert "😀" in content["text"]
    assert content["text_sha256"] == cc.text_sha256(content["text"])


def test_the_scenario_without_speech_writes_nothing_that_would_be_submitted(jvm_output):
    """路由已确认但没有语音：有录音结果，但没有 Revision 内容，也没有要提交的 capture 段。"""
    files = {p.name for p in (jvm_output / "complete-no-speech").iterdir()}

    assert "recording-result.json" in files
    assert files.isdisjoint(cc.TEXT_FILES)


def test_the_truncated_recording_is_reported_partial_and_names_the_route_loss(jvm_output):
    result = json.loads((jvm_output / "partial-route-lost" / "recording-result.json").read_text(encoding="utf-8"))
    block = json.loads((jvm_output / "partial-route-lost" / "capture-block.json").read_text(encoding="utf-8"))

    assert (result["status"], result["end_reason"]) == ("partial", "route_lost")
    assert (block["status"], block["end_reason"]) == ("partial", "route_lost")
