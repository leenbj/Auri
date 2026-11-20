"""destination_contract_check 自己的测试：好的输出放行，每一种做坏的输出都被报出来（坏样本由合同里的有效样本改出来）。

检查工具本身有问题时，Kotlin 那边写出再多的 JSON 也说明不了什么，所以这里先证明它认得出坏东西。
"""
import copy
import json
import pathlib

import pytest

import destination_contract_check as dc

SAMPLES = dc.REPO_ROOT / "contracts" / "fixtures" / "messages"
AUTO_ID = "65ea2953-505c-5c6f-9474-7cd5b8474104"
PREVIEW_ID = "9b1a23f8-5241-4e78-9d21-15c4c720c2c9"


def sample(name: str, folder: str = "valid") -> dict:
    return json.loads((SAMPLES / folder / name).read_text(encoding="utf-8"))["instance"]


def submission(destination_ids, *, intent="context", review_state="not_reviewed") -> dict:
    value = sample("revision.submission.asr-first-revision.json")
    value["publication"] = {"intent": intent, "destination_ids": list(destination_ids)}
    value["content"]["review_state"] = review_state
    return value


def write(dir_: pathlib.Path, name: str, value) -> None:
    dir_.mkdir(parents=True, exist_ok=True)
    (dir_ / name).write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")


def good_tree(root: pathlib.Path) -> dict[str, str]:
    """六个场景各写一份合规的输出；返回期望清单。"""
    write(root / "create-auto-send", "create-request.json", sample("destination.create_request.auto-send.json"))
    write(root / "create-preview-confirm", "create-request.json", sample("destination.create_request.preview-confirm.json"))
    write(root / "auto-send-unreviewed", "submission.json", submission([AUTO_ID]))
    write(root / "auto-send-unreviewed", "scenario.json", {"puts": 1, "deliveries": 1, "destination_ids": [AUTO_ID]})
    write(root / "preview-held-before-confirm", "scenario.json", {"puts": 0, "deliveries": 0, "destination_ids": []})
    write(root / "preview-after-confirm", "submission.json", submission([PREVIEW_ID]))
    write(root / "preview-after-confirm", "scenario.json", {"puts": 1, "deliveries": 1, "destination_ids": [PREVIEW_ID]})
    write(root / "action-request-tag", "submission.json", submission([PREVIEW_ID], intent="action_request"))
    write(root / "action-request-tag", "scenario.json", {"puts": 1, "deliveries": 1, "destination_ids": [PREVIEW_ID]})
    return dict(dc.EXPECTED_JVM_SCENARIOS)


@pytest.fixture
def tree(tmp_path):
    return tmp_path, good_tree(tmp_path)


def test_a_compliant_output_tree_passes_in_strict_mode(tree):
    root, expected = tree

    assert dc.check_tree(root, expected, strict=True) == []


def test_the_preset_lists_exactly_the_scenarios_the_checker_knows():
    assert set(dc.PRESETS["jvm"]) == {
        "create-auto-send", "create-preview-confirm", "auto-send-unreviewed", "preview-held-before-confirm", "preview-after-confirm", "action-request-tag",
    }


def problems_of(root, expected, scenario: str) -> list[str]:
    return dc.check_tree(root, {scenario: expected[scenario]})


def test_a_create_request_without_a_send_mode_is_rejected_the_contract_has_no_default(tree):
    root, expected = tree
    write(root / "create-auto-send", "create-request.json", sample("destination.create_request.no-send-mode.json", "schema-invalid"))

    problems = problems_of(root, expected, "create-auto-send")

    assert any("create_request" in p and "send_mode" in p for p in problems), problems


def test_a_create_request_whose_mode_is_not_the_one_the_user_chose_is_rejected(tree):
    root, expected = tree
    write(root / "create-preview-confirm", "create-request.json", sample("destination.create_request.auto-send.json"))

    problems = problems_of(root, expected, "create-preview-confirm")

    assert any("send_mode 不是用户选的 preview_confirm" in p for p in problems), problems


def test_a_create_scenario_that_also_submitted_text_is_rejected(tree):
    root, expected = tree
    write(root / "create-auto-send", "submission.json", submission([AUTO_ID]))

    assert any("不应当有入库请求" in p for p in problems_of(root, expected, "create-auto-send"))


def test_an_auto_sent_draft_that_claims_to_be_reviewed_is_rejected(tree):
    root, expected = tree
    write(root / "auto-send-unreviewed", "submission.json", submission([AUTO_ID], review_state="reviewed"))

    problems = problems_of(root, expected, "auto-send-unreviewed")

    assert any("必须是 not_reviewed" in p for p in problems), problems


def test_a_preview_draft_confirmed_by_the_user_is_still_not_reviewed(tree):
    root, expected = tree
    write(root / "preview-after-confirm", "submission.json", submission([PREVIEW_ID], review_state="reviewed"))

    problems = problems_of(root, expected, "preview-after-confirm")

    assert any("必须是 not_reviewed" in p for p in problems), problems


