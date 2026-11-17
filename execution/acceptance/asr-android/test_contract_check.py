"""合同校验器自身的测试：它必须真的能拦住不合规的输出。

校验器（contract_check.py）用来检查 Android 转录测试写出的 JSON。如果它对什么都说“通过”，
那 test_contract_output.py 的通过就毫无意义，所以这里用手造的坏数据证明它会失败。
好数据的基线取自合同自带的有效样本（contracts/fixtures/messages/valid/），不是自己编的。
"""
import json
import pathlib
import re

import contract_check as cc

REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]
VALID = REPO_ROOT / "contracts" / "fixtures" / "messages" / "valid"
JOB_ID = "7d76cbc4-6c81-551c-807c-14fa4691df3a"


def fixture_instance(name: str) -> dict:
    return json.loads((VALID / name).read_text(encoding="utf-8"))["instance"]


def write(dir_: pathlib.Path, name: str, value) -> None:
    dir_.mkdir(parents=True, exist_ok=True)
    (dir_ / name).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def good_trace(*steps):
    return {
        "machine": "transcription",
        "initial_state": "QUEUED",
        "steps": [{"event": e, "state_after": s} for e, s in steps],
    }


SUCCESS_STEPS = (("RUN_STARTED", "RUNNING"), ("RESULT_TEXT", "SUCCEEDED"))


def error_instance(code: str, operation_id: str | None = JOB_ID) -> dict:
    return {
        "schema_version": "1.1",
        "code": code,
        "message": "测试用错误。",
        "retryable": False,
        "required_action": None,
        "operation_id": operation_id,
    }


def make_scenario(root: pathlib.Path, name: str = "succeeded", *, steps=SUCCESS_STEPS, content=True, error=None):
    """写出一个有效的场景目录（默认是成功场景），测试再从它出发改坏一处。"""
    dir_ = root / name
    write(dir_, "capabilities.json", fixture_instance("adapter.asr_capabilities.local-engine.json"))
    handle = fixture_instance("adapter.asr_task_handle.local-job.json")
    assert handle["job_id"] == JOB_ID
    write(dir_, "task-handle.json", handle)
    write(dir_, "trace.json", good_trace(*steps))
    if content:
        write(dir_, "revision-content.json", fixture_instance("revision.submission.asr-first-revision.json")["content"])
    if error is not None:
        write(dir_, "error.json", error)
    return dir_


def edit(dir_: pathlib.Path, name: str, change) -> None:
    value = json.loads((dir_ / name).read_text(encoding="utf-8"))
    change(value)
    write(dir_, name, value)


def test_a_fully_valid_success_scenario_has_no_problems(tmp_path):
    assert cc.check_scenario(make_scenario(tmp_path), "SUCCEEDED") == []


def test_wrong_text_hash_is_reported_as_sr01(tmp_path):
    dir_ = make_scenario(tmp_path)
    edit(dir_, "revision-content.json", lambda c: c.update(text_sha256="0" * 64))

    assert any("SR-01" in p for p in cc.check_scenario(dir_, "SUCCEEDED"))


def test_hash_must_cover_the_raw_utf8_bytes_not_a_normalized_form(tmp_path):
    """SR-01：不做任何字符规范化。组合字符与预组合字符的哈希不同，写错就要被抓到。"""
    dir_ = make_scenario(tmp_path)
    decomposed = "e\u0301"  # é 的分解形式
    precomposed = "\u00e9"
    edit(dir_, "revision-content.json", lambda c: c.update(text=decomposed, text_sha256=cc.text_sha256(precomposed)))

    assert any("SR-01" in p for p in cc.check_scenario(dir_, "SUCCEEDED"))


def test_cloud_execution_location_is_rejected_by_the_real_schema(tmp_path):
    dir_ = make_scenario(tmp_path)
    edit(dir_, "revision-content.json", lambda c: c.update(execution_location="cloud"))

    problems = cc.check_scenario(dir_, "SUCCEEDED")

    assert any("execution_location" in p for p in problems), problems


def test_capabilities_claiming_cloud_are_rejected(tmp_path):
    dir_ = make_scenario(tmp_path)
    edit(dir_, "capabilities.json", lambda c: c.update(execution_location="cloud"))

    assert cc.check_scenario(dir_, "SUCCEEDED") != []


def test_a_task_handle_without_provider_job_id_is_rejected(tmp_path):
    dir_ = make_scenario(tmp_path)
    edit(dir_, "task-handle.json", lambda h: h.pop("provider_job_id"))

    assert any("provider_job_id" in p for p in cc.check_scenario(dir_, "SUCCEEDED"))


def test_an_error_code_missing_from_the_contract_is_rejected(tmp_path):
    """合同 1.1 没有“引擎内部失败”的错误码；硬造一个会被 schema 拦下（见 README 的合同缺口）。"""
    dir_ = make_scenario(
        tmp_path, "failed", steps=(("RUN_STARTED", "RUNNING"), ("PERMANENT_ERROR", "FAILED")),
        content=False, error=error_instance("ASR_ENGINE_FAILED"),
    )

    assert any("ASR_ENGINE_FAILED" in p for p in cc.check_scenario(dir_, "FAILED"))


