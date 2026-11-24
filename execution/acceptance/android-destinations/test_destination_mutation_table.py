"""Ticket 22 变异表自身的离线检查：表不能悄悄过期（做法同 android-sync/test_mutation_table.py）。

destination_mutation_check.py 里每条变异都要“恰好改一处生产代码”。生产代码一重构，原文找不到了，这条变异就会变成
“什么都没改、测试当然全绿”的假象。这里逐条核对：原文恰好出现一次、改动确实改了东西、预期变红的测试在源码里真的存在、
没有任何变异会碰到测试文件，并且“应用 → 还原”之后文件字节级不变（在临时副本上做，绝不碰真实工作树）。
"""
import pathlib
import re
import shutil
import sqlite3

import pytest

import destination_mutation_check as dm

REPO = dm.base.REPO
ANDROID_SOURCES = REPO / "apps" / "android"


@pytest.mark.parametrize("mutation", dm.MUTATIONS, ids=lambda m: m.id)
def test_mutation_edits_apply_exactly_once_and_only_touch_production_code(mutation):
    for edit in mutation.edits:
        text = (REPO / edit.path).read_text(encoding="utf-8")
        assert text.count(edit.old) == 1, f"{mutation.id}: {edit.path} 里原文出现 {text.count(edit.old)} 次"
        assert edit.old != edit.new
        # 生产代码 = 各模块的 src/main，加上网关自己的包（评审 M4：上限在网关里，真网关变异要改它）；tests/ 与任何测试文件仍然不许碰
        assert "/src/main/" in edit.path or edit.path.startswith("services/gateway/auri_gateway/"), "变异只能碰生产代码（src/main 或网关包），不能碰测试"
        assert not edit.path.endswith("Test.kt") and "/androidTest/" not in edit.path and "/testFixtures/" not in edit.path


GATEWAY_MUTATIONS = [m for m in dm.MUTATIONS if m.gradle and m.gradle[0] == dm.PYTEST]
KOTLIN_MUTATIONS = [m for m in dm.MUTATIONS if m not in GATEWAY_MUTATIONS]


@pytest.mark.parametrize("mutation", KOTLIN_MUTATIONS, ids=lambda m: m.id)
def test_every_expected_failing_test_exists_in_the_sources(mutation):
    assert mutation.must_fail, "一条变异必须指明哪些测试会因此变红"
    sources = "\n".join(
        p.read_text(encoding="utf-8")
        for p in ANDROID_SOURCES.rglob("*.kt")
        if "/src/" in str(p) and ("test" in str(p).lower())
    )
    for needle in mutation.must_fail:
        assert re.search(re.escape(needle), sources), f"{mutation.id}: 找不到预期变红的测试 {needle!r}"


@pytest.mark.parametrize("mutation", GATEWAY_MUTATIONS, ids=lambda m: m.id)
def test_a_gateway_mutation_names_pytest_files_that_exist_and_failing_tests_that_are_in_them(mutation):
    assert mutation.results_dir == dm.GATEWAY_RESULTS
    files = [REPO / arg for arg in mutation.gradle[1:]]
    assert files and all(f.is_file() and f.suffix == ".py" and f.relative_to(REPO).parts[0] == "tests" for f in files), mutation.gradle
    source = "\n".join(f.read_text(encoding="utf-8") for f in files)
    assert mutation.must_fail, "一条变异必须指明哪些测试会因此变红"
    for needle in mutation.must_fail:
        # 参数化的用例写成 `函数名[编号]`：函数要在文件里（与以前一样严格），编号要是文件里的一个字符串字面量（防止写错编号而永远匹配不上）
        function, _, param = needle.partition("[")
        assert re.search(rf"^(async )?def {re.escape(function)}\(", source, re.MULTILINE), f"{mutation.id}: 找不到预期变红的测试 {needle!r}"
        if param:
            assert param.endswith("]") and re.search(rf"""["']{re.escape(param[:-1])}["']""", source), f"{mutation.id}: 找不到参数化用例的编号 {needle!r}"


