#!/usr/bin/env python3
"""Summarize iOS probe JSONL logs for volume-gesture retest metrics."""

from __future__ import annotations

import argparse
import json
from collections import Counter, defaultdict
from pathlib import Path
from typing import Iterable


def summarize_jsonl_lines(lines: Iterable[str]) -> dict:
    """Parse probe-log.jsonl lines and count volume stops, false stops, and missed stops."""
    totals: Counter[str] = Counter()
    stop_reasons: Counter[str] = Counter()
    volume_decisions: Counter[str] = Counter()
    by_scenario: dict[str, Counter[str]] = defaultdict(Counter)
    background_events = 0
    protected_data_unavailable_events = 0
    write_failures_total = 0
    route_gate_blocks = 0

    for raw in lines:
        line = raw.strip()
        if not line:
            continue
        event = json.loads(line)
        kind = event.get("kind", "")
        data = event.get("data") or {}
        app_state = event.get("app_state", "unknown")
        scenario = str(data.get("scenario") or "unspecified")

        if app_state == "background":
            background_events += 1
        if kind == "protected_data_unavailable":
            protected_data_unavailable_events += 1
        elif kind == "output_volume_changed":
            decision = str(data.get("decision") or "unknown")
            volume_decisions[decision] += 1
        elif kind == "retest_marker":
            marker_kind = str(data.get("kind") or "")
            if marker_kind:
                totals[marker_kind] += 1
                by_scenario[scenario][marker_kind] += 1
        elif kind == "volume_stop_triggered":
            totals["volume_stop_triggered"] += 1
            by_scenario[scenario]["volume_stop_triggered"] += 1
        elif kind == "capture_stopped":
            reason = str(data.get("reason") or "unknown")
            stop_reasons[reason] += 1
            write_failures_total += int(data.get("write_failures") or 0)
        elif kind == "capture_blocked":
            route_gate_blocks += 1

    return {
        "totals": dict(totals),
        "stop_reasons": dict(stop_reasons),
        "volume_decisions": dict(volume_decisions),
        "by_scenario": {k: dict(v) for k, v in by_scenario.items()},
        "background_events": background_events,
        "protected_data_unavailable_events": protected_data_unavailable_events,
        "write_failures_total": write_failures_total,
        "route_gate_blocks": route_gate_blocks,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Summarize Auri iOS probe JSONL log.")
    parser.add_argument("log_path", type=Path, help="Path to probe-log.jsonl")
    args = parser.parse_args()
    with args.log_path.open("r", encoding="utf-8") as fh:
        summary = summarize_jsonl_lines(fh)
    print(json.dumps(summary, indent=2, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
