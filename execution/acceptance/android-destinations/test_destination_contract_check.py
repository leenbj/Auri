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


def additions(destination_ids, *, intent="context") -> dict:
    value = sample("revision.delivery_additions.confirmed-preview.json")
    value["intent"] = intent
    value["destination_ids"] = list(destination_ids)
    return value


def write(dir_: pathlib.Path, name: str, value) -> None:
    dir_.mkdir(parents=True, exist_ok=True)
    (dir_ / name).write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")


def observed(*, puts, additions=0, deliveries, destination_ids) -> dict:
    return {"puts": puts, "additions": additions, "deliveries": deliveries, "destination_ids": list(destination_ids)}


def good_tree(root: pathlib.Path) -> dict[str, str]:
    """六个场景各写一份合规的输出；返回期望清单。"""
    write(root / "create-auto-send", "create-request.json", sample("destination.create_request.auto-send.json"))
    write(root / "create-preview-confirm", "create-request.json", sample("destination.create_request.preview-confirm.json"))
    write(root / "auto-send-unreviewed", "submission.json", submission([AUTO_ID]))
    write(root / "auto-send-unreviewed", "scenario.json", observed(puts=1, deliveries=1, destination_ids=[AUTO_ID]))
    # 预览确认且没确认：文字已入库、没点名任何目的地、没有 Delivery（评审 B2）
    write(root / "preview-held-before-confirm", "submission.json", submission([]))
    write(root / "preview-held-before-confirm", "scenario.json", observed(puts=1, deliveries=0, destination_ids=[]))
    # 确认之后：Revision 不重发，追加请求补上它的 Delivery
    write(root / "preview-after-confirm", "submission.json", submission([]))
    write(root / "preview-after-confirm", "additions-request.json", additions([PREVIEW_ID]))
    write(root / "preview-after-confirm", "scenario.json", observed(puts=1, additions=1, deliveries=1, destination_ids=[PREVIEW_ID]))
    write(root / "action-request-tag", "submission.json", submission([PREVIEW_ID], intent="action_request"))
    write(root / "action-request-tag", "scenario.json", observed(puts=1, deliveries=1, destination_ids=[PREVIEW_ID]))
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


@pytest.mark.parametrize("scenario", ["preview-held-before-confirm", "preview-after-confirm", "action-request-tag"])
def test_every_draft_the_phone_sends_is_still_not_reviewed_whatever_the_send_path(tree, scenario):
    root, expected = tree
    named = json.loads((root / scenario / "submission.json").read_text(encoding="utf-8"))["publication"]
    write(root / scenario, "submission.json", submission(named["destination_ids"], intent=named["intent"], review_state="reviewed"))

    problems = problems_of(root, expected, scenario)

    assert any("必须是 not_reviewed" in p for p in problems), problems


def test_a_submission_without_destinations_in_an_auto_send_scenario_is_rejected(tree):
    root, expected = tree
    write(root / "auto-send-unreviewed", "submission.json", submission([]))
    write(root / "auto-send-unreviewed", "scenario.json", observed(puts=1, deliveries=0, destination_ids=[]))

    assert any("至少一个目的地" in p for p in problems_of(root, expected, "auto-send-unreviewed"))


def test_the_destinations_in_the_request_must_be_the_ones_the_scenario_recorded(tree):
    root, expected = tree
    write(root / "auto-send-unreviewed", "scenario.json", observed(puts=1, deliveries=1, destination_ids=[PREVIEW_ID]))

    assert any("不一致" in p for p in problems_of(root, expected, "auto-send-unreviewed"))


def test_the_action_request_scenario_must_carry_that_tag(tree):
    root, expected = tree
    write(root / "action-request-tag", "submission.json", submission([PREVIEW_ID], intent="context"))

    assert any("action_request" in p for p in problems_of(root, expected, "action-request-tag"))


@pytest.mark.parametrize("puts, deliveries, additions_seen", [(2, 1, 0), (1, 2, 0), (0, 1, 0), (1, 1, 1)])
def test_the_gateway_must_have_seen_exactly_one_put_and_one_delivery_per_destination_and_no_addition(tree, puts, deliveries, additions_seen):
    root, expected = tree
    write(root / "auto-send-unreviewed", "scenario.json", observed(puts=puts, additions=additions_seen, deliveries=deliveries, destination_ids=[AUTO_ID]))

    assert problems_of(root, expected, "auto-send-unreviewed"), (puts, deliveries, additions_seen)


def test_an_auto_send_scenario_that_also_added_deliveries_is_rejected_the_request_already_named_them(tree):
    root, expected = tree
    write(root / "auto-send-unreviewed", "additions-request.json", additions([AUTO_ID]))

    assert any("不应当还有追加请求" in p for p in problems_of(root, expected, "auto-send-unreviewed"))


def test_a_create_scenario_that_also_added_deliveries_is_rejected(tree):
    root, expected = tree
    write(root / "create-auto-send", "additions-request.json", additions([AUTO_ID]))

    assert any("不应当有追加请求" in p for p in problems_of(root, expected, "create-auto-send"))


