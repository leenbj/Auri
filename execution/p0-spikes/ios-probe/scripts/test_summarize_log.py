import json
from pathlib import Path

from summarize_log import summarize_jsonl_lines


def _line(kind: str, data: dict | None = None, app_state: str = "active", mono_ns: int = 1000) -> str:
    return json.dumps({
        "seq": 1,
        "launch_id": "test-launch",
        "mono_ns": mono_ns,
        "wall": "2026-11-17T00:00:00Z",
        "kind": kind,
        "app_state": app_state,
        "data": data or {},
    })


def test_counts_volume_stops_false_stops_and_missed_stops_by_scenario():
    lines = [
        _line("retest_marker", {"kind": "intended_stop", "scenario": "middle"}),
        _line("volume_stop_triggered", {"scenario": "middle", "direction_pair": "up->down", "dt_ms": 550}),
        _line("capture_stopped", {"scenario": "middle", "reason": "volume_gesture", "accepted_frames": 16000, "write_failures": 0}),
        _line("retest_marker", {"kind": "ordinary_volume_adjust", "scenario": "middle"}),
        _line("volume_stop_triggered", {"scenario": "middle", "direction_pair": "up->down", "dt_ms": 900}),
        _line("retest_marker", {"kind": "false_stop", "scenario": "middle"}),
        _line("retest_marker", {"kind": "intended_stop", "scenario": "bottom"}),
        _line("retest_marker", {"kind": "missed_stop", "scenario": "bottom"}),
        _line("capture_stopped", {"scenario": "bottom", "reason": "screen_control", "accepted_frames": 8000, "write_failures": 0}),
    ]

    summary = summarize_jsonl_lines(lines)

    assert summary["totals"]["intended_stop"] == 2
    assert summary["totals"]["volume_stop_triggered"] == 2
    assert summary["totals"]["ordinary_volume_adjust"] == 1
    assert summary["totals"]["false_stop"] == 1
    assert summary["totals"]["missed_stop"] == 1
    assert summary["stop_reasons"] == {"volume_gesture": 1, "screen_control": 1}
    assert summary["by_scenario"]["middle"]["false_stop"] == 1
    assert summary["by_scenario"]["bottom"]["missed_stop"] == 1


def test_tracks_volume_decisions_background_locks_and_blocks():
    lines = [
        "",
        _line("protected_data_unavailable", app_state="background"),
        _line("output_volume_changed", {"decision": "ignored", "boundary": "top"}, app_state="background"),
        _line("output_volume_changed", {"decision": "armed_first_step", "boundary": "top"}, app_state="background"),
        _line("output_volume_changed", {"decision": "triggered_stop", "boundary": "top"}, app_state="background"),
        _line("capture_blocked", {"reason": "no_bluetooth_input_available"}, app_state="active"),
        _line("capture_stopped", {"reason": "route_lost", "write_failures": 2}, app_state="background"),
    ]

    summary = summarize_jsonl_lines(lines)

    assert summary["background_events"] == 5
    assert summary["protected_data_unavailable_events"] == 1
    assert summary["route_gate_blocks"] == 1
    assert summary["write_failures_total"] == 2
    assert summary["volume_decisions"] == {
        "ignored": 1,
        "armed_first_step": 1,
        "triggered_stop": 1,
    }
