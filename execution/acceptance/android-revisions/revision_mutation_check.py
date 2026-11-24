#!/usr/bin/env python3
"""Ticket 23 变异检查：故意破坏某条规则，确认预期测试真的会变红。

只在仓库外的临时快照（/tmp/auri-ticket23-mutation-snapshot）里改源码，绝不修改真实工作树。
退出码：0 = 全部被预期的测试拦住；1 = 有没被拦住的变异；2 = 变异表本身无效。
"""
from __future__ import annotations

import argparse
import dataclasses
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
ANDROID_DIR = REPO_ROOT / "apps" / "android"
SNAPSHOT_DIR = Path("/tmp/auri-ticket23-mutation-snapshot")


@dataclasses.dataclass(frozen=True)
class Mutation:
    id: str
    checklist_item: int
    rule: str
    rel_file: str
    old: str
    new: str
    gradle_task: str
    test_filter: str
    expected_failures: tuple[str, ...]
    xml_dir: str


MUTATIONS: tuple[Mutation, ...] = (
    Mutation(
        id="rev-save-allows-empty-text",
        checklist_item=1,
        rule="空白编辑必须被拒绝，不能产生空正文 Revision（SR-06 / revision.schema.json）",
        rel_file="sync/src/main/kotlin/app/auri/sync/revisions/RecordRevisionService.kt",
        old="if (newText.isEmpty()) return EditOutcome.Invalid(EditError.EMPTY_TEXT)",
        new="if (false && newText.isEmpty()) return EditOutcome.Invalid(EditError.EMPTY_TEXT)",
        gradle_task=":sync:test",
        test_filter="app.auri.sync.revisions.RecordRevisionServiceTest",
        expected_failures=("空白或超过 1 MiB 的编辑被拒绝，不产生新 Revision",),
        xml_dir="sync/build/test-results/test",
    ),
    Mutation(
        id="rev-edit-marks-unreviewed",
        checklist_item=1,
        rule="用户保存的修订必须标为 REVIEWED（FR-TXT-002）",
        rel_file="sync/src/main/kotlin/app/auri/sync/revisions/RecordRevisionService.kt",
        old="reviewState = ReviewState.REVIEWED,",
        new="reviewState = ReviewState.NOT_REVIEWED,",
        gradle_task=":sync:test",
        test_filter="app.auri.sync.revisions.RecordRevisionServiceTest",
        expected_failures=("保存修订产生新不可变 Revision，旧版本保留且不自动重发；日志不含正文",),
        xml_dir="sync/build/test-results/test",
    ),
    Mutation(
        id="rev-auto-resend-on-new-revision",
        checklist_item=2,
        rule="新 Revision（number > 1 或 USER_EDIT）默认不得自动规划发送目标（FR-TXT-002 / ADR-0009）",
        rel_file="syncplatform/src/main/kotlin/app/auri/syncplatform/dispatch/DispatchSql.kt",
        old="if (source != RevisionSource.ASR || key.revision != 1) return@transaction",
        new="if (false && (source != RevisionSource.ASR || key.revision != 1)) return@transaction",
        gradle_task=":syncplatform:testDebugUnitTest",
        test_filter="app.auri.syncplatform.dispatch.SqliteDispatchStoreContractTest",
        expected_failures=(
            "a_user_edit_revision_is_never_planned_or_held_it_is_only_stored",
            "a_later_asr_revision_such_as_a_retranscription_is_only_stored_never_sent_again",
        ),
        xml_dir="syncplatform/build/test-results/testDebugUnitTest",
    ),
    Mutation(
        id="rev-resend-ignores-eligibility",
        checklist_item=2,
        rule="重发请求必须校验 Destination 可接收且允许该意图",
        rel_file="sync/src/main/kotlin/app/auri/sync/revisions/RecordRevisionService.kt",
        old="if (result == ResendResult.Requested) onSyncNeeded()",
        new="onSyncNeeded()",
        gradle_task=":sync:test",
        test_filter="app.auri.sync.revisions.RecordRevisionServiceTest",
        expected_failures=("查询收到过旧版本的 Destination 并逐个确认重发（只发用户选的，重复确认幂等）",),
        xml_dir="sync/build/test-results/test",
    ),
    Mutation(
        id="delete-record-allows-resurrection",
        checklist_item=3,
        rule="已删除的 Record（墓碑存在）绝不能通过 addRecord 复活（FR-TXT-005 / SEC-005）",
        rel_file="capture/src/testFixtures/kotlin/app/auri/capture/testing/InMemoryRecordLibrary.kt",
        old="if (entry.recordId in tombstones) return@synchronized false",
        new="if (false && entry.recordId in tombstones) return@synchronized false",
        gradle_task=":capture:test",
        test_filter="app.auri.capture.library.InMemoryRecordLibraryTest",
        expected_failures=("删除 Record 留下墓碑：本地不可见，重复删除幂等，再次登记或发布修订都不会复活",),
        xml_dir="capture/build/test-results/test",
    ),
    Mutation(
        id="delete-sql-trigger-allows-single-revision-delete",
        checklist_item=3,
        rule="没有墓碑时 SQLite 触发器必须拒绝删除单个 Revision",
        rel_file="platform/src/main/kotlin/app/auri/platform/library/SqliteRecordLibrary.kt",
        old=(
            "\"CREATE TRIGGER revisions_immutable_update BEFORE UPDATE ON revisions BEGIN SELECT RAISE(ABORT, 'revision 不可改写'); END\",\n"
            "            \"\"\"\n"
            "            CREATE TRIGGER revisions_immutable_delete BEFORE DELETE ON revisions\n"
            "            WHEN NOT EXISTS (SELECT 1 FROM record_tombstones t WHERE t.record_id = OLD.record_id)"
        ),
        new=(
            "\"CREATE TRIGGER revisions_immutable_update BEFORE UPDATE ON revisions BEGIN SELECT RAISE(ABORT, 'revision 不可改写'); END\",\n"
            "            \"\"\"\n"
            "            CREATE TRIGGER revisions_immutable_delete BEFORE DELETE ON revisions\n"
            "            WHEN 1 = 0"
        ),
        gradle_task=":platform:testDebugUnitTest",
        test_filter="app.auri.platform.library.RecordTombstoneSqlTest",
        expected_failures=("没有墓碑时无法删除单个 Revision，写下墓碑后可删除整条 Record",),
        xml_dir="platform/build/test-results/testDebugUnitTest",
    ),
    Mutation(
        id="delete-backfill-resurrects-tombstoned-job",
        checklist_item=3,
        rule="SqliteSyncJobStore 回填必须排除已删除墓碑的 Record",
        rel_file="syncplatform/src/main/kotlin/app/auri/syncplatform/jobs/SqliteSyncJobStore.kt",
        old="AND NOT EXISTS (SELECT 1 FROM record_tombstones t WHERE t.record_id = r.record_id)",
        new="AND 1 = 1",
        gradle_task=":syncplatform:testDebugUnitTest",
        test_filter="app.auri.syncplatform.jobs.SyncJobsBackfillTest",
        expected_failures=("已有删除墓碑的 Record 不会被回填复活待同步任务",),
        xml_dir="syncplatform/build/test-results/testDebugUnitTest",
    ),
    Mutation(
        id="delete-gateway-accepts-wrong-record-id",
        checklist_item=3,
        rule="DELETE 响应里的 record_id 与请求不符时必须判为 Garbled",
        rel_file="sync/src/main/kotlin/app/auri/sync/gateway/GatewayClient.kt",
        old="if (deleted.recordId != recordId) throw Unreadable(\"answer is about a different record\")",
        new="if (false && deleted.recordId != recordId) throw Unreadable(\"answer is about a different record\")",
        gradle_task=":sync:test",
        test_filter="app.auri.sync.gateway.GatewayClientDeleteTest",
        expected_failures=("响应里的 record_id 与请求不一致时判为 Garbled",),
        xml_dir="sync/build/test-results/test",
    ),
    Mutation(
        id="delete-sync-skips-strict-local",
        checklist_item=3,
        rule="严格仅本地开启时 SyncEngine 不得发送 DELETE 请求（SEC-002）",
        rel_file="sync/src/main/kotlin/app/auri/sync/engine/SyncEngine.kt",
        old="syncDeletions(location)",
        new="// mutated: skipped syncDeletions(location)",
        gradle_task=":sync:test",
        test_filter="app.auri.sync.engine.SyncEngineDeletionTest",
        expected_failures=("待同步的删除墓碑会向网关发 DELETE 并标记已同步",),
        xml_dir="sync/build/test-results/test",
    ),
    Mutation(
        id="ui-hides-agent-cannot-recall-notice",
        checklist_item=4,
        rule="界面文案必须明确告知 Agent 已读走的内容无法收回（FR-TXT-005）",
        rel_file="app/src/main/kotlin/app/auri/android/revisions/RevisionDescriptions.kt",
        old="但 Agent 已读走并另存的内容无法收回。",
        new="删除后全部副本都会消失。",
        gradle_task=":app:testDebugUnitTest",
        test_filter="app.auri.android.revisions.RevisionLogPrivacyGuardTest",
        expected_failures=("界面文案明确说明 Agent 已读走的内容无法收回，且不夸大能力",),
        xml_dir="app/build/test-results/testDebugUnitTest",
    ),
    Mutation(
        id="audio-retention-default-not-30-days",
        checklist_item=5,
        rule="音频保留期默认必须是转录成功后 30 天（FR-TXT-005）",
        rel_file="capture/src/main/kotlin/app/auri/capture/library/AudioRetentionCleaner.kt",
        old="const val DEFAULT_RETENTION_DAYS = 30",
        new="const val DEFAULT_RETENTION_DAYS = 7",
        gradle_task=":capture:test",
        test_filter="app.auri.capture.library.AudioRetentionCleanerTest",
        expected_failures=("默认保留期是转录成功后 30 天",),
        xml_dir="capture/build/test-results/test",
    ),
    Mutation(
        id="audio-retention-purges-unexpired",
        checklist_item=5,
        rule="未满保留期的音频绝不能被提前清理（FR-TXT-005）",
        rel_file="capture/src/main/kotlin/app/auri/capture/library/AudioRetentionCleaner.kt",
        old="if (now.isBefore(transcribedAt.plus(retention))) continue",
        new="if (false && now.isBefore(transcribedAt.plus(retention))) continue",
        gradle_task=":capture:test",
        test_filter="app.auri.capture.library.AudioRetentionCleanerTest",
        expected_failures=("转录成功满 30 天的音频被自动清理并保留文字，未满 30 天与未转录成功的保留音频",),
        xml_dir="capture/build/test-results/test",
    ),
    Mutation(
        id="export-filename-leaks-text",
        checklist_item=6,
        rule="导出文件名不得泄露正文或敏感信息（FR-TXT-004）",
        rel_file="sync/src/main/kotlin/app/auri/sync/revisions/RecordExporter.kt",
        old='return "$base.${format.extension}"',
        new='return "秘密耳机-$base.${format.extension}"',
        gradle_task=":sync:test",
        test_filter="app.auri.sync.revisions.RecordFilterAndExportTest",
        expected_failures=("导出为 TXT 与 Markdown 包含正文、修订历史与截断标注，且文件名绝不泄露正文",),
        xml_dir="sync/build/test-results/test",
    ),
    Mutation(
        id="filter-ignores-keyword",
        checklist_item=6,
        rule="按关键词筛选必须匹配正文（FR-TXT-004）",
        rel_file="sync/src/main/kotlin/app/auri/sync/revisions/RecordFilter.kt",
        old="return entry.revisions.any { it.text.contains(keyword, ignoreCase = true) }",
        new="return true",
        gradle_task=":sync:test",
        test_filter="app.auri.sync.revisions.RecordFilterAndExportTest",
        expected_failures=("按时间范围、关键词和发送状态组合筛选",),
        xml_dir="sync/build/test-results/test",
    ),
    Mutation(
        id="log-interpolates-transcript-text",
        checklist_item=7,
        rule="日志默认不得包含正文（FR-TXT-005 / SEC-003）",
        rel_file="sync/src/main/kotlin/app/auri/sync/revisions/RecordRevisionService.kt",
        old='log.event("revision.edited number=${saved.number}")',
        new='log.event("revision.edited text=${saved.text}")',
        gradle_task=":sync:test",
        test_filter="app.auri.sync.revisions.RecordRevisionServiceTest",
        expected_failures=("保存修订产生新不可变 Revision，旧版本保留且不自动重发；日志不含正文",),
        xml_dir="sync/build/test-results/test",
    ),
)


