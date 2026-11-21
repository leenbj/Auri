"""Ticket 14 第 6 项：Kotlin 产出的 JSON 满足真实的数据合同。

这里运行真实的 Gradle 单元测试 ContractOutputTest，让 Kotlin 把七个场景写成 JSON 文件，
再用 contract_check（真实 contracts/schemas/ + 语义规则 + 转录状态机重放）逐个校验。
设备（模拟器/真机）上真引擎产出的文件用同一个校验器检查，见 README 的仪器测试一节。

前置条件见 README.md；缺少 JAVA_HOME 或依赖时测试失败而不是跳过。
"""
import json

import pytest

import contract_check as cc
from asr_android_support import gradle


@pytest.fixture(scope="module")
def jvm_output(tmp_path_factory):
    out = tmp_path_factory.mktemp("contract-out") / "jvm"
    result = gradle(
        ":asr:cleanTestDebugUnitTest",
        ":asr:testDebugUnitTest",
        "--tests",
        "app.auri.asr.ContractOutputTest",
        f"-PauriContractOut={out}",
    )
    assert result.returncode == 0, (result.stdout + result.stderr)[-4000:]
    return out


def test_every_scenario_the_kotlin_tests_wrote_satisfies_the_real_contract(jvm_output):
    # strict：Kotlin 多写出来的场景目录也要有人登记，否则它的输出没被校验
    assert cc.check_tree(jvm_output, cc.EXPECTED_JVM_SCENARIOS, strict=True) == []


def test_text_outside_the_basic_plane_hashes_the_same_in_kotlin_and_python(jvm_output):
    """正文含 😀（UTF-16 代理对）。Kotlin 与 Python 对同一段文字必须得到同一个 SHA-256（SR-01）。"""
    content = json.loads((jvm_output / "succeeded" / "revision-content.json").read_text(encoding="utf-8"))

    assert content["text"] == "你好，Auri 😀 hello"
    assert content["text_sha256"] == cc.text_sha256(content["text"])


def test_the_engine_failure_scenario_leaves_no_error_file_because_the_contract_has_no_code(jvm_output):
    """合同 1.2 的缺口（1.1 同样没有）：没有“引擎内部失败”的错误码。输出如实只有轨迹，不硬套别的码。"""
    files = {p.name for p in (jvm_output / "failed-engine-exception").iterdir()}

    assert "error.json" not in files
    assert "trace.json" in files
