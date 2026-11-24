"""Ticket 23 变异表离线自检：确认每条原文恰好出现一次、覆盖清单 1–7 全部项。"""
from __future__ import annotations

from pathlib import Path
import pytest

from revision_mutation_check import ANDROID_DIR, MUTATIONS


def test_mutation_ids_are_unique():
    ids = [m.id for m in MUTATIONS]
    assert len(ids) == len(set(ids))


def test_all_seven_checklist_items_covered():
    covered = {m.checklist_item for m in MUTATIONS}
    assert covered == {1, 2, 3, 4, 5, 6, 7}


@pytest.mark.parametrize("mutation", MUTATIONS, ids=lambda m: m.id)
def test_mutation_target_exists_uniquely(mutation):
    target = ANDROID_DIR / mutation.rel_file
    assert target.is_file(), f"Missing target file: {target}"
    content = target.read_text(encoding="utf-8")
    assert content.count(mutation.old) == 1, f"{mutation.id}: expected 1 match for {mutation.old!r}"
    assert mutation.old != mutation.new