@pytest.mark.parametrize("mutation", GATEWAY_MUTATIONS, ids=lambda m: m.id)
def test_a_gateway_mutation_is_still_valid_python_or_sql_after_it_is_applied(mutation):
    # 变异成了语法错误：网关起不来、所有测试都红，会被误当成“测试抓住了它”。运行器里有同样的拦截（INVALID），这里提前证明表里没有这种条目
    migrations = REPO / "services" / "gateway" / "auri_gateway" / "migrations"
    for edit in mutation.edits:
        mutated = (REPO / edit.path).read_text(encoding="utf-8").replace(edit.old, edit.new)
        if edit.path.endswith(".py"):
            compile(mutated, edit.path, "exec")
        else:
            assert edit.path.endswith(".sql"), edit.path
            conn = sqlite3.connect(":memory:")
            try:  # 真实的前面几个迁移之上执行变异后的这一个：列名、表名、子查询写错会在这里报错
                for path in sorted(migrations.glob("*.sql")):
                    if path.name < pathlib.Path(edit.path).name:
                        conn.executescript(path.read_text(encoding="utf-8"))
                conn.executescript(mutated)
            finally:
                conn.close()


def test_the_gateway_runner_is_installed_and_gradle_entries_still_go_to_the_base_runner():
    assert dm.base.run_mutation is dm._run_mutation
    assert dm._BASE_RUN_MUTATION is not dm._run_mutation
    assert GATEWAY_MUTATIONS and KOTLIN_MUTATIONS


def test_mutation_ids_are_unique_and_documented():
    ids = [m.id for m in dm.MUTATIONS]
    assert len(ids) == len(set(ids))
    assert all(m.rule.strip() for m in dm.MUTATIONS)


def test_real_gateway_mutations_run_the_real_gateway_suite_and_the_others_do_not():
    for m in dm.MUTATIONS:
        real = m.gradle == dm.REAL
        assert real == m.id.startswith("real-gw-"), m.id
        assert m.results_dir == (dm.REAL_RESULTS if real else m.results_dir)
        if real:
            assert m.results_dir.endswith("realGatewayTest")


@pytest.mark.parametrize("mutation", dm.MUTATIONS, ids=lambda m: m.id)
def test_apply_then_restore_leaves_files_byte_identical(mutation, tmp_path, monkeypatch):
    # 只在临时副本上做：测试进程被杀也不会把故意写坏的代码留在真实工作树里
    for edit in mutation.edits:
        target = tmp_path / edit.path
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(REPO / edit.path, target)
    monkeypatch.setattr(dm.base, "REPO", tmp_path)
    paths = {tmp_path / e.path for e in mutation.edits}
    before = {p: p.read_bytes() for p in paths}

    originals = dm.base.apply_edits(mutation)
    try:
        assert any(p.read_bytes() != before[p] for p in paths), "应用变异后文件没有任何变化"
    finally:
        dm.base.restore(originals)

    assert {p: p.read_bytes() for p in paths} == before


def test_the_snapshot_directory_is_not_the_one_ticket_21_uses_and_the_script_is_found_inside_it():
    assert dm.base.SNAPSHOT_DIR.name == "auri-ticket22-mutation-snapshot"
    assert dm.base.SCRIPT_RELATIVE == pathlib.Path("execution/acceptance/android-destinations/destination_mutation_check.py")
    assert (REPO / dm.base.SCRIPT_RELATIVE).is_file()


def test_listing_shows_this_ticket_s_table_not_ticket_21_s(capsys):
    assert dm.main(["--list"]) == 0

    listed = [line.split("\t")[0] for line in capsys.readouterr().out.splitlines() if line.strip()]

    assert listed == [m.id for m in dm.MUTATIONS]


# 验收清单的每一项，在表里至少有一条对应的变异（README 的“变异”一节引用这些 id）
CHECKLIST = {
    "1 创建必须明确选发送方式，没有默认值": ["app-send-mode-defaults-to-auto", "sync-send-mode-wire-renamed", "real-gw-send-mode-wire-renamed"],
    "2 生成或轮换凭证前要本机核验；凭证只显示一次": [
        "service-skips-authentication", "real-gw-service-skips-authentication", "biometric-unknown-error-is-authenticated",
        "vm-copy-does-not-wipe", "vm-leave-does-not-wipe", "vm-late-credential-after-leave-shown", "vm-backgrounded-keeps-credential",
        "credential-tostring-leaks", "service-logs-credential", "guard-sees-credential-in-log", "guard-sees-saved-state-on-the-page",
    ],
    "3 自动发送的初稿标未审核": ["sync-submission-marks-reviewed", "real-gw-submission-marks-reviewed", "ui-unreviewed-label-blank"],
    "4 预览确认在用户确认前不产生 Delivery": [
        "dispatch-awaiting-not-recognized", "dispatch-awaiting-in-publication", "dispatch-due-includes-awaiting",
        "dispatch-sql-preview-recorded-as-auto", "dispatch-sql-decide-not-idempotent",
        "engine-additions-never-run", "real-gw-engine-additions-never-run", "engine-additions-trusts-success",
    ],
    "5 每条内容可标备忘/请求执行": ["dispatch-ignores-eligibility", "default-intent-is-action-request"],
    "6 详情页状态如实、不说已处理": [
        "status-unknown-state-counts-as-acknowledged", "status-leased-reads-as-acknowledged", "real-gw-leased-reads-as-acknowledged",
        "status-unreachable-shown-as-stored", "status-wording-says-processed",
    ],
}