def test_an_illegal_transition_in_the_trace_is_rejected(tmp_path):
    dir_ = make_scenario(tmp_path, steps=(("RUN_STARTED", "RUNNING"), ("RESULT_TEXT", "FAILED")))

    assert any("RESULT_TEXT" in p for p in cc.check_scenario(dir_, "FAILED"))


def test_a_trace_must_start_from_the_contract_initial_state(tmp_path):
    dir_ = make_scenario(tmp_path)
    edit(dir_, "trace.json", lambda t: t.update(initial_state="RUNNING"))

    assert any("initial_state" in p for p in cc.check_scenario(dir_, "SUCCEEDED"))


def test_an_event_the_state_cannot_take_is_rejected(tmp_path):
    dir_ = make_scenario(tmp_path, steps=(("RESULT_TEXT", "SUCCEEDED"),))  # QUEUED 不能直接收到 RESULT_TEXT

    assert any("RESULT_TEXT" in p for p in cc.check_scenario(dir_, "SUCCEEDED"))


def test_the_final_state_must_be_the_expected_one(tmp_path):
    dir_ = make_scenario(tmp_path)

    assert any("SUCCEEDED" in p and "NO_SPEECH" in p for p in cc.check_scenario(dir_, "NO_SPEECH"))


def test_success_without_revision_content_is_rejected(tmp_path):
    dir_ = make_scenario(tmp_path, content=False)

    assert any("revision-content.json" in p for p in cc.check_scenario(dir_, "SUCCEEDED"))


def test_no_speech_must_not_carry_a_revision(tmp_path):
    dir_ = make_scenario(
        tmp_path, "no-speech", steps=(("RUN_STARTED", "RUNNING"), ("RESULT_NO_SPEECH", "NO_SPEECH")),
        content=True, error=error_instance("ASR_NO_SPEECH"),
    )

    assert any("revision-content.json" in p for p in cc.check_scenario(dir_, "NO_SPEECH"))


def test_no_speech_must_use_the_no_speech_error_code(tmp_path):
    dir_ = make_scenario(
        tmp_path, "no-speech", steps=(("RUN_STARTED", "RUNNING"), ("RESULT_NO_SPEECH", "NO_SPEECH")),
        content=False, error=error_instance("MODEL_NOT_INSTALLED"),
    )

    assert any("MODEL_NOT_INSTALLED" in p for p in cc.check_scenario(dir_, "NO_SPEECH"))


def test_waiting_model_scenario_is_valid_with_a_model_error(tmp_path):
    dir_ = make_scenario(
        tmp_path, "waiting", steps=(("MODEL_MISSING", "WAITING_MODEL"),),
        content=False, error=error_instance("MODEL_INTEGRITY_FAILED"),
    )

    assert cc.check_scenario(dir_, "WAITING_MODEL") == []


def test_an_engine_failure_without_error_file_is_valid_because_the_contract_has_no_code(tmp_path):
    dir_ = make_scenario(tmp_path, "failed", steps=(("RUN_STARTED", "RUNNING"), ("PERMANENT_ERROR", "FAILED")), content=False)

    assert cc.check_scenario(dir_, "FAILED") == []


def test_error_operation_id_must_be_the_job_id(tmp_path):
    other = "8b3c1d2e-4f5a-4b6c-8d7e-9f0a1b2c3d4e"
    dir_ = make_scenario(
        tmp_path, "waiting", steps=(("MODEL_MISSING", "WAITING_MODEL"),),
        content=False, error=error_instance("MODEL_NOT_INSTALLED", operation_id=other),
    )

    assert any("operation_id" in p for p in cc.check_scenario(dir_, "WAITING_MODEL"))


def test_a_missing_file_is_a_problem_not_a_pass(tmp_path):
    dir_ = make_scenario(tmp_path)
    (dir_ / "trace.json").unlink()

    assert any("trace.json" in p for p in cc.check_scenario(dir_, "SUCCEEDED"))


def test_malformed_json_is_a_problem_not_a_crash(tmp_path):
    dir_ = make_scenario(tmp_path)
    (dir_ / "capabilities.json").write_text("{not json", encoding="utf-8")

    assert any("capabilities.json" in p for p in cc.check_scenario(dir_, "SUCCEEDED"))


def test_a_missing_scenario_directory_is_a_problem(tmp_path):
    make_scenario(tmp_path)

    problems = cc.check_tree(tmp_path, {"succeeded": "SUCCEEDED", "never-written": "NO_SPEECH"})

    assert any("never-written" in p for p in problems)


def test_an_empty_output_tree_is_a_problem(tmp_path):
    assert cc.check_tree(tmp_path, {"succeeded": "SUCCEEDED"}) != []