def test_a_submission_without_destinations_in_an_auto_send_scenario_is_rejected(tree):
    root, expected = tree
    write(root / "auto-send-unreviewed", "submission.json", submission([]))
    write(root / "auto-send-unreviewed", "scenario.json", {"puts": 1, "deliveries": 0, "destination_ids": []})

    assert any("至少一个目的地" in p for p in problems_of(root, expected, "auto-send-unreviewed"))


def test_the_destinations_in_the_request_must_be_the_ones_the_scenario_recorded(tree):
    root, expected = tree
    write(root / "auto-send-unreviewed", "scenario.json", {"puts": 1, "deliveries": 1, "destination_ids": [PREVIEW_ID]})

    assert any("不一致" in p for p in problems_of(root, expected, "auto-send-unreviewed"))


def test_the_action_request_scenario_must_carry_that_tag(tree):
    root, expected = tree
    write(root / "action-request-tag", "submission.json", submission([PREVIEW_ID], intent="context"))

    assert any("action_request" in p for p in problems_of(root, expected, "action-request-tag"))


@pytest.mark.parametrize("puts, deliveries", [(2, 1), (1, 2), (0, 1)])
def test_the_gateway_must_have_seen_exactly_one_put_and_one_delivery_per_destination(tree, puts, deliveries):
    root, expected = tree
    write(root / "auto-send-unreviewed", "scenario.json", {"puts": puts, "deliveries": deliveries, "destination_ids": [AUTO_ID]})

    assert problems_of(root, expected, "auto-send-unreviewed"), (puts, deliveries)


def test_a_held_scenario_with_a_submission_is_rejected_nothing_may_leave_before_the_user_confirms(tree):
    root, expected = tree
    write(root / "preview-held-before-confirm", "submission.json", submission([PREVIEW_ID]))

    assert any("不得有入库请求" in p for p in problems_of(root, expected, "preview-held-before-confirm"))


@pytest.mark.parametrize("puts, deliveries", [(1, 0), (0, 1)])
def test_a_held_scenario_where_the_gateway_saw_anything_is_rejected(tree, puts, deliveries):
    root, expected = tree
    write(root / "preview-held-before-confirm", "scenario.json", {"puts": puts, "deliveries": deliveries, "destination_ids": []})

    assert any("都必须是 0" in p for p in problems_of(root, expected, "preview-held-before-confirm"))


def test_a_held_scenario_without_the_gateway_side_observation_proves_nothing(tree):
    root, expected = tree
    (root / "preview-held-before-confirm" / "scenario.json").unlink()

    assert problems_of(root, expected, "preview-held-before-confirm")


def test_a_missing_scenario_directory_and_an_unregistered_one_are_both_reported(tree):
    root, expected = tree
    (root / "create-auto-send" / "create-request.json").unlink()
    (root / "create-auto-send").rmdir()
    write(root / "brand-new-scenario", "create-request.json", sample("destination.create_request.auto-send.json"))

    problems = dc.check_tree(root, expected, strict=True)

    assert any("create-auto-send 不存在" in p for p in problems)
    assert any("brand-new-scenario 不在预期清单里" in p for p in problems)


def test_a_broken_json_file_is_reported_without_echoing_its_content(tree):
    root, expected = tree
    (root / "auto-send-unreviewed" / "submission.json").write_text('{"content": "机密口述内容', encoding="utf-8")

    problems = problems_of(root, expected, "auto-send-unreviewed")

    assert any("不是有效的 UTF-8 JSON" in p for p in problems)
    assert not any("机密口述内容" in p for p in problems)


def test_problem_messages_never_echo_the_text_of_the_submission(tree):
    root, expected = tree
    bad = submission([AUTO_ID])
    bad["content"]["text"] = "这是一段不该出现在报告里的口述"
    bad["content"]["unexpected_field_with_secret_name"] = "x"
    write(root / "auto-send-unreviewed", "submission.json", bad)

    problems = problems_of(root, expected, "auto-send-unreviewed")

    assert problems
    joined = "\n".join(problems)
    assert "这是一段不该出现在报告里的口述" not in joined
    assert "unexpected_field_with_secret_name" not in joined


def test_an_unknown_expectation_is_reported(tree):
    root, _ = tree

    problems = dc.check_scenario(root / "create-auto-send", "create:maybe")

    assert problems and "未知的期望" in problems[0]


def test_main_exits_zero_for_a_good_tree_and_one_for_a_bad_one(tree, capsys):
    root, _ = tree
    assert dc.main(["x", str(root), "--preset", "jvm"]) == 0

    write(root / "create-auto-send", "create-request.json", sample("destination.create_request.preview-confirm.json"))
    assert dc.main(["x", str(root), "--preset", "jvm"]) == 1
    assert dc.main(["x", str(root), "--preset", "nope"]) == 2
    assert dc.main(["x", str(root)]) == 2
    capsys.readouterr()


def test_the_checker_is_pure_it_leaves_its_input_untouched(tree):
    root, expected = tree
    before = {p: p.read_bytes() for p in root.rglob("*.json")}

    dc.check_tree(root, expected, strict=True)

    assert before == {p: p.read_bytes() for p in root.rglob("*.json")}
    assert copy.deepcopy(expected) == expected