def test_a_held_scenario_whose_submission_names_the_preview_destination_is_rejected_that_would_create_the_delivery_early(tree):
    root, expected = tree
    write(root / "preview-held-before-confirm", "submission.json", submission([PREVIEW_ID]))
    write(root / "preview-held-before-confirm", "scenario.json", observed(puts=1, deliveries=1, destination_ids=[PREVIEW_ID]))

    problems = problems_of(root, expected, "preview-held-before-confirm")

    assert any("不得点名任何目的地" in p for p in problems), problems


def test_a_held_scenario_without_a_submission_is_rejected_the_text_must_already_be_on_the_gateway(tree):
    root, expected = tree
    (root / "preview-held-before-confirm" / "submission.json").unlink()
    write(root / "preview-held-before-confirm", "scenario.json", observed(puts=0, deliveries=0, destination_ids=[]))

    problems = problems_of(root, expected, "preview-held-before-confirm")

    assert any("文字也必须已经入库" in p for p in problems), problems
    assert any("恰好一次 PUT" in p for p in problems), problems


@pytest.mark.parametrize(
    "puts, additions_seen, deliveries, destination_ids",
    [(2, 0, 0, []), (0, 0, 0, []), (1, 1, 0, []), (1, 0, 1, []), (1, 0, 0, [PREVIEW_ID])],
)
def test_a_held_scenario_where_the_gateway_saw_anything_but_the_text_is_rejected(tree, puts, additions_seen, deliveries, destination_ids):
    root, expected = tree
    write(root / "preview-held-before-confirm", "scenario.json", observed(puts=puts, additions=additions_seen, deliveries=deliveries, destination_ids=destination_ids))

    assert any("恰好一次 PUT" in p for p in problems_of(root, expected, "preview-held-before-confirm"))


def test_a_held_scenario_with_an_addition_request_is_rejected_nothing_may_be_added_before_the_user_confirms(tree):
    root, expected = tree
    write(root / "preview-held-before-confirm", "additions-request.json", additions([PREVIEW_ID]))

    assert any("不得有追加请求" in p for p in problems_of(root, expected, "preview-held-before-confirm"))


def test_a_held_scenario_without_the_gateway_side_observation_proves_nothing(tree):
    root, expected = tree
    (root / "preview-held-before-confirm" / "scenario.json").unlink()

    assert problems_of(root, expected, "preview-held-before-confirm")


def test_an_addition_scenario_without_the_addition_request_is_rejected_the_delivery_never_got_made(tree):
    root, expected = tree
    (root / "preview-after-confirm" / "additions-request.json").unlink()

    assert any("必须有追加 Delivery 的请求" in p for p in problems_of(root, expected, "preview-after-confirm"))


def test_an_addition_request_that_breaks_the_schema_is_reported_with_the_contract_name(tree):
    root, expected = tree
    broken = additions([PREVIEW_ID])
    broken["destination_ids"] = []  # 合同要求至少一个目的地
    write(root / "preview-after-confirm", "additions-request.json", broken)

    problems = problems_of(root, expected, "preview-after-confirm")

    assert any("delivery_additions" in p and "destination_ids" in p for p in problems), problems


def test_an_addition_request_naming_other_destinations_than_the_confirmed_one_is_rejected(tree):
    root, expected = tree
    write(root / "preview-after-confirm", "additions-request.json", additions([AUTO_ID]))

    assert any("追加的目的地与场景记下的目的地不一致" in p for p in problems_of(root, expected, "preview-after-confirm"))


def test_an_addition_request_that_changes_the_default_label_is_rejected(tree):
    root, expected = tree
    write(root / "preview-after-confirm", "additions-request.json", additions([PREVIEW_ID], intent="action_request"))

    assert any("追加请求的 intent 必须是默认的 context" in p for p in problems_of(root, expected, "preview-after-confirm"))


def test_a_confirmed_destination_named_in_the_first_submission_is_rejected_the_delivery_would_exist_before_the_confirmation(tree):
    root, expected = tree
    write(root / "preview-after-confirm", "submission.json", submission([PREVIEW_ID]))

    problems = problems_of(root, expected, "preview-after-confirm")

    assert any("不得出现在入库请求里" in p for p in problems), problems


@pytest.mark.parametrize(
    "puts, additions_seen, deliveries",
    [(2, 1, 1), (0, 1, 1), (1, 0, 1), (1, 2, 1), (1, 1, 0), (1, 1, 2)],
)
def test_the_gateway_must_have_seen_one_put_one_addition_and_one_delivery_after_the_confirmation(tree, puts, additions_seen, deliveries):
    root, expected = tree
    write(root / "preview-after-confirm", "scenario.json", observed(puts=puts, additions=additions_seen, deliveries=deliveries, destination_ids=[PREVIEW_ID]))

    assert problems_of(root, expected, "preview-after-confirm"), (puts, additions_seen, deliveries)


def test_a_scenario_file_without_the_addition_count_is_rejected_the_observation_must_cover_additions(tree):
    root, expected = tree
    write(root / "preview-after-confirm", "scenario.json", {"puts": 1, "deliveries": 1, "destination_ids": [PREVIEW_ID]})

    assert any("additions" in p for p in problems_of(root, expected, "preview-after-confirm"))


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


def test_the_retired_submission_preview_expectation_is_unknown_so_a_stale_scenario_list_fails_loudly(tree):
    root, _ = tree  # 评审 B2 之前预览确认的场景期望是 submission:preview（确认后才入库）；现在是 addition

    problems = dc.check_scenario(root / "preview-after-confirm", "submission:preview")

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
