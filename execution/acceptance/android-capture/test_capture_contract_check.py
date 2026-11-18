"""合同校验器自身的测试：它必须真的能拦住不合规的输出。

capture_contract_check.py 检查 :capture 的 ContractOutputTest 写出的 JSON。如果它对什么都说“通过”，
test_capture_contract_output.py 的通过就毫无意义，所以这里用手造的坏数据证明它会失败。
好数据的基线取自合同自带的有效样本（contracts/fixtures/messages/valid/，同一组样本互相一致：会话、纪元、时间段、耳机名、模型），
不是自己编的。
"""
import json
import pathlib

import pytest

import capture_contract_check as cc

REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]
VALID = REPO_ROOT / "contracts" / "fixtures" / "messages" / "valid"
SECRET = "SECRET-TRANSCRIPT-MARKER"


def fixture_instance(name: str) -> dict:
    return json.loads((VALID / name).read_text(encoding="utf-8"))["instance"]


def write(dir_: pathlib.Path, name: str, value) -> None:
    dir_.mkdir(parents=True, exist_ok=True)
    (dir_ / name).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def make_scenario(root: pathlib.Path, name: str = "complete-with-text", *, result_fixture="adapter.recording_result.complete-two-speech-ranges.json", text=True):
    """写出一个有效的场景目录（默认是“完整 + 有文字”），测试再从它出发改坏一处。"""
    dir_ = root / name
    result = fixture_instance(result_fixture)
    write(dir_, "recording-result.json", result)
    evidence = fixture_instance("adapter.route_change_event.bluetooth-hfp-verified.json")
    evidence["capture_session_id"] = result["capture_session_id"]  # 样本是各自独立的：对齐会话
    if result["chunks"]:
        evidence["epoch"] = result["chunks"][0]["route_epoch"]
    write(dir_, "route-evidence.json", evidence)
    write(dir_, "capabilities.json", fixture_instance("adapter.asr_capabilities.local-engine.json"))
    write(dir_, "task-handle.json", fixture_instance("adapter.asr_task_handle.local-job.json"))
    if text:
        submission = fixture_instance("revision.submission.asr-first-revision.json")
        write(dir_, "capture-block.json", submission["capture"])
        write(dir_, "revision-content.json", submission["content"])
    return dir_


def edit(dir_: pathlib.Path, name: str, change) -> None:
    value = json.loads((dir_ / name).read_text(encoding="utf-8"))
    change(value)
    write(dir_, name, value)


def test_a_fully_valid_scenario_with_text_has_no_problems(tmp_path):
    assert cc.check_scenario(make_scenario(tmp_path), "with-text:complete") == []


def test_a_confirmed_route_with_no_speech_and_no_text_has_no_problems(tmp_path):
    dir_ = make_scenario(tmp_path, "complete-no-speech", result_fixture="adapter.recording_result.no-speech.json", text=False)

    assert cc.check_scenario(dir_, "no-speech:complete") == []


def test_a_truncated_recording_whose_status_and_end_reason_agree_is_valid_as_partial(tmp_path):
    dir_ = make_scenario(tmp_path, "partial-route-lost", result_fixture="adapter.recording_result.partial-route-lost.json", text=False)
    submission = fixture_instance("revision.submission.partial-route-lost.json")
    write(dir_, "capture-block.json", submission["capture"])
    write(dir_, "revision-content.json", submission["content"])

    assert cc.check_scenario(dir_, "with-text:partial") == []


def test_wrong_text_hash_is_reported_as_sr01(tmp_path):
    dir_ = make_scenario(tmp_path)
    edit(dir_, "revision-content.json", lambda c: c.update(text_sha256="0" * 64))

    assert any("SR-01" in p for p in cc.check_scenario(dir_, "with-text:complete"))


def test_speech_ranges_that_overrun_the_capture_are_reported_as_sr03(tmp_path):
    dir_ = make_scenario(tmp_path)
    edit(dir_, "recording-result.json", lambda r: r.update(capture_duration_ms=r["verified_ranges"][-1]["end_ms"] - 1))

    assert any("SR-03" in p for p in cc.check_scenario(dir_, "with-text:complete"))


def test_a_cloud_capability_is_reported_as_sr05(tmp_path):
    dir_ = make_scenario(tmp_path)
    edit(dir_, "capabilities.json", lambda c: c.update(execution_location="commercial_cloud"))

    assert any("SR-05" in p for p in cc.check_scenario(dir_, "with-text:complete"))


def test_audio_from_another_route_epoch_is_rejected(tmp_path):
    """路由证据是 epoch 3，音频块却声称来自 epoch 4：音频不是来自已确认的那条路由。"""
    dir_ = make_scenario(tmp_path)
    edit(dir_, "recording-result.json", lambda r: r["chunks"][0].update(route_epoch=r["chunks"][0]["route_epoch"] + 1))

    problems = cc.check_scenario(dir_, "with-text:complete")

    assert any("route_epoch" in p for p in problems), problems


def test_evidence_that_is_not_verified_cannot_back_a_record(tmp_path):
    dir_ = make_scenario(tmp_path)
    edit(dir_, "route-evidence.json", lambda e: e.update(verification_state="mismatch", observed_input={"route_type": "built_in_mic", "display_name": None}))

    problems = cc.check_scenario(dir_, "with-text:complete")

    assert any("verified" in p for p in problems), problems


def test_evidence_from_another_capture_session_is_rejected(tmp_path):
    dir_ = make_scenario(tmp_path)
    edit(dir_, "route-evidence.json", lambda e: e.update(capture_session_id="00000000-0000-4000-8000-000000000042"))

    assert any("capture_session_id" in p for p in cc.check_scenario(dir_, "with-text:complete"))


