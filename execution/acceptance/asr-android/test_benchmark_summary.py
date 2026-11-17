"""benchmark_summary 的测试：运行报告必须先过校验才能进结果表，坏数据与泄露都要被拦住。

基线报告的数字取自 2026-11-18 在 API 35 模拟器上用 `am start -S -W` 冷启动实测的第一份 run-report.json
（只拿结构与量级，不代表任何设备）。变形后的坏报告证明校验器真的会失败，而不是对什么都说“通过”。
"""
import copy
import json

import pytest

import benchmark_summary as bs

GOOD = {
    "report_version": 1,
    "final_state": "SUCCEEDED",
    "audio": {"duration_ms": 5592, "sample_rate_hz": 16000, "channels": 1},
    "metrics": {"verify_ms": 612, "engine_load_ms": 906, "resample_ms": 3, "inference_ms": 108, "rtf": 108 / 5592},
    "process": {
        "start_to_first_result_ms": 2028,
        "vm_hwm_kb_before": 145344,
        "vm_hwm_kb_after": 504912,
        "vm_rss_kb_after": 502460,
        "uid_network_bytes_delta": 0,
    },
    "warm_inference_ms": [104, 104, 105],
    "engine": {"version": "2.0.1", "onnxruntime_version": "1.28.2", "git_sha1": "af2d8d81", "num_threads": 2},
    "model": {"model_id": "sensevoice-small-int8", "model_version": "2024-07-17+7d1efa2138a6"},
    "device": {"model": "sdk_gphone64_arm64", "hardware": "ranchu", "fingerprint": "google/x", "sdk_int": 35, "abis": ["arm64-v8a"]},
}


def mutated(change):
    report = copy.deepcopy(GOOD)
    change(report)
    return report


def test_a_complete_cold_run_report_has_no_problems():
    assert bs.validate_report(GOOD, expect_warm_repeats=3) == []


def test_a_report_that_is_not_a_success_cannot_be_benchmark_evidence():
    problems = bs.validate_report(mutated(lambda r: r.update(final_state="WAITING_MODEL")))

    assert any("final_state" in p for p in problems), problems


def test_rtf_must_equal_inference_time_over_audio_duration():
    """NFR-006 的定义；Kotlin 把分子分母弄反或换了单位，这里要抓到。"""
    problems = bs.validate_report(mutated(lambda r: r["metrics"].update(rtf=5592 / 108)))

    assert any("rtf" in p for p in problems), problems


def test_a_missing_rtf_is_a_problem_not_zero():
    problems = bs.validate_report(mutated(lambda r: r["metrics"].update(rtf=None)))

    assert any("rtf" in p for p in problems), problems


@pytest.mark.parametrize("bytes_delta", [1234, None])
def test_offline_evidence_needs_a_measured_zero_byte_delta(bytes_delta):
    problems = bs.validate_report(mutated(lambda r: r["process"].update(uid_network_bytes_delta=bytes_delta)))

    assert any("uid_network_bytes_delta" in p for p in problems), problems


@pytest.mark.parametrize("field", ["vm_hwm_kb_before", "vm_hwm_kb_after", "vm_rss_kb_after"])
def test_memory_readings_that_could_not_be_taken_are_problems(field):
    problems = bs.validate_report(mutated(lambda r: r["process"].update({field: None})))

    assert any(field in p for p in problems), problems


def test_peak_memory_cannot_shrink_during_the_run():
    problems = bs.validate_report(mutated(lambda r: r["process"].update(vm_hwm_kb_after=100000)))

    assert any("vm_hwm_kb" in p for p in problems), problems


def test_the_warm_run_count_must_match_what_was_requested():
    problems = bs.validate_report(GOOD, expect_warm_repeats=5)

    assert any("warm_inference_ms" in p for p in problems), problems


def test_a_non_positive_cold_start_time_is_rejected():
    problems = bs.validate_report(mutated(lambda r: r["process"].update(start_to_first_result_ms=0)))

    assert any("start_to_first_result_ms" in p for p in problems), problems


def test_an_unknown_field_is_rejected_so_transcript_text_cannot_sneak_into_reports():
    """日志与报告默认不含正文（CLAUDE.md）；以后谁往报告里加 text，这里立刻红。"""
    problems = bs.validate_report(mutated(lambda r: r.update(text="口述内容")))

    assert any("text" in p for p in problems), problems


def test_a_missing_section_is_reported_instead_of_raising():
    report = copy.deepcopy(GOOD)
    del report["process"]

    assert any("process" in p for p in bs.validate_report(report))


def test_the_table_shows_each_run_and_the_median_of_the_cold_starts():
    second = mutated(lambda r: r["process"].update(start_to_first_result_ms=2200))
    third = mutated(lambda r: r["process"].update(start_to_first_result_ms=2100))

    table = bs.render_table({"冷启动 1": GOOD, "冷启动 2": second, "冷启动 3": third}, evidence="E2 模拟器")

    assert "E2 模拟器" in table
    for number in ("2028", "2200", "2100"):
        assert number in table
    median_row = next(line for line in table.splitlines() if line.startswith("| 中位数"))
    assert "2100" in median_row
    assert "0.0193" in table  # RTF 108/5592 保留四位小数
    assert "493.1" in table  # VmHWM 504912 KiB / 1024 = 493.08 MiB


def test_the_table_never_prints_transcript_text_or_the_device_fingerprint():
    table = bs.render_table({"冷启动 1": GOOD}, evidence="E2 模拟器")

    assert "google/x" not in table  # 指纹留在报告里，不进对外结果表


def test_cli_prints_the_table_for_valid_reports_and_fails_for_bad_ones(tmp_path, capsys):
    good = tmp_path / "good.json"
    good.write_text(json.dumps(GOOD), encoding="utf-8")
    bad = tmp_path / "bad.json"
    bad.write_text(json.dumps(mutated(lambda r: r.update(final_state="FAILED"))), encoding="utf-8")

    assert bs.main(["benchmark_summary.py", "--evidence", "E2 模拟器", "--warm", "3", f"冷启动 1={good}"]) == 0
    assert "| 冷启动 1 |" in capsys.readouterr().out

    assert bs.main(["benchmark_summary.py", "--evidence", "E2 模拟器", f"坏的={bad}"]) == 1
    assert "final_state" in capsys.readouterr().out


def test_cli_without_reports_prints_usage(capsys):
    assert bs.main(["benchmark_summary.py", "--evidence", "E2 模拟器"]) == 2
