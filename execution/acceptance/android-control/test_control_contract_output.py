"""Ticket 17：Kotlin 控制链（向导 + 归一化器）产出的 JSON 满足真实的数据合同。

这里运行真实的 Gradle 单元测试 ControlContractOutputTest（在 :capture 里，用真实的校准向导与事件归一化器，
只有系统边缘是假的），让 Kotlin 把校准档案与控制事件写成 JSON 文件，再用 control_contract_check
（真实 contracts/schemas/ + 语义规则）逐个校验。做法同 Ticket 16 的 android-capture/test_capture_contract_output.py。

证据等级：这些都是 E2（JVM 单测造的输入）。真机 + FreeClip 校准出来的档案用 `control_contract_check.py --profile` 校验，见 README.md。
前置条件见 README.md；缺少 JAVA_HOME 或依赖时测试失败而不是跳过。
"""
import json

import pytest

import control_contract_check as cc
from control_support import gradle


@pytest.fixture(scope="module")
def jvm_output(tmp_path_factory):
    # ControlContractOutputTest 把文件写在“这个目录的同级目录 control-jvm”，不与 Ticket 16 的采集输出混在一起
    given = tmp_path_factory.mktemp("contract-out") / "jvm"
    result = gradle(
        ":capture:cleanTest",
        ":capture:test",
        "--tests",
        "app.auri.capture.calibration.ControlContractOutputTest",
        f"-PauriContractOut={given}",
    )
    assert result.returncode == 0, (result.stdout + result.stderr)[-4000:]
    return given.parent / "control-jvm"


def _capabilities(root, scenario) -> dict:
    return json.loads((root / scenario / "headset-profile.json").read_text(encoding="utf-8"))["capabilities"]


def test_every_file_the_kotlin_tests_wrote_satisfies_the_real_contract(jvm_output):
    # strict：Kotlin 多写出来的场景目录也要有人登记，否则它的输出没被校验
    assert cc.check_tree(jvm_output, cc.EXPECTED_JVM_SCENARIOS, strict=True) == []


@pytest.mark.parametrize("environment", cc.ENVIRONMENTS)
def test_a_wizard_run_with_no_observations_leaves_every_capability_untested(jvm_output, environment):
    """清单第 5 项的反面。检查器通过之外再独立断言一次：没有观察，就没有任何结论、证据等级与证据 id，在每种环境下都一样。"""
    capabilities = _capabilities(jvm_output, f"no-observations-{environment}")

    assert {name: capabilities[name] for name in cc.CAPABILITIES} == {name: cc.UNTESTED for name in cc.CAPABILITIES}


@pytest.mark.parametrize("environment", ["unit-test", "emulator", "unconfirmed"])
def test_a_full_run_outside_a_real_device_has_e2_evidence_but_no_conclusion(jvm_output, environment):
    capabilities = _capabilities(jvm_output, f"e2-full-run-{environment}")

    assert [capabilities[name]["status"] for name in cc.CAPABILITIES] == ["NOT_TESTED"] * len(cc.CAPABILITIES)
    assert [capabilities[name]["evidence_level"] for name in cc.SCENARIO_CAPABILITIES] == ["E2"] * len(cc.SCENARIO_CAPABILITIES)
    assert capabilities["start_after_process_termination"] == cc.UNTESTED
    for name in cc.SCENARIO_CAPABILITIES:
        assert all(evidence.endswith(f":{environment}") for evidence in capabilities[name]["evidence_ids"]), name


def test_the_stop_key_is_written_as_a_stop_and_an_unknown_source_is_not_called_a_headset(jvm_output):
    """合同检查之外再直读文件：这两条是“不能把停止键当开始、不能把说不清的来源说成耳机”的产品规则。"""
    events = {path.stem.removeprefix("normalized-control-event-"): json.loads(path.read_text(encoding="utf-8")) for path in (jvm_output / "events").glob("*.json")}

    assert events["stop-key-is-pause"]["command"] == "STOP"
    assert events["play-unknown-source"]["source"] == "unknown_remote"
    assert events["next-is-ignored"]["command"] == "IGNORE"