def prepare_snapshot() -> Path:
    if SNAPSHOT_DIR.exists():
        shutil.rmtree(SNAPSHOT_DIR)
    shutil.copytree(
        ANDROID_DIR,
        SNAPSHOT_DIR,
        ignore=shutil.ignore_patterns("build", ".gradle", ".kotlin"),
    )
    return SNAPSHOT_DIR


def collect_failures(xml_dir: Path) -> tuple[int, list[str]]:
    total = 0
    failed: list[str] = []
    if not xml_dir.is_dir():
        return 0, []
    for xml_file in xml_dir.glob("TEST-*.xml"):
        root = ET.parse(xml_file).getroot()
        for tc in root.findall("testcase"):
            total += 1
            if tc.find("failure") is not None or tc.find("error") is not None:
                failed.append(tc.attrib.get("name", ""))
    return total, failed


def run_mutation(snapshot: Path, m: Mutation) -> tuple[str, int, int]:
    target = snapshot / m.rel_file
    original = target.read_text(encoding="utf-8")
    count = original.count(m.old)
    if count != 1:
        print(f"[INVALID] {m.id}: expected 1 occurrence in {m.rel_file}, found {count}", file=sys.stderr)
        return "INVALID", 0, 0

    xml_dir = snapshot / m.xml_dir
    if xml_dir.exists():
        shutil.rmtree(xml_dir)

    extra_args = os.environ.get(
        "AURI_GRADLE_ARGS",
        "--no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2",
    ).split()
    cmd = ["./gradlew", m.gradle_task, "--tests", m.test_filter, "--rerun", *extra_args]
    try:
        target.write_text(original.replace(m.old, m.new, 1), encoding="utf-8")
        subprocess.run(cmd, cwd=snapshot, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    finally:
        target.write_text(original, encoding="utf-8")

    total, failed = collect_failures(xml_dir)
    missing = [exp for exp in m.expected_failures if not any(exp in name for name in failed)]
    if missing:
        print(f"[SURVIVED] {m.id}: missing expected failures {missing}; actual failed={failed}", file=sys.stderr)
        return "SURVIVED", len(failed), total
    print(f"[CAUGHT] {m.id} ({len(failed)}/{total})")
    return "CAUGHT", len(failed), total


def main() -> int:
    parser = argparse.ArgumentParser(description="Ticket 23 mutation check")
    parser.add_argument("--list", action="store_true")
    parser.add_argument("--only", nargs="+")
    parser.add_argument("--match")
    args = parser.parse_args()

    selected = list(MUTATIONS)
    if args.only:
        wanted = set(args.only)
        selected = [m for m in selected if m.id in wanted]
    if args.match:
        pattern = re.compile(args.match)
        selected = [m for m in selected if pattern.search(m.id)]

    if args.list:
        for m in selected:
            print(f"{m.id}\titem={m.checklist_item}\t{m.rel_file}\t{m.rule}")
        return 0

    if not selected:
        print("No mutations selected.", file=sys.stderr)
        return 2

    snapshot = prepare_snapshot()
    try:
        statuses = [run_mutation(snapshot, m)[0] for m in selected]
    finally:
        shutil.rmtree(snapshot, ignore_errors=True)

    if any(s == "INVALID" for s in statuses):
        return 2
    if any(s != "CAUGHT" for s in statuses):
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
