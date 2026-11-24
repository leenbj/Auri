"""Ticket 22：手机实际发给网关的请求满足真实的数据合同。

这里运行真实的 Gradle 单元测试 DestinationContractOutputTest（在 :sync 里，用真实的目的地服务、发送账与同步引擎，只有网关、局域网与本机核验是假的），
让 Kotlin 把六个场景写成 JSON 文件（创建请求、入库请求、追加 Delivery 的请求、网关侧观察），再用 destination_contract_check（真实 contracts/schemas/ + 语义规则 + 场景断言）逐个校验。

前置条件见 README.md；缺少 JAVA_HOME 或依赖时测试失败而不是跳过。
"""
import json

import pytest

import destination_contract_check as dc
from destination_support import gradle


@pytest.fixture(scope="module")
def jvm_output(tmp_path_factory):
    out = tmp_path_factory.mktemp("destination-contract-out") / "jvm"
    result = gradle(
        ":sync:cleanTest",
        ":sync:test",
        "--tests",
        "app.auri.sync.contract.DestinationContractOutputTest",
        f"-PauriContractOut={out}",
    )
    assert result.returncode == 0, (result.stdout + result.stderr)[-4000:]
    return out


def test_every_scenario_the_kotlin_tests_wrote_satisfies_the_real_contract(jvm_output):
    # strict：Kotlin 多写出来的场景目录也要有人登记，否则它的输出没被校验
    assert dc.check_tree(jvm_output, dc.EXPECTED_JVM_SCENARIOS, strict=True) == []


def test_the_user_chose_the_mode_in_each_create_request_and_nothing_defaulted(jvm_output):
    auto = json.loads((jvm_output / "create-auto-send" / "create-request.json").read_text(encoding="utf-8"))
    preview = json.loads((jvm_output / "create-preview-confirm" / "create-request.json").read_text(encoding="utf-8"))

    assert (auto["send_mode"], preview["send_mode"]) == ("auto_send", "preview_confirm")


def test_before_the_user_confirms_the_text_is_already_on_the_gateway_but_no_delivery_exists(jvm_output):
    held = jvm_output / "preview-held-before-confirm"
    files = {p.name for p in held.iterdir()}
    observed = json.loads((held / "scenario.json").read_text(encoding="utf-8"))
    submission = json.loads((held / "submission.json").read_text(encoding="utf-8"))

    assert "additions-request.json" not in files
    assert (observed["puts"], observed["additions"], observed["deliveries"]) == (1, 0, 0)
    assert submission["publication"]["destination_ids"] == []  # 点名了就会在确认之前建出 Delivery


def test_after_the_confirmation_the_delivery_comes_from_one_addition_and_the_revision_is_not_sent_again(jvm_output):
    confirmed = jvm_output / "preview-after-confirm"
    observed = json.loads((confirmed / "scenario.json").read_text(encoding="utf-8"))
    addition = json.loads((confirmed / "additions-request.json").read_text(encoding="utf-8"))

    assert (observed["puts"], observed["additions"], observed["deliveries"]) == (1, 1, 1)
    assert addition["destination_ids"] == observed["destination_ids"]


def test_the_auto_sent_draft_and_the_confirmed_draft_are_both_unreviewed(jvm_output):
    for scenario in ("auto-send-unreviewed", "preview-after-confirm"):
        body = json.loads((jvm_output / scenario / "submission.json").read_text(encoding="utf-8"))
        assert body["content"]["review_state"] == "not_reviewed", scenario
        assert body["content"]["source"] == "asr", scenario