def test_the_revision_must_name_the_model_that_the_capabilities_declare(tmp_path):
    dir_ = make_scenario(tmp_path)
    edit(dir_, "revision-content.json", lambda c: c.update(model_id="some-other-model"))

    problems = cc.check_scenario(dir_, "with-text:complete")

    assert any("model_id" in p for p in problems), problems
    assert not any("some-other-model" in p for p in problems), "值不回显"


@pytest.mark.parametrize(
    "field,value",
    [("end_reason", "volume_gesture"), ("capture_duration_ms", 18501), ("speech_duration_ms", 9199), ("input_route_type", "bluetooth_le_audio"), ("headset_display_name", "别的耳机")],
)
def test_the_capture_block_must_describe_the_same_recording_as_the_result_and_evidence(tmp_path, field, value):
    dir_ = make_scenario(tmp_path)
    edit(dir_, "capture-block.json", lambda b: b.update({field: value}))

    problems = cc.check_scenario(dir_, "with-text:complete")

    assert any(field in p for p in problems), problems


def test_speech_segments_must_equal_the_verified_ranges(tmp_path):
    dir_ = make_scenario(tmp_path)
    edit(dir_, "capture-block.json", lambda b: b["speech_segments"][0].update(start_ms=b["speech_segments"][0]["start_ms"] + 1))

    problems = cc.check_scenario(dir_, "with-text:complete")

    assert any("speech_segments" in p for p in problems), problems


def test_a_scenario_that_should_have_text_but_has_no_revision_is_rejected(tmp_path):
    dir_ = make_scenario(tmp_path)
    (dir_ / "revision-content.json").unlink()

    assert any("revision-content.json" in p for p in cc.check_scenario(dir_, "with-text:complete"))


def test_a_no_speech_scenario_must_not_carry_text_or_a_capture_block(tmp_path):
    dir_ = make_scenario(tmp_path, "complete-no-speech", result_fixture="adapter.recording_result.no-speech.json", text=False)
    submission = fixture_instance("revision.submission.asr-first-revision.json")
    write(dir_, "revision-content.json", submission["content"])
    write(dir_, "capture-block.json", submission["capture"])

    problems = cc.check_scenario(dir_, "no-speech:complete")

    assert any("revision-content.json" in p for p in problems) and any("capture-block.json" in p for p in problems), problems


def test_the_expected_recording_status_is_enforced(tmp_path):
    dir_ = make_scenario(tmp_path)

    assert any("录音状态" in p for p in cc.check_scenario(dir_, "with-text:partial"))


def test_a_status_that_contradicts_the_end_reason_is_caught_by_the_schema(tmp_path):
    dir_ = make_scenario(tmp_path)
    edit(dir_, "recording-result.json", lambda r: r.update(status="partial"))

    assert any("recording_result" in p for p in cc.check_scenario(dir_, "with-text"))


def test_a_missing_required_file_is_reported(tmp_path):
    dir_ = make_scenario(tmp_path)
    (dir_ / "route-evidence.json").unlink()

    assert any("route-evidence.json: 文件不存在" in p for p in cc.check_scenario(dir_, "with-text:complete"))


def test_an_unknown_expectation_is_an_error_not_a_pass(tmp_path):
    assert cc.check_scenario(make_scenario(tmp_path), "succeeded")


def test_validator_messages_never_echo_values_from_the_output_files(tmp_path):
    """输出文件里的值可能是转录正文：哪怕它出现在被拒绝的字段里，报告也不能把它带出来。"""
    dir_ = make_scenario(tmp_path)
    edit(dir_, "revision-content.json", lambda c: c.update(language=SECRET, **{SECRET: SECRET}))
    edit(dir_, "capture-block.json", lambda b: b.update(headset_display_name=SECRET + "x" * 300))

    problems = cc.check_scenario(dir_, "with-text:complete")

    assert problems
    assert not any(SECRET in p for p in problems), problems


def test_a_file_that_is_not_utf8_json_is_reported_without_quoting_its_bytes(tmp_path):
    dir_ = make_scenario(tmp_path)
    (dir_ / "revision-content.json").write_bytes(b'{"text": "\xff\xfe"}')

    problems = cc.check_scenario(dir_, "with-text:complete")

    assert any("revision-content.json: 不是有效的 UTF-8 JSON" in p for p in problems)
    assert not any("\\xff" in p or "\xff" in p for p in problems)


def test_strict_tree_check_flags_missing_and_unregistered_scenarios(tmp_path):
    make_scenario(tmp_path, "complete-with-text")
    make_scenario(tmp_path, "brand-new-scenario")

    problems = cc.check_tree(tmp_path, {"complete-with-text": "with-text:complete", "never-written": "no-speech"}, strict=True)

    assert any("never-written" in p and "不存在" in p for p in problems)
    assert any("brand-new-scenario" in p and "不在预期清单里" in p for p in problems)


def test_command_line_exit_codes(tmp_path, capsys):
    make_scenario(tmp_path)

    assert cc.main(["x", str(tmp_path), "complete-with-text=with-text:complete"]) == 0
    assert cc.main(["x", str(tmp_path), "complete-with-text=no-speech"]) == 1
    assert cc.main(["x", str(tmp_path)]) == 2
    assert cc.main(["x", str(tmp_path), "--preset", "nope"]) == 2
    capsys.readouterr()


def test_the_registered_jvm_scenarios_are_the_three_the_kotlin_test_writes():
    """README 与 Kotlin 的 ContractOutputTest 都按这三个名字写；改名要三处一起改。"""
    assert set(cc.EXPECTED_JVM_SCENARIOS) == {"complete-with-text", "complete-no-speech", "partial-route-lost"}