@pytest.mark.parametrize("item", list(CHECKLIST))
def test_every_checklist_item_has_mutations_in_the_table(item):
    ids = {m.id for m in dm.MUTATIONS}
    assert set(CHECKLIST[item]) <= ids, set(CHECKLIST[item]) - ids


# 第三轮再评（S1–S12）：每一项修复在表里至少有一条对应的变异（README 15.5 引用这些 id）；
# 任何人删掉某条修复的全部变异，这里会红，而不是让那项修复悄悄失去变异检查的保护。
ROUND3 = {
    "S1 升级补撤销截止点不信网关时钟的先后": [
        "s1-gw-migration-ignores-revision-times", "s1-gw-migration-trusts-time-order", "s1-gw-migration-treats-every-later-credential-as-suspension",
    ],
    "S2 网关结果的判断与落账在配对存储的同一把锁里": ["s2-settle-without-the-pairing-lock", "s2-freeze-outside-the-pairing-lock"],
    "S3 放行与请求交出去之间不留缝（授权世代）": [
        "s3-permit-never-compares-the-generation", "s3-handoff-without-the-policy-lock", "s3-heal-contract-skips-the-second-ask", "s3-generation-not-moved-before-the-write",
        "s3-generation-not-moved-after-settling", "s3-sqlite-policy-write-skips-the-generation", "s3-first-attempt-keeps-the-freeze-when-gated",
        "s3-uncertain-attempt-releases-the-freeze", "s3-sql-unfreeze-does-nothing",
        # 续作：交接在真实客户端上的性质（1b）、撤销测试不能空洞通过（1c）
        "s3-real-client-runs-blocking-work-in-the-callers-prefix", "s3-handoff-holds-the-lock-for-the-whole-request",
        "s3-handoff-shields-the-request-from-cancellation", "s3-handoff-reports-a-failed-request-as-withheld", "s3-handoff-checks-then-never-sends",
        "s3-put-handed-over-with-a-stale-permit",
        # 续作 1d：被说成“等价变异”的严格形式（只删一问、沿用旧章）其实有已有的测试盯着，还原成独立条目
        "s3-last-ask-deleted-keeps-the-old-permit", "s3-contract-probe-ask-deleted-keeps-the-old-permit",
    ],
    "S4 缓存与排着的目标按完整网关身份认": [
        "s4-ledger-key-is-the-gateway-id-only", "s4-ledger-key-is-the-gateway-id-only-on-sqlite", "s4-sql-use-gateway-compares-the-id-only",
    ],
    "S5 授权带配对世代，清理失败不让旧同意复活": [
        "s5-epoch-always-inherited", "s5-epoch-always-fresh", "s5-consent-ignores-the-pairing-epoch", "s5-consent-ignores-the-pairing-epoch-on-sqlite",
        "s5-grant-epoch-not-validated", "s5-pairing-store-drops-the-epoch",
    ],
    "S6 兼容结论作废并重新问版本": [
        "s6-422-permanent-without-rechecking", "s6-platform-network-events-keep-the-verdict", "s6-unreachable-location-keeps-the-verdict",
        "s6-guard-invalidate-keeps-the-memory", "s6-guard-remembers-an-answer-asked-before-an-invalidation", "s6-service-keeps-the-verdict-after-a-failed-call",
        "s6-service-unreachable-keeps-the-verdict", "s6-additions-permanent-rejection-without-rechecking",
    ],
    "S7 网关太旧不取消系统叫醒": ["s7-too-old-cancels-the-wake", "s7-any-connection-problem-keeps-the-wake"],
    "S8 收尾不带走叫醒；登记不上叫醒就不落账确认": [
        "s8-finishing-outside-the-lock", "s8-finishing-never-cancels-the-wake", "s8-finishing-trusts-what-the-gate-told", "s8-prewake-failure-still-commits",
        "s8-wake-unavailable-commits-anyway", "s8-runtime-finishing-skips-the-gate", "s8-review-ignores-wake-unavailable", "s8-review-screen-never-shows-the-retry-notice",
    ],
    "S9 第一次发送连接就失败时放开意图标签": [
        "s9-unreachable-first-attempt-keeps-the-freeze", "s9-key-mismatch-first-attempt-keeps-the-freeze", "s9-interrupted-request-releases-the-freeze",
        "s9-uncertain-attempt-releases-the-freeze",
    ],
    "S10 失去焦点也按离开处理": ["s10-focus-loss-does-not-clear-the-clipboard"],
    "S11 剪贴板归属按内容证明": [
        "s11-ownership-trusts-any-content", "s11-ownership-compares-loosely", "s11-ownership-keeps-the-secret-text", "s11-ownership-keeps-reading-after-being-replaced",
        "s11-ownership-forget-keeps-the-digest", "s11-port-does-not-remember-what-it-copied", "s11-port-clear-keeps-the-digest",
    ],
    "S12 找网关是一串请求：逐个探测、问 mDNS 各过一次门（续作 1a）": [
        "s12-walk-skips-the-gate-for-probes", "s12-walk-skips-the-gate-for-mdns", "s12-walk-keeps-asking-after-the-gate-withholds",
        "s12-withheld-walk-reported-as-unreachable", "s12-locator-hands-an-open-gate-to-the-walk", "s12-engine-locates-without-the-permit",
        "s12-open-gate-withholds", "s12-attempt-locates-with-a-fresh-permit", "s12-probe-locates-with-a-fresh-permit",
        "s12-heal-contract-locates-with-a-fresh-permit", "s12-additions-locate-with-a-fresh-permit",
    ],
}