def test_every_error_code_the_kotlin_side_can_emit_exists_in_the_contract_enum():
    """Kotlin 的 ErrorCode 枚举与合同 common.schema.json 的 error_code 逐个对照。"""
    source = (REPO_ROOT / "apps/android/asr/src/main/kotlin/app/auri/asr/contract/AsrContract.kt").read_text(encoding="utf-8")
    body = re.search(r"enum class ErrorCode \{(.*?)\n\}", source, re.S).group(1)
    emitted = set(re.findall(r"^\s*([A-Z][A-Z0-9_]+),?\s*$", body, re.M))
    contract = set(json.loads((REPO_ROOT / "contracts/schemas/common.schema.json").read_text(encoding="utf-8"))["$defs"]["error_code"]["enum"])

    assert emitted, "没有从 AsrContract.kt 解析出任何错误码，解析规则需要更新"
    assert emitted <= contract, f"合同里没有这些错误码：{sorted(emitted - contract)}"


def test_an_expected_error_code_pins_the_exact_code_not_just_the_family(tmp_path):
    """WAITING_MODEL 允许两个错误码；磁盘上没装与文件被篡改是两回事，场景必须能钉死具体是哪个。"""
    dir_ = make_scenario(
        tmp_path, "waiting", steps=(("MODEL_MISSING", "WAITING_MODEL"),),
        content=False, error=error_instance("MODEL_INTEGRITY_FAILED"),
    )

    assert cc.check_scenario(dir_, "WAITING_MODEL:MODEL_INTEGRITY_FAILED") == []
    problems = cc.check_scenario(dir_, "WAITING_MODEL:MODEL_NOT_INSTALLED")
    assert any("MODEL_INTEGRITY_FAILED" in p and "MODEL_NOT_INSTALLED" in p for p in problems), problems


def test_an_expected_error_code_without_any_error_file_is_a_problem(tmp_path):
    dir_ = make_scenario(tmp_path, "failed", steps=(("RUN_STARTED", "RUNNING"), ("PERMANENT_ERROR", "FAILED")), content=False)

    problems = cc.check_scenario(dir_, "FAILED:ASR_RESOURCE_LIMIT")

    assert any("ASR_RESOURCE_LIMIT" in p and "没有 error.json" in p for p in problems), problems


def test_an_unknown_expected_state_is_reported_instead_of_crashing(tmp_path):
    problems = cc.check_scenario(make_scenario(tmp_path), "SUCCEDED")

    assert any("未知的期望终态" in p and "SUCCEDED" in p for p in problems), problems


def test_strict_tree_check_reports_directories_nobody_expects(tmp_path):
    """新增一个场景却忘了登记，它的输出就永远没人校验；严格模式把这种漏网之鱼变成失败。"""
    make_scenario(tmp_path, "succeeded")
    make_scenario(tmp_path, "surprise")

    assert cc.check_tree(tmp_path, {"succeeded": "SUCCEEDED"}) == []
    strict = cc.check_tree(tmp_path, {"succeeded": "SUCCEEDED"}, strict=True)
    assert any("surprise" in p for p in strict), strict


def test_every_preset_expectation_is_a_known_state_with_a_code_that_state_allows():
    """预设表自己写错（比如 WAITING_MODEL 配了 ASR_NO_SPEECH）会让校验永远对不上，先自检。"""
    for preset, scenarios in cc.PRESETS.items():
        assert scenarios, preset
        for name, expectation in scenarios.items():
            state, _, code = expectation.partition(":")
            assert state in cc.STATE_RULES, f"{preset}/{name}: 未知终态 {state}"
            if code:
                assert code in cc.STATE_RULES[state][1], f"{preset}/{name}: {state} 不允许错误码 {code}"


def test_device_preset_covers_the_refusal_and_airplane_scenarios_with_exact_codes():
    device = cc.PRESETS["device"]

    assert device["refused-corrupted"] == "WAITING_MODEL:MODEL_INTEGRITY_FAILED"
    assert device["refused-truncated"] == "WAITING_MODEL:MODEL_INTEGRITY_FAILED"
    assert device["refused-not-installed"] == "WAITING_MODEL:MODEL_NOT_INSTALLED"
    assert device["airplane-zh"] == "SUCCEEDED"
    assert {f"real-{lang}" for lang in ("zh", "en", "ja", "ko", "yue")} <= set(device)


def test_cli_preset_runs_the_named_expectations_and_is_strict(tmp_path, monkeypatch, capsys):
    make_scenario(tmp_path, "succeeded")
    monkeypatch.setitem(cc.PRESETS, "tiny", {"succeeded": "SUCCEEDED"})

    assert cc.main(["contract_check.py", str(tmp_path), "--preset", "tiny"]) == 0

    make_scenario(tmp_path, "surprise")
    assert cc.main(["contract_check.py", str(tmp_path), "--preset", "tiny"]) == 1
    assert "surprise" in capsys.readouterr().out


def test_cli_rejects_an_unknown_preset(tmp_path, capsys):
    assert cc.main(["contract_check.py", str(tmp_path), "--preset", "no-such-preset"]) == 2
    assert "no-such-preset" in capsys.readouterr().err
