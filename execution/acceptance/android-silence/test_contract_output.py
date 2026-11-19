"""Check JSON emitted by the JVM capture/record pipeline against the frozen contract."""
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "execution" / "acceptance" / "android-capture"))
import capture_contract_check as checker  # noqa: E402

OUTPUT = ROOT / "apps" / "android" / "capture" / "build" / "contract-out" / "silence-jvm"
EXPECTED = {
    "speech": "with-text:complete",
    "mixed": "with-text:complete",
    "no-speech": "no-speech:complete",
    "limit": "with-text:complete",
}


def test_real_recording_results_satisfy_schema_and_semantics():
    assert checker.check_tree(OUTPUT, EXPECTED, strict=True) == []


def test_result_scenarios_preserve_timeline_and_end_reason():
    def result(name):
        return json.loads((OUTPUT / name / "recording-result.json").read_text())

    mixed = result("mixed")
    assert mixed["capture_duration_ms"] == 6000
    assert mixed["speech_duration_ms"] == 3000
    assert mixed["verified_ranges"] == [{"start_ms": 800, "end_ms": 2300}, {"start_ms": 3800, "end_ms": 5300}]
    assert result("no-speech")["chunks"] == []
    assert result("no-speech")["verified_ranges"] == []
    assert result("limit")["end_reason"] == "duration_limit"


def test_mutated_speech_duration_is_rejected():
    mixed = json.loads((OUTPUT / "mixed" / "recording-result.json").read_text())
    mixed["speech_duration_ms"] += 1
    violations = checker.semantic_rules.violations("adapter.schema.json#/$defs/recording_result", mixed)
    assert "SR-04" in violations