@pytest.mark.parametrize("item", list(ROUND3))
def test_every_round3_fix_has_mutations_in_the_table(item):
    ids = {m.id for m in dm.MUTATIONS}
    assert set(ROUND3[item]) <= ids, set(ROUND3[item]) - ids


def test_every_round3_mutation_in_the_table_is_attributed_to_a_fix():
    # 反过来：以 s<数字>- 开头的条目都属于上面某一项修复，表里不留“不知道为谁而写”的条目
    attributed = {i for ids in ROUND3.values() for i in ids}
    round3_ids = {m.id for m in dm.MUTATIONS if re.match(r"s\d+-", m.id)}
    assert round3_ids == attributed, (round3_ids - attributed, attributed - round3_ids)


# 第四轮（对第三轮再评的修复，F1–F10）：每一项修复在表里至少有一条对应的变异（README 16 节引用这些 id）；
# 任何人删掉某项修复的全部变异，这里会红，而不是让那项修复悄悄失去变异检查的保护。
ROUND4 = {
    "F1 授权设置的写窗口里世代是一次性的章；写块没跑起来也不让窗口永远开着": [
        "r4f1-window-reads-return-the-counter", "r4f1-window-reads-repeat", "r4f1-window-never-opens", "r4f1-first-settle-closes-every-window",
        "r4f1-ticket-settles-twice", "r4f1-ticket-opens-after-settling", "r4f1-sqlite-write-without-the-outer-settle",
        "r4f1-sqlite-write-settles-off-the-database-thread", "r4f1-sqlite-write-touches-the-window-directly",
    ],
    "F2 升级补撤销截止点：凭证历史含糊（撤销与重新签发同一时间戳等）一律补，只有数据能证明的原子轮换才不补": [
        "r4f2-gw-predecessor-rule-accepts-any-credential", "r4f2-gw-predecessor-lookup-crosses-destinations", "r4f2-gw-same-instant-issues-not-detected",
        "r4f2-gw-cancel-trace-ignored", "r4f2-gw-cancel-trace-without-a-later-credential", "r4f2-gw-cancel-trace-from-another-destination",
        "r4f2-gw-cancel-trace-needs-no-matching-instant", "r4f2-gw-cutoff-takes-other-tenants-texts",
    ],
    "F3 窗口失焦后读不了剪贴板：靠复制之后登记的变化监听证明“没人动过”（只在失焦那一刻有效）才清，收到过任何变化就不清": [
        "r4f3-leaving-ignores-the-no-change-proof", "r4f3-expiry-trusts-the-no-change-proof", "r4f3-change-event-does-not-void-the-proof",
        "r4f3-no-listener-registered", "r4f3-listener-never-reports", "r4f3-remember-keeps-the-old-listener", "r4f3-forget-keeps-the-listener",
        "r4f3-port-registers-before-the-copy", "r4f3-adapter-never-removes-the-listener", "r4f3-adapter-never-registers", "r4f3-port-never-wires-the-events",
    ],
    "F10 整份剪贴板的形状（一项、只有 text/plain、只有文字）与摘要都对得上才算我们的": [
        "r4f10-ownership-reads-only-the-first-item", "r4f10-ownership-ignores-html", "r4f10-ownership-ignores-uri", "r4f10-ownership-ignores-intent",
        "r4f10-ownership-ignores-the-mime-types", "r4f10-port-reads-only-the-first-item", "r4f10-port-ignores-html-uri-and-intent", "r4f10-port-ignores-the-mime-types",
    ],
    "F4 叫醒规划器的结论在门的锁里按磁盘上的账现算，不拿流里排队时读到的旧快照": [
        "r4f4-planner-ignores-the-current-need", "r4f4-planner-reads-the-need-outside-the-gate", "r4f4-planner-trusts-the-current-need-only-when-it-says-yes",
        "r4f4-runtime-planner-without-current-need", "r4f4-runtime-need-skips-the-pairing", "r4f4-runtime-need-skips-the-jobs-table",
        "r4f4-runtime-need-skips-the-policy", "r4f4-runtime-need-skips-the-additions", "r4f4-runtime-runner-uses-another-judgment",
    ],
    "F5 网络变了时在路上的版本询问：答复对谁都不算数（不记也不交给提问的人），重新问，一直在变就回没问成": [
        "r4f5-stale-answer-handed-over-and-kept", "r4f5-only-compatible-is-discarded", "r4f5-rejections-and-failures-are-handed-over",
        "r4f5-discards-every-compatible-answer", "r4f5-never-asks-again", "r4f5-asks-without-a-useful-bound",
        "r4f5-gives-up-with-the-stale-answer", "r4f5-invalidate-does-not-bump-the-generation",
    ],
    "F6 撤销截止点改按持久单调入库顺序（store_seq / revocation_cutoff_seq，迁移 0006），不再被历史未来时间戳永久截断": [
        "r4f6-gw-visibility-still-uses-wall-clock", "r4f6-gw-sequence-never-increments",
        "r4f6-gw-migration-0006-ignores-existing-cutoffs", "r4f6-gw-migration-0006-crosses-tenants",
        "r4f6-gw-migration-0006-leaves-sequence-at-zero",
    ],
    "F7 mDNS 发现里每次解析各过一次门，门不放行就停发现，没有新服务时哨兵也会发现授权被收回": [
        "r4f7-walk-hands-down-an-open-gate", "r4f7-resolution-skips-the-gate", "r4f7-refusal-keeps-listening", "r4f7-no-sentinel",
        "r4f7-sentinel-waits-far-too-long", "r4f7-sentinel-outlives-the-listening", "r4f7-nsd-ignores-the-gate", "r4f7-nsd-collects-by-itself",
    ],
    "F8 文字不能进网关时自动读（列表、状态）一个请求也不发，显示本机缓存与明确状态，用户自己发起的照常": [
        "r4f8-automatic-refresh-skips-the-permit", "r4f8-automatic-deliveries-skips-the-permit",
        "r4f8-connect-locates-without-the-gate", "r4f8-verified-skips-the-gate",
        "r4f8-gateway-call-skips-the-gate", "r4f8-status-loop-skips-the-gate",
        "r4f8-list-init-uses-manual-refresh", "r4f8-detail-load-always-manual",
    ],
    "F9 网关不得自动或显式绑定 VPN / 隧道接口与 Tailscale ULA 地址（ADR-0018）": [
        "r4f9-gw-tailscale-ula-allowed-as-lan", "r4f9-gw-discovery-keeps-tunnel-interfaces",
        "r4f9-gw-explicit-bind-allows-tunnel-ip", "r4f9-gw-tunnel-name-pattern-disabled",
    ],
}


@pytest.mark.parametrize("item", list(ROUND4))
def test_every_round4_fix_has_mutations_in_the_table(item):
    ids = {m.id for m in dm.MUTATIONS}
    assert set(ROUND4[item]) <= ids, set(ROUND4[item]) - ids


def test_every_round4_mutation_in_the_table_is_attributed_to_a_fix():
    # 反过来：以 r4f<数字>- 开头的条目都属于上面某一项修复；这个前缀与第三轮的 s<数字>- 不冲突
    attributed = {i for ids in ROUND4.values() for i in ids}
    round4_ids = {m.id for m in dm.MUTATIONS if re.match(r"r4f\d+-", m.id)}
    assert round4_ids == attributed, (round4_ids - attributed, attributed - round4_ids)
