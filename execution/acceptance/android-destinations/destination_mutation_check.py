#!/usr/bin/env python3
"""Ticket 22 变异检查：对每条“必须守住的规则”故意破坏一处生产代码，确认对应的测试会变红，然后一定还原。

机器与 Ticket 21 的 android-sync/mutation_check.py 是同一套（只改快照里的源码、真实工作树一个字节都不动；
应用 → 跑 Gradle → 还原；表自身有离线检查），这里只换变异表与快照目录。表的离线检查见 test_destination_mutation_table.py。
为什么要有它：只写“通过的测试”不能证明测试能拦住错误。这里把“我破坏了什么、哪些测试必须因此失败”写成一张表，任何人都能重跑。

用法（先 source ~/.config/auri/android-env.sh；先取共享 Gradle 锁 ~/.cache/auri/gradle.lock；在仓库根目录执行）：
    .venv/bin/python execution/acceptance/android-destinations/destination_mutation_check.py --list
    .venv/bin/python execution/acceptance/android-destinations/destination_mutation_check.py --only <id> [<id> ...]
    .venv/bin/python execution/acceptance/android-destinations/destination_mutation_check.py --match '^real-gw-'
退出码：0 = 每条变异都被预期的测试拦住；1 = 有变异没被拦住（测试有漏洞）；2 = 变异本身无效（找不到原文、编译不过没跑出测试等）。
内存紧的机器上加环境变量 AURI_GRADLE_ARGS="--no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2"。
"""
from __future__ import annotations

import importlib.util
import os
import pathlib
import subprocess
import sys
import tempfile

_HERE = pathlib.Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("auri_sync_mutation_check", _HERE.parent / "android-sync" / "mutation_check.py")
base = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = base  # dataclass 要在模块表里找得到它
_spec.loader.exec_module(base)

Edit, Mutation = base.Edit, base.Mutation
base.SNAPSHOT_DIR = pathlib.Path(tempfile.gettempdir()) / "auri-ticket22-mutation-snapshot"
base.SCRIPT_RELATIVE = pathlib.Path(__file__).resolve().relative_to(base.REPO)

# ---- 被破坏的生产代码与要跑的测试 ----
ANDROID = "apps/android/"
APP_DEST = ANDROID + "app/src/main/kotlin/app/auri/android/destinations/"
APP_UI = ANDROID + "app/src/main/kotlin/app/auri/android/ui/"
SYNC_DEST = ANDROID + "sync/src/main/kotlin/app/auri/sync/destinations/"
SYNC_GATEWAY = ANDROID + "sync/src/main/kotlin/app/auri/sync/gateway/"
SYNC_ENGINE = ANDROID + "sync/src/main/kotlin/app/auri/sync/engine/"
SYNCPLATFORM = ANDROID + "syncplatform/src/main/kotlin/app/auri/syncplatform/"

DETAIL_VM = APP_DEST + "DestinationDetailViewModel.kt"
DESTINATIONS_VM = APP_DEST + "DestinationsViewModel.kt"
DESCRIPTIONS = APP_DEST + "DestinationDescriptions.kt"
PAGE = APP_UI + "DestinationsPage.kt"
SERVICE = SYNC_DEST + "DestinationService.kt"
DISPATCH_STORE = SYNC_DEST + "DispatchStore.kt"
DELIVERY_VIEW = SYNC_DEST + "DeliveryView.kt"
MODELS = SYNC_GATEWAY + "DestinationModels.kt"
SUBMISSION = SYNC_GATEWAY + "SubmissionBuilder.kt"
LIBRARY_SOURCE = SYNC_ENGINE + "RecordLibrarySubmissionSource.kt"
DISPATCH_SQL = SYNCPLATFORM + "dispatch/DispatchSql.kt"
BIOMETRIC = SYNCPLATFORM + "auth/BiometricResults.kt"
BIOMETRIC_GATE = SYNCPLATFORM + "auth/BiometricGate.kt"
ENGINE = SYNC_ENGINE + "SyncEngine.kt"
POLICY = ANDROID + "sync/src/main/kotlin/app/auri/sync/policy/DataFlowPolicy.kt"
POLICY_SQL = SYNCPLATFORM + "policy/DataFlowPolicySql.kt"
SQLITE_POLICY = SYNCPLATFORM + "policy/SqliteDataFlowPolicy.kt"
STORED_STATES = ANDROID + "sync/src/main/kotlin/app/auri/sync/policy/StoredStatesRecovery.kt"
RESUME_PLANNER = ANDROID + "sync/src/main/kotlin/app/auri/sync/resume/ResumePlanner.kt"
WAKE_GATE = ANDROID + "sync/src/main/kotlin/app/auri/sync/resume/WakeGate.kt"
SYNC_RUNTIME = SYNCPLATFORM + "SyncRuntime.kt"
PAIRING_FILE_STORE = ANDROID + "sync/src/main/kotlin/app/auri/sync/pairing/FilePairingStore.kt"
PAIRING_OBSERVABLE = ANDROID + "sync/src/main/kotlin/app/auri/sync/pairing/ObservablePairingStore.kt"
PAIRING_SERVICE = ANDROID + "sync/src/main/kotlin/app/auri/sync/pairing/PairingService.kt"
SYNC_STATUS = ANDROID + "sync/src/main/kotlin/app/auri/sync/status/SyncStatus.kt"
SEND_REVIEW_VM = APP_DEST + "SendReviewViewModel.kt"
CLIPBOARD_GUARD = APP_DEST + "SecretClipboardGuard.kt"
CLIPBOARD_PORT = APP_UI + "SecretWindow.kt"
MAIN_ACTIVITY = ANDROID + "app/src/main/kotlin/app/auri/android/MainActivity.kt"
AURI_RUNTIME = ANDROID + "app/src/main/kotlin/app/auri/android/AuriRuntime.kt"
CONTAINED_READS = APP_DEST + "ContainedReads.kt"
SEND_REVIEW_SCREEN = APP_UI + "SendReviewScreen.kt"
DESTINATIONS_SCREEN = APP_UI + "DestinationsScreen.kt"
DETAIL_SCREEN = APP_UI + "DestinationDetailScreen.kt"
GATEWAY_STORE = "services/gateway/auri_gateway/store.py"
GATEWAY_DELIVERIES = "services/gateway/auri_gateway/deliveries.py"
GATEWAY_QUERIES = "services/gateway/auri_gateway/queries.py"
GATEWAY_MIGRATION_0005 = "services/gateway/auri_gateway/migrations/0005_destination_revocation_cutoff.sql"
CONTRACT_GUARD = SYNC_GATEWAY + "ContractGuard.kt"
PAIRED_GATEWAY = ANDROID + "sync/src/main/kotlin/app/auri/sync/pairing/PairedGateway.kt"
POLICY_GENERATION = ANDROID + "sync/src/main/kotlin/app/auri/sync/policy/PolicyGeneration.kt"
PINNED_CLIENT = ANDROID + "sync/src/main/kotlin/app/auri/sync/http/PinnedHttpsClient.kt"
REQUEST_GATE = ANDROID + "sync/src/main/kotlin/app/auri/sync/policy/RequestGate.kt"
DISCOVERY = ANDROID + "sync/src/main/kotlin/app/auri/sync/discovery/GatewayDiscovery.kt"
LOCATOR = ANDROID + "sync/src/main/kotlin/app/auri/sync/discovery/GatewayLocator.kt"
CLIP_OWNERSHIP = APP_DEST + "ClipOwnership.kt"

APP_RESULTS, SYNC_RESULTS, REAL_RESULTS, PLATFORM_RESULTS = (
    "app/build/test-results/testDebugUnitTest",
    "sync/build/test-results/test",
    "sync/build/test-results/realGatewayTest",
    "syncplatform/build/test-results/testDebugUnitTest",
)


def app_tests(*classes: str) -> tuple[str, ...]:
    return (":app:testDebugUnitTest", *(arg for c in classes for arg in ("--tests", f"*{c}*")))


def sync_tests(*classes: str) -> tuple[str, ...]:
    return (":sync:test", *(arg for c in classes for arg in ("--tests", f"*{c}*")))


def platform_tests(*classes: str) -> tuple[str, ...]:
    return (":syncplatform:testDebugUnitTest", *(arg for c in classes for arg in ("--tests", f"*{c}*")))


REAL = (":sync:realGatewayTest",)

# 网关自己的 Python 集成测试（tests/integration）：撤销截止点（复评 R1/R2）是 Agent 经 MCP 才看得到的网关行为，Kotlin 客户端的真网关测试够不着。
# 这类条目的 gradle 字段第一项是 PYTEST、其余是 pytest 的参数；结果写成 JUnit XML 放在快照里的 GATEWAY_RESULTS（build 目录不进快照同步）。
PYTEST = "pytest"
GATEWAY_RESULTS = "build/gateway-pytest-results"
REVOCATION_CUTOFF_TESTS = "tests/integration/test_revocation_cutoff.py"
BACKFILL_TESTS = "tests/integration/test_revocation_cutoff_backfill.py"
BACKFILL_CASE = "test_the_backfill_only_leaves_a_destination_uncut_when_its_history_proves_an_atomic_rotation"  # 参数化：BACKFILL_CASE[编号]


def gateway_tests(*pytest_args: str) -> tuple[str, ...]:
    return (PYTEST, *pytest_args)


_BASE_RUN_MUTATION = base.run_mutation


def _run_mutation(mutation):
    """Gradle 条目照旧；pytest 条目：应用 → 在快照里用 AURI_GATEWAY_PYTHON 跑 pytest → 还原。"""
    if not mutation.gradle or mutation.gradle[0] != PYTEST:
        return _BASE_RUN_MUTATION(mutation)
    results = base.ANDROID / mutation.results_dir
    originals = base.apply_edits(mutation)
    try:
        for edit in mutation.edits:  # 变异成了语法错误，整个网关起不来、所有测试都红：那不是“被测试抓住”，是变异无效
            if edit.path.endswith(".py"):
                try:
                    compile((base.REPO / edit.path).read_text(encoding="utf-8"), edit.path, "exec")
                except SyntaxError as error:
                    return "INVALID", f"变异后的 {edit.path} 不是合法的 Python：{error}"
        subprocess.run(["rm", "-rf", str(results)], check=True)
        results.mkdir(parents=True)
        python = os.environ.get("AURI_GATEWAY_PYTHON") or str(base.REPO / ".venv" / "bin" / "python")
        env = {**os.environ, "NO_PROXY": "localhost,127.0.0.1,::1", "no_proxy": "localhost,127.0.0.1,::1"}
        proc = subprocess.run(
            [python, "-m", "pytest", *mutation.gradle[1:], "-q", "-p", "no:cacheprovider", f"--junitxml={results / 'TEST-gateway.xml'}"],
            cwd=base.REPO, env=env, capture_output=True, text=True, timeout=1800,
        )
        total, failed = base.failed_tests(results)
    finally:
        base.restore(originals)
    if total == 0:
        return "INVALID", f"没有跑出任何测试：\n{proc.stdout[-1500:]}\n{proc.stderr[-500:]}"
    if proc.returncode == 0 or not failed:
        return "SURVIVED", f"变异后 {total} 个测试全部通过：测试拦不住这个破坏"
    missing = [needle for needle in mutation.must_fail if not any(needle in name for name in failed)]
    if missing:
        return "SURVIVED", f"有测试失败，但预期会失败的这些没有失败：{missing}；实际失败：{failed}"
    return "CAUGHT", f"{len(failed)}/{total} 个测试变红：{failed}"


base.run_mutation = _run_mutation

# 同一处破坏，在不同的测试层再跑一遍（单测 / 真实网关）：用同一份编辑
SKIP_AUTH = Edit(SERVICE, "            if (auth != AuthResult.Authenticated) {", "            if (false && auth != AuthResult.Authenticated) {")
AWAITING_NOT_RECOGNIZED = Edit(DISPATCH_STORE, "    val awaitingUser: Boolean get() = decision == TargetDecision.AWAITING && eligible", "    val awaitingUser: Boolean get() = false")
AWAITING_WILL_RECEIVE = Edit(
    DISPATCH_STORE,
    "    val willReceive: Boolean get() = eligible && delivery != GatewayDelivery.REFUSED && (decision == TargetDecision.AUTO || decision == TargetDecision.CONFIRMED)",
    "    val willReceive: Boolean get() = eligible && delivery != GatewayDelivery.REFUSED && decision != TargetDecision.SKIPPED",
)
IGNORE_ELIGIBILITY = Edit(
    DISPATCH_STORE,
    "    val willReceive: Boolean get() = eligible && delivery != GatewayDelivery.REFUSED && (decision == TargetDecision.AUTO || decision == TargetDecision.CONFIRMED)",
    "    val willReceive: Boolean get() = delivery != GatewayDelivery.REFUSED && (decision == TargetDecision.AUTO || decision == TargetDecision.CONFIRMED)",
)
MARK_REVIEWED = Edit(SUBMISSION, '                put("review_state", wire(revision.reviewState))', '                put("review_state", "reviewed")')
IGNORE_PUBLICATION = Edit(LIBRARY_SOURCE, "        return builder.build(entry, revision, publications.publicationFor(key))", "        return builder.build(entry, revision)")
WIRE_RENAMED = Edit(MODELS, '    AUTO_SEND("auto_send"),', '    AUTO_SEND("auto"),')
LEASED_AS_ACKED = Edit(DELIVERY_VIEW, '            "LEASED" -> CLAIMED', '            "LEASED" -> ACKNOWLEDGED')
UNKNOWN_AS_ACKED = Edit(DELIVERY_VIEW, '            "CANCELLED" -> CANCELLED\n            else -> null', '            "CANCELLED" -> CANCELLED\n            else -> ACKNOWLEDGED')
LOG_CREDENTIAL = Edit(
    SERVICE,
    '                    log.event("credential.issued replaced=${result.value.replacedPrevious}")',
    '                    log.event("credential.issued replaced=${result.value.replacedPrevious} ${result.value.credential.reveal()}")',
)

# 评审 B2：引擎里“给已入库的 Revision 追加 Delivery”那一遍——真网关测试与单测各跑一遍，用同一份编辑
ADDITIONS_NEVER_RUN = Edit(ENGINE, "                addDeliveries(location)\n            }\n", "            }\n")

# 评审 B1：引擎问“文字放行了吗”的那一处——真网关测试与单测各跑一遍，用同一份编辑
POLICY_GATE_OPEN = Edit(
    ENGINE,
    "    private suspend fun mayTransmit(gateway: PairedGateway?): Boolean = policy.current(gateway?.identity).textMayEnterGateway",
    "    private suspend fun mayTransmit(gateway: PairedGateway?): Boolean = true",
)

# 评审 B3：手机读 Destination 的 credential_active（真网关测试与单测各跑一遍，用同一份编辑）
GATEWAY_CLIENT = SYNC_GATEWAY + "GatewayClient.kt"
CLIENT_IGNORES_CREDENTIAL_ACTIVE = Edit(
    GATEWAY_CLIENT,
    '            credentialActive = reply.booleanOrNull("credential_active") ?: issued,',
    "            credentialActive = issued,",
)

# 评审 M4：服务把网关“到上限了”的 409 读成普通拒绝（真网关测试与单测各跑一遍，用同一份编辑）
LIMIT_READ_AS_PLAIN_REJECTION = Edit(SERVICE, "result.failure.isActiveLimit()", "false && result.failure.isActiveLimit()")

MUTATIONS: tuple = (
    # ---- 1. 创建：发送方式必须明确选，没有默认值，请求里就是合同写的那个名字 ----
    Mutation(
        "app-send-mode-defaults-to-auto",
        "创建表单一开始必须没有发送方式（没有默认值）：默认成自动发送，就等于替用户授权了自动发送（ADR-009）",
        (Edit(DESTINATIONS_VM, "    val sendMode: SendMode? = null,", "    val sendMode: SendMode? = SendMode.AUTO_SEND,"),),
        app_tests("DestinationsViewModelTest"), APP_RESULTS,
        ("一开始没有选发送方式",),
    ),
    Mutation(
        "sync-send-mode-wire-renamed",
        "创建请求里的 send_mode 必须逐字是合同的取值（auto_send / preview_confirm）",
        (WIRE_RENAMED,),
        sync_tests("DestinationClientTest", "DeliveryViewTest"), SYNC_RESULTS,
        ("创建自动发送的 Destination", "发送方式的合同取值与本机一致"),
    ),
    Mutation(
        "real-gw-send-mode-wire-renamed",
        "真网关：send_mode 写错了取值，网关不认，创建必须失败（而不是悄悄变成别的模式）",
        (WIRE_RENAMED,),
        REAL, REAL_RESULTS,
        ("创建：自动发送与预览确认各建一个",),
    ),
    # ---- 2. 凭证：生成或轮换之前必须过本机核验 ----
    Mutation(
        "service-skips-authentication",
        "生成/轮换凭证前必须现场核验：跳过核验就会在没人确认是机主的情况下拿到凭证",
        (SKIP_AUTH,),
        sync_tests("DestinationServiceTest"), SYNC_RESULTS,
        ("核验没通过",),
    ),
    Mutation(
        "service-cancel-and-unavailable-pass",
        "取消、没有硬件、没登记生物识别都不能当作通过（只有明确被拒才拦住就是放行了没法核验的设备）",
        (Edit(SERVICE, "            if (auth != AuthResult.Authenticated) {", "            if (auth == AuthResult.Denied) {"),),
        sync_tests("DestinationServiceTest"), SYNC_RESULTS,
        ("核验没通过",),
    ),
    Mutation(
        "real-gw-service-skips-authentication",
        "真网关：核验没通过时手机一个凭证请求也不能发，网关上不能出现凭证",
        (SKIP_AUTH,),
        REAL, REAL_RESULTS,
        ("核验没通过",),
    ),
    Mutation(
        "biometric-unknown-error-is-authenticated",
        "系统生物识别给了没见过的错误码，必须按没通过处理（任何码都不可能得到“已通过”）",
        (Edit(BIOMETRIC, "        else -> AuthResult.Denied", "        else -> AuthResult.Authenticated"),),
        platform_tests("BiometricResultsTest"), PLATFORM_RESULTS,
        ("锁定、超时、无法处理与没见过的码都是没通过", "任何错误码都不可能得到"),
    ),
    # ---- 3. 凭证只显示一次，不留在任何地方 ----
    Mutation(
        "vm-copy-does-not-wipe",
        "复制之后凭证必须立刻抹掉（只显示一次）",
        (Edit(DETAIL_VM, "    fun credentialCopied() = wipeCredential()", "    fun credentialCopied() = Unit"),),
        app_tests("DestinationDetailViewModelTest"), APP_RESULTS,
        ("凭证只显示一次",),
    ),
    Mutation(
        "vm-leave-does-not-wipe",
        "离开页面必须抹掉凭证",
        (Edit(DETAIL_VM, "    fun leave() {\n        panel.update { Panel(it.generation + 1) }\n    }", "    fun leave() {\n        panel.update { it.copy(generation = it.generation + 1) }\n    }"),),
        app_tests("DestinationDetailViewModelTest"), APP_RESULTS,
        ("凭证只显示一次", "等核验或网关的时候离开了页面"),
    ),
    Mutation(
        "vm-late-credential-after-leave-shown",
        "等核验或网关时页面离开了：之后才到的凭证不能再显示出来（没人看着，也不能留着）",
        (Edit(DETAIL_VM, "                current.generation == mine -> current.copy(ui = ui)\n", "                true -> current.copy(ui = ui)\n"),),
        app_tests("DestinationDetailViewModelTest"), APP_RESULTS,
        ("等核验或网关的时候离开了页面",),
    ),
    Mutation(
        "vm-backgrounded-keeps-credential",
        "应用退到后台时正在显示的凭证必须抹掉（回来时看到的和进程被杀后一样是空的）",
        (Edit(DETAIL_VM, "        panel.update { if (it.ui is CredentialUi.Shown || it.ui == CredentialUi.Working) Panel(it.generation + 1) else it }", "        Unit"),),
        app_tests("DestinationDetailViewModelTest"), APP_RESULTS,
        ("应用退到后台：正在显示的凭证抹掉", "应用退到后台时凭证还在路上"),
    ),
    Mutation(
        "credential-tostring-leaks",
        "凭证对象的字符串形式不能含凭证（日志、异常消息、调试输出里不小心带上它也不会泄露）",
        (Edit(MODELS, '    override fun toString() = "DestinationCredential(redacted)"', '    override fun toString() = "DestinationCredential($value)"'),),
        sync_tests("DestinationClientTest", "DestinationServiceTest"), SYNC_RESULTS,
        ("凭证对象的 toString 不含凭证", "凭证不会出现在日志里"),
    ),
    Mutation(
        "service-logs-credential",
        "签发凭证的日志里不能出现凭证",
        (LOG_CREDENTIAL,),
        sync_tests("DestinationServiceTest"), SYNC_RESULTS,
        ("凭证不会出现在日志里",),
    ),
    Mutation(
        "guard-sees-credential-in-log",
        "源码守卫：日志里插值凭证、在登记之外读出凭证明文，守卫必须变红",
        (LOG_CREDENTIAL,),
        app_tests("CredentialSurfaceGuardTest"), APP_RESULTS,
        ("日志只写事件名与异常类名", "读出凭证明文的地方只有两处"),
    ),
    Mutation(
        "guard-sees-saved-state-on-the-page",
        "源码守卫：详情页多存一个保存状态（进程被杀后还在），守卫必须变红",
        (Edit(PAGE, "            val owner = LocalLifecycleOwner.current", "            val owner = LocalLifecycleOwner.current\n            var leaked by rememberSaveable { mutableStateOf(\"\") }"),),
        app_tests("CredentialSurfaceGuardTest"), APP_RESULTS,
        ("没有任何会带走凭证的 API", "界面里唯一保存的状态"),
    ),
    # ---- 4. 预览确认（评审 B2 之后）：文字照常入库、不拦别的目标；预览目标的 Delivery 在用户确认之后才向网关追加，幂等；意图标签只是数据 ----
    # “第一次提交带哪些目标”“欠网关哪些追加”的判断在发送账里（Android 上是 SQLite：DispatchSql / DispatchTarget.willReceive），
    # 所以账的变异放在 :syncplatform 的 SQLite 合同测试上（JVM 里跑真 SQLite 引擎）；追加那一遍在引擎里，单测与真网关各跑一遍。
    # 真网关测试用的是内存发送账（测试替身），它们的价值在“网关那一侧看到什么”，所以不对账动手脚。
    Mutation(
        "dispatch-awaiting-not-recognized",
        "等用户决定的目标必须被认出来（待发送页靠它列出要用户确认的内容）：认不出来，用户永远看不到要确认什么",
        (AWAITING_NOT_RECOGNIZED,),
        platform_tests("SqliteDispatchStoreContractTest", "DispatchSqlTest"), PLATFORM_RESULTS,
        ("a_preview_confirm_destination_does_not_hold_the_text_only_its_delivery_waits_for_the_user", "an_awaiting_preview_target_keeps_waiting_after_a_restart"),
    ),
    Mutation(
        "dispatch-awaiting-in-publication",
        "评审 B2：没确认的预览目标不能进第一次提交的 publication（否则网关马上为它建 Delivery，Agent 在用户确认之前就领得到）",
        (AWAITING_WILL_RECEIVE,),
        platform_tests("SqliteDispatchStoreContractTest", "DispatchSqlTest"), PLATFORM_RESULTS,
        ("a_preview_confirm_destination_does_not_hold_the_text_only_its_delivery_waits_for_the_user",),
    ),
    Mutation(
        "dispatch-due-includes-awaiting",
        "评审 B2：没确认的预览目标不能算“欠网关的追加”（否则同步几轮就替用户做了决定）",
        (Edit(DISPATCH_SQL, "t.decision IN ('${TargetDecision.AUTO.name}', '${TargetDecision.CONFIRMED.name}')", "t.decision IN ('${TargetDecision.AUTO.name}', '${TargetDecision.CONFIRMED.name}', '${TargetDecision.AWAITING.name}')"),),
        platform_tests("SqliteDispatchStoreContractTest", "DispatchSqlTest"), PLATFORM_RESULTS,
        ("a_preview_confirm_destination_does_not_hold_the_text_only_its_delivery_waits_for_the_user",),
    ),
    Mutation(
        "dispatch-sql-preview-recorded-as-auto",
        "Android 的 SQLite 发送账：预览确认的目标必须记成“等用户”，不能记成自动",
        (Edit(DISPATCH_SQL, "                    (if (destination.sendMode == SendMode.AUTO_SEND) TargetDecision.AUTO else TargetDecision.AWAITING).name,", "                    TargetDecision.AUTO.name,"),),
        platform_tests("SqliteDispatchStoreContractTest", "DispatchSqlTest"), PLATFORM_RESULTS,
        ("a_preview_confirm_destination_does_not_hold_the_text_only_its_delivery_waits_for_the_user",),
    ),
    Mutation(
        "dispatch-sql-decide-not-idempotent",
        "重复确认必须被认出来（已经决定过）：不能当成新的决定再处理一遍",
        (Edit(DISPATCH_SQL, "        if (target.decision != TargetDecision.AWAITING) return@transaction DecideResult.AlreadyDecided(target.decision)\n", ""),),
        platform_tests("SqliteDispatchStoreContractTest"), PLATFORM_RESULTS,
        ("confirming_is_idempotent_and_the_delivery_becomes_due_once_the_text_is_on_the_gateway", "a_decision_is_final_a_later_opposite_choice_changes_nothing"),
    ),
    Mutation(
        "engine-additions-never-run",
        "评审 B2：用户确认之后，引擎必须向网关为这个已入库的 Revision 追加 Delivery：不做，预览目标永远收不到",
        (ADDITIONS_NEVER_RUN,),
        sync_tests("DispatchEngineTest", "DeliveryAdditionsLoopTest"), SYNC_RESULTS,
        ("预览确认：确认之前文字照常入库、网关上没有给它的投递；确认之后对同一个 Revision 追加恰好一份，不再 PUT", "用户确认之后循环自己醒来做追加"),
    ),
    Mutation(
        "real-gw-engine-additions-never-run",
        "评审 B2（真网关）：确认之后真网关上必须为同一个 Revision 出现预览目标的 Delivery",
        (ADDITIONS_NEVER_RUN,),
        REAL, REAL_RESULTS,
        ("预览确认：文字照常入库，但用户确认之前网关上没有这个 Destination 的 Delivery", "自动发送与预览确认并存"),
    ),
    Mutation(
        "engine-additions-trusts-success",
        "评审 B2：追加的答复成功但里面没有这个目标，必须记为没有送达，不能假设追加一定建成（否则界面谎称已送出）",
        (Edit(
            ENGINE,
            "                        if (result.value.deliveries.any { it.destinationId == addition.destinationId }) additions.gatewayHas(addition.key, ids)\n                        else additions.refused(addition.key, ids, Refusal.NOT_CREATED)\n",
            "                        additions.gatewayHas(addition.key, ids)\n",
        ),),
        sync_tests("DispatchEngineTest"), SYNC_RESULTS,
        ("追加答复成功却没有这个目标：记为没有送达，不假设追加一定建成",),
    ),
    # ---- 评审 M3：意图标签在请求真正离开手机之前都能改，构造提交的那一刻才冻结 ----
    Mutation(
        "dispatch-sql-publication-does-not-freeze",
        "评审 M3：构造提交时必须在同一个事务里冻结意图：不冻结，发出去之后用户还能把标签改成与网关上不一样的值",
        (Edit(DISPATCH_SQL, '        sql.update("UPDATE dispatch_items SET intent_locked = 1 WHERE record_id = ? AND revision = ? AND intent_locked = 0", key.recordId, key.revision)\n', ""),),
        platform_tests("SqliteDispatchStoreContractTest", "DispatchSqlTest"), PLATFORM_RESULTS,
        ("building_the_submission_freezes_the_intent_so_a_later_change_is_refused_and_the_frozen_value_stays", "building_the_submission_freezes_the_intent_in_the_same_transaction"),
    ),
    Mutation(
        "dispatch-sql-set-intent-ignores-lock",
        "评审 M3：冻结之后改标签必须被拒（网关上这一版的意图不可更改）",
        (Edit(DISPATCH_SQL, "        if (item.intentLocked) return@transaction IntentResult.Locked\n", ""),),
        platform_tests("SqliteDispatchStoreContractTest"), PLATFORM_RESULTS,
        ("building_the_submission_freezes_the_intent_so_a_later_change_is_refused_and_the_frozen_value_stays",),
    ),
    Mutation(
        "engine-freezes-intent-before-locating-gateway",
        "评审 M3：没网、网关找不到的时候标签必须还能改：引擎在找到网关之前就构造提交，等于一排队就锁死了标签",
        (Edit(ENGINE, "        var permit = permitFor(gateway) ?: return waiting(gateway, key, wasUncertain)\n", "        source.submissionFor(key)\n        var permit = permitFor(gateway) ?: return waiting(gateway, key, wasUncertain)\n"),),
        sync_tests("DispatchEngineTest"), SYNC_RESULTS,
        ("没网时文字排着队，标签还能改，网关收到的是改后的（评审 M3）",),
    ),
    Mutation(
        "dispatch-ignores-eligibility",
        "不接收这种意图标签（或已撤销）的 Destination 不能出现在 publication 里（否则网关把整条 422 永久拒收）",
        (IGNORE_ELIGIBILITY,),
        platform_tests("SqliteDispatchStoreContractTest", "DispatchSqlTest"), PLATFORM_RESULTS,
        ("a_destination_revoked_after_the_confirmation_but_before_the_send_is_not_in_the_publication", "an_auto_send_destination_that_does_not_accept_the_intent_is_not_in_the_publication"),
    ),
    Mutation(
        "default-intent-is-action-request",
        "没有任何设置时标签默认必须是备忘，绝不是请求执行",
        (Edit(DISPATCH_SQL, "    fun defaultIntent(): Intent = meta(DEFAULT_INTENT)?.let(Intent::fromWire) ?: Intent.CONTEXT", "    fun defaultIntent(): Intent = meta(DEFAULT_INTENT)?.let(Intent::fromWire) ?: Intent.ACTION_REQUEST"),),
        platform_tests("SqliteDispatchStoreContractTest"), PLATFORM_RESULTS,
        ("the_default_intent_is_context_and_what_the_user_sets_survives_a_restart",),
    ),
    Mutation(
        "sync-source-ignores-publication",
        "每次发送都要现问发送账要 publication：不问就只会入库，Destination 永远收不到",
        (IGNORE_PUBLICATION,),
        sync_tests("SubmissionBuilderTest", "DispatchEngineTest"), SYNC_RESULTS,
        ("发布时的 publication 每次发送都现问发送账", "自动发送：转录完成后一轮同步就送到网关"),
    ),
    # ---- 5. 自动发送的初稿标未审核 ----
    Mutation(
        "sync-submission-marks-reviewed",
        "自动发送的初稿在请求里必须是 not_reviewed",
        (MARK_REVIEWED,),
        sync_tests("SubmissionBuilderTest", "DispatchEngineTest"), SYNC_RESULTS,
        ("自动发送：转录完成后一轮同步就送到网关",),
    ),
    Mutation(
        "real-gw-submission-marks-reviewed",
        "真网关：Agent 领到的自动发送文字必须标着未审核",
        (MARK_REVIEWED,),
        REAL, REAL_RESULTS,
        ("自动发送：转录完成后一轮同步",),
    ),
    Mutation(
        "ui-unreviewed-label-blank",
        "界面必须在自动发送的每一条上写明“未审核”",
        (Edit(DESCRIPTIONS, 'internal const val UNREVIEWED_LABEL = "未审核"', 'internal const val UNREVIEWED_LABEL = ""'),),
        app_tests("DestinationTextTest", "SendReviewViewModelTest"), APP_RESULTS,
        ("两种发送方式的话不同", "自动发送：转录一完成就发出去"),
    ),
    # ---- 6. 状态：网关怎么说就怎么显示，不认识就是未知，绝不说“已处理” ----
    Mutation(
        "status-unknown-state-counts-as-acknowledged",
        "网关给了没见过的投递状态，必须显示未知，不能悄悄算成已确认",
        (UNKNOWN_AS_ACKED,),
        sync_tests("DeliveryViewTest", "DestinationServiceTest"), SYNC_RESULTS,
        ("不认识的状态值不是任何一档", "网关给的状态这个版本不认识"),
    ),
    Mutation(
        "status-leased-reads-as-acknowledged",
        "已领取（LEASED）不能说成已确认：Agent 领走了不等于它回了确认",
        (LEASED_AS_ACKED,),
        sync_tests("DeliveryViewTest", "DestinationServiceTest"), SYNC_RESULTS,
        ("已入库、已领取、已确认：三个阶段各对应网关的一个状态",),
    ),
    Mutation(
        "real-gw-leased-reads-as-acknowledged",
        "真网关：Agent 领取之后手机上必须显示已领取，不是已确认",
        (LEASED_AS_ACKED,),
        REAL, REAL_RESULTS,
        ("自动发送：转录完成后一轮同步",),
    ),
    Mutation(
        "status-unreachable-shown-as-stored",
        "已入库但这会儿问不到网关（或网关答非所问）：必须是未知，不能显示成已入库",
        (Edit(SERVICE, "                is Remote.Unusable -> return DeliveryView.Unknown(remote.reason)", "                is Remote.Unusable -> return DeliveryView.OnGateway(GatewayStage.STORED)"),),
        sync_tests("DestinationServiceTest"), SYNC_RESULTS,
        ("已入库但这会儿问不到网关", "网关答了错误"),
    ),
    Mutation(
        "status-wording-says-processed",
        "任何文案都不能说“已处理”：网关只知道 Agent 回了确认，不知道它有没有读懂或执行",
        (Edit(DESCRIPTIONS, '    GatewayStage.ACKNOWLEDGED -> "Agent 已确认（网关只知道它回了确认）"', '    GatewayStage.ACKNOWLEDGED -> "Agent 已处理"'),),
        app_tests("DestinationTextTest"), APP_RESULTS,
        ("没有任何一句话说“已处理”", "网关能证明的三步"),
    ),
    # ---- 评审 B1：文字进网关要单独授权（默认关，不确定就是不许）；严格仅本地是统一门禁，压过授权 ----
    Mutation(
        "policy-engine-gate-open",
        "评审 B1：引擎每次联系网关前必须先问“文字放行了吗”：门禁恒放行，配对之后没有任何授权的文字也会进网关",
        (POLICY_GATE_OPEN,),
        sync_tests("DataFlowGateTest"), SYNC_RESULTS,
        ("配了对、没有授权文字进网关", "没有授权时直接调 send", "没有授权时单独探测网关也不行", "被系统叫醒的补发也过同一道门"),
    ),
    Mutation(
        "real-gw-policy-gate-open",
        "评审 B1（真网关）：配对之后、没授权文字进网关时，真网关上一个 Revision 也不能有",
        (POLICY_GATE_OPEN,),
        REAL, REAL_RESULTS,
        ("配对之后没有任何 Destination、也没授权文字进网关",),
    ),
    Mutation(
        "policy-before-contact-gate-missing",
        "评审 B1：落盘之后、联系网关之前还要再问一次放行（中间用户可能收回了授权）：少这一道，收回之后还会去连网关。这一行同时是盖章（记下问之前的世代），没有“只删问、沿用旧章”的形式；变异是不问、直接按此刻的世代盖章",
        (Edit(ENGINE, "        var permit = permitFor(gateway) ?: return waiting(gateway, key, wasUncertain)\n", "        var permit = Permit(policy.generation)\n"),),
        sync_tests("DataFlowGateTest"), SYNC_RESULTS,
        ("问过之后、请求发出之前授权被收回",),
    ),
    Mutation(
        "policy-second-gate-missing",
        "评审 B1 / 复评 R4：紧挨着 PUT 的最后一问（状态查询的回答在路上时用户可能收回了授权）：少这一道，收回之后还会发出一个 PUT。变异是不问、直接按此刻的世代盖章；只删这一行、沿用上一张章的形式不等价，另列为 s3-last-ask-deleted-keeps-the-old-permit",
        (Edit(ENGINE, "        // 刚才被收回的，什么都没发出去，退回等待（“不确定”还原成发送之前的样子）\n        permit = permitFor(gateway) ?: return waiting(gateway, key, wasUncertain)\n", "        // 刚才被收回的，什么都没发出去，退回等待（“不确定”还原成发送之前的样子）\n        permit = Permit(policy.generation)\n"),),
        sync_tests("DataFlowGateTest", "PolicyAfterResponseTest"), SYNC_RESULTS,
        ("联系过网关、合同版本探测之后、紧挨着请求发出的最后一道", "问网关结果的回答在路上时授权被收回"),
    ),
    Mutation(
        "r4-no-gate-after-locating",
        "复评 R4：定位网关是一次网络往返，回来之后要再问一次放行：少这一道，往返期间收回授权之后，合同版本探测照发。只删这一行、沿用上一张章：整个 :sync:test 真跑全部通过（第三轮 1d，581 个），收回授权之后行为相同（请求被每个请求前的世代比较拦下），只差收回后多记一次本地地址、设置动了但没收回授权时这一轮多等一轮，对这条规则是等价变异；这里的变异是不问、直接按此刻的世代盖章",
        (Edit(ENGINE, "        permit = permitFor(gateway) ?: return waiting(gateway, key, wasUncertain)\n        rememberAddress(gateway, address)\n", "        permit = Permit(policy.generation)\n        rememberAddress(gateway, address)\n"),),
        sync_tests("PolicyAfterResponseTest"), SYNC_RESULTS,
        ("定位网关的回答在路上时授权被收回：不再发合同版本探测",),
    ),
    Mutation(
        "r4-no-gate-after-contract-probe",
        "复评 R4：合同版本探测也是网络往返，回来之后要再问一次放行：少这一道，往返期间收回授权之后，结果不确定的任务照样去问状态。变异是不问、直接按此刻的世代盖章；只删这一行、沿用探测之前的章的形式不等价，另列为 s3-contract-probe-ask-deleted-keeps-the-old-permit",
        (Edit(ENGINE, "        permit = permitFor(gateway) ?: return waiting(gateway, key, wasUncertain)\n\n        // 换了网关的话，", "        permit = Permit(policy.generation)\n\n        // 换了网关的话，"),),
        sync_tests("PolicyAfterResponseTest"), SYNC_RESULTS,
        ("合同版本探测的回答在路上时授权被收回",),
    ),
    Mutation(
        "r4-additions-no-gate-after-locating",
        "复评 R4：追加那一遍里定位网关回来之后也要再问一次放行：少这一道，收回授权之后合同版本探测照发。只删这一行、沿用上一张章：整个 :sync:test 真跑全部通过（第三轮 1d，581 个），收回授权之后行为相同（请求被每个请求前的世代比较拦下），只差收回后多记一次本地地址、设置动了但没收回授权时这一轮多等一轮，对这条规则是等价变异；这里的变异是不问、直接按此刻的世代盖章",
        (Edit(ENGINE, "        permit = permitFor(gateway) ?: return // 定位网关是一次网络往返：回来再问一次放行（复评 R4）\n", "        permit = Permit(policy.generation)\n"),),
        sync_tests("PolicyAfterResponseTest"), SYNC_RESULTS,
        ("追加：定位网关的回答在路上时授权被收回",),
    ),
    Mutation(
        "r4-additions-no-gate-before-each-request",
        "复评 R4：每一个追加请求之前都要问一次放行（前一个的回答在路上时用户可能收回了授权）：少这一道，收回之后后面的追加照发。只删这一行、沿用上一张章：整个 :sync:test 真跑全部通过（第三轮 1d，581 个），收回授权之后行为相同（请求被每个请求前的世代比较拦下），只差设置动了但没收回授权时，这一遍追加多等一轮，对这条规则是等价变异；这里的变异是不问、直接按此刻的世代盖章",
        (Edit(ENGINE, "            permit = permitFor(gateway) ?: return // 请求离开手机之前再问一次放行\n", "            permit = Permit(policy.generation)\n"),),
        sync_tests("PolicyAfterResponseTest"), SYNC_RESULTS,
        ("追加：第一个追加请求的回答在路上时授权被收回",),
    ),
    # ---- 复评 R5：每次改缓存/目标，都在“配对着的仍是这一份”的围栏里，判断与写账在配对存储的同一把锁里 ----
    Mutation(
        "r5-attempt-use-gateway-unfenced",
        "复评 R5：入库前换账本要在配对围栏里：不围，等定位回答的这一轮醒来时配对已是 B，它会清掉 B 的缓存、删掉 B 那条新文字的目标",
        (Edit(ENGINE, "        if (!fenced(gateway) { additions.useGateway(gateway.identity) }) return stale(key)\n", "        additions.useGateway(gateway.identity)\n"),),
        sync_tests("StalePassFenceTest", "PairingFenceAtomicityTest"), SYNC_RESULTS,
        ("一轮对着网关 A 的同步醒来时配对已经是 B", "引擎在入库之前换账本时"),
    ),
    Mutation(
        "r5-attempt-use-gateway-check-then-write",
        "复评 R5：围栏必须是同一把锁里的判断加写账，不能是“先看一眼再写”的两步：两步的做法在判断与写账之间留着缝，重新配对能插进去",
        (Edit(ENGINE, "        if (!fenced(gateway) { additions.useGateway(gateway.identity) }) return stale(key)\n", "        if (!stillPaired(gateway)) return stale(key)\n        additions.useGateway(gateway.identity)\n"),),
        sync_tests("PairingFenceAtomicityTest"), SYNC_RESULTS,
        ("引擎在入库之前换账本时",),
    ),
    Mutation(
        "r5-put-success-record-unfenced",
        "复评 R5：入库成功后的记账也在围栏里：不围，记账与重新配对可以交错，旧网关的回答落进新配对的账",
        (Edit(ENGINE, "SendResult = settle(gateway, key) {\n        additions.gatewayHas(key, deliveredTo)\n        deliver(key, SyncEvent.STORED_CONFIRMED) { it.copy(uncertain = false) }\n        SendResult.STORED\n    }\n", "SendResult {\n        additions.gatewayHas(key, deliveredTo)\n        deliver(key, SyncEvent.STORED_CONFIRMED) { it.copy(uncertain = false) }\n        return SendResult.STORED\n    }\n"),),
        sync_tests("PairingFenceAtomicityTest"), SYNC_RESULTS,
        ("入库成功后记账时",),
    ),
    Mutation(
        "r5-additions-use-gateway-unfenced",
        "复评 R5：追加那一遍开始换账本也在围栏里：不围，旧配对读到的网关编号会把新配对的缓存与目标清掉",
        (Edit(ENGINE, "        if (gateway != null && !fenced(gateway) { additions.useGateway(gateway.identity) }) return // 读到配对之后又换了：旧配对的残余不碰新配对的账（复评 R5）\n", "        if (gateway != null) additions.useGateway(gateway.identity)\n"),),
        sync_tests("PairingFenceAtomicityTest"), SYNC_RESULTS,
        ("追加那一遍开始换账本时",),
    ),
    Mutation(
        "r5-additions-record-unfenced",
        "复评 R5：追加的回答记账（网关已有 / 不会建）也在围栏里：不围，旧网关对追加的回答会写进新配对的账（接线守卫钉住每一处改账调用都在围栏里）",
        (Edit(
            ENGINE,
            "                    val recorded = fenced(gateway) {\n"
            "                        if (result.value.deliveries.any { it.destinationId == addition.destinationId }) additions.gatewayHas(addition.key, ids)\n"
            "                        else additions.refused(addition.key, ids, Refusal.NOT_CREATED)\n"
            "                    }\n"
            "                    if (!recorded) return\n",
            "                    if (result.value.deliveries.any { it.destinationId == addition.destinationId }) additions.gatewayHas(addition.key, ids)\n"
            "                    else additions.refused(addition.key, ids, Refusal.NOT_CREATED)\n",
        ),),
        app_tests("PairingFenceWiringTest"), APP_RESULTS,
        ("引擎里每一处改缓存与目标的调用",),
    ),
    Mutation(
        "r5-service-apply-unfenced",
        "复评 R5：管理页读写目的地之后写缓存要在围栏里：不围，请求途中重新配对了，旧网关的回答照样写进新配对的缓存",
        (Edit(
            SERVICE,
            "                pairing.whileCurrent(session.gateway) { apply(session, result.value) } ?: return@withLock ServiceResult.Failed(ServiceFailure.NotPaired)\n",
            "                apply(session, result.value)\n",
        ),),
        sync_tests("PairingFenceAtomicityTest"), SYNC_RESULTS,
        ("管理页读写目的地之后写缓存时",),
    ),
    Mutation(
        "r5-issue-credential-apply-unfenced",
        "复评 R5：签发凭证之后写缓存也要在围栏里：不围，换了配对之后旧网关签发的结果写进新配对的缓存（接线守卫）",
        (Edit(SERVICE, "                        pairing.whileCurrent(session.gateway) {\n                            dispatch.applyDestinations(session.gateway.identity, listOf(known.copy(credentialIssued = true, credentialActive = true)), authoritative = false, now = clock.now())\n                        } ?: return@withLock CredentialOutcome.Failed(ServiceFailure.NotPaired)\n", "                        dispatch.applyDestinations(session.gateway.identity, listOf(known.copy(credentialIssued = true, credentialActive = true)), authoritative = false, now = clock.now())\n"),),
        app_tests("PairingFenceWiringTest"), APP_RESULTS,
        ("目的地服务里写缓存的两处",),
    ),
    Mutation(
        "r5-file-store-while-current-ignores-pairing",
        "复评 R5：whileCurrent 只在配对着的仍是这一份时执行：不看配对身份，换了配对（或取消、记录丢了）之后旧配对的残余照样执行",
        (Edit(PAIRING_FILE_STORE, "        if (current?.isSamePairing(gateway) == true) block() else null\n", "        block()\n"),),
        sync_tests("FilePairingStoreTest"), SYNC_RESULTS,
        ("whileCurrent 只在配对着的仍是这一份时执行",),
    ),
    Mutation(
        "r5-file-store-while-current-no-lock",
        "复评 R5：whileCurrent 的判断与动作在存储的同一把锁里：不持锁，动作进行时重新配对（save）能插进来",
        (Edit(
            PAIRING_FILE_STORE,
            "    override suspend fun <T : Any> whileCurrent(gateway: PairedGateway, block: suspend () -> T): T? = mutex.withLock {",
            "    override suspend fun <T : Any> whileCurrent(gateway: PairedGateway, block: suspend () -> T): T? = run {",
        ),),
        sync_tests("FilePairingStoreTest"), SYNC_RESULTS,
        ("whileCurrent 持锁期间 save 必须等着",),
    ),
    Mutation(
        "r5-observable-while-current-no-fence",
        "复评 R5：可观察的外壳转交 whileCurrent 时也不能放掉围栏：先 load 判断、再在锁外执行，就把“判断与动作之间”的缝又开了",
        (Edit(
            PAIRING_OBSERVABLE,
            "    override suspend fun <T : Any> whileCurrent(gateway: PairedGateway, block: suspend () -> T): T? = mutex.withLock { delegate.whileCurrent(gateway, block) }",
            "    override suspend fun <T : Any> whileCurrent(gateway: PairedGateway, block: suspend () -> T): T? =\n"
            "        if ((delegate.load() as? PairingState.Paired)?.gateway?.isSamePairing(gateway) == true) block() else null",
        ),),
        sync_tests("ObservablePairingStoreTest"), SYNC_RESULTS,
        ("whileCurrent 经过可观察的外壳也持锁",),
    ),
    # ---- 复评 R6：没有“剪贴板里还是我们放的”的证明就绝不清；离开时清；标成敏感；复制之后说明 ----
    Mutation(
        "r6-timeout-clears-unreadable",
        "复评 R6：到点时读不了剪贴板（应用在后台）不能清：盲清会抹掉用户后来复制的别的东西（原来的缺陷）",
        (Edit(CLIPBOARD_GUARD, '            ClipboardPeek.Unreadable -> log.event("clipboard.unverifiable")\n', "            ClipboardPeek.Unreadable -> clear()\n"),),
        app_tests("SecretClipboardGuardTest"), APP_RESULTS,
        (
            "应用不在前台（系统不让读剪贴板）时到点",
            "读不了的时候剪贴板其实已被别的应用换成了别的东西",
            "到点时读不了、回到前台时那里已是别人的东西",
        ),
    ),
    Mutation(
        "r6-leaving-clears-without-verification",
        "复评 R6：面板关闭 / 离开 / 即将失去焦点时也要先认出是自己放的才清：不认就清，等于在读不到或别人的东西上盲清",
        (Edit(
            CLIPBOARD_GUARD,
            "        if (port.peek() is ClipboardPeek.Ours) {\n            timer?.cancel()\n            clear()\n        }\n",
            "        timer?.cancel()\n        clear()\n",
        ),),
        app_tests("SecretClipboardGuardTest"), APP_RESULTS,
        ("离开时读不了——没有归属证明，不清", "离开时剪贴板里是别的应用放的东西——不动"),
    ),
    Mutation(
        "r6-leaving-never-clears",
        "复评 R6：读得到且认得出是自己放的时候，离开就要当场清：不清，凭证会跟着用户离开应用留在剪贴板里",
        (Edit(
            CLIPBOARD_GUARD,
            "        if (port.peek() is ClipboardPeek.Ours) {\n            timer?.cancel()\n            clear()\n        }\n",
            "        if (port.peek() is ClipboardPeek.Ours) {\n            timer?.cancel()\n        }\n",
        ),),
        app_tests("SecretClipboardGuardTest"), APP_RESULTS,
        ("面板关闭、页面离开或应用即将失去焦点时", "守卫自己没有复制过，但端口证明剪贴板里是我们放的"),
    ),
    Mutation(
        "r6-activity-no-pause-hook",
        "复评 R6：应用即将失去焦点（onPause）时要叫守卫：不叫，用户一按 Home 就失去了最后一次能读到剪贴板的机会",
        (Edit(MAIN_ACTIVITY, "        runtime.secretClipboard.onLeaving()\n        super.onPause()\n", "        super.onPause()\n"),),
        app_tests("ClipboardWiringTest"), APP_RESULTS,
        ("应用即将失去焦点（onPause，那一刻还读得到剪贴板）",),
    ),
    Mutation(
        "r6-page-dispose-no-leaving",
        "复评 R6：离开详情页时要叫守卫：不叫，复制之后返回列表，凭证还留在剪贴板里",
        (Edit(PAGE, "                    clipboard.onLeaving() // 离开详情页：剪贴板里还是自己放的凭证就清（读不到、或是别人的不动）\n", ""),),
        app_tests("ClipboardWiringTest"), APP_RESULTS,
        ("应用即将失去焦点（onPause，那一刻还读得到剪贴板）",),
    ),
    Mutation(
        "r6-port-not-sensitive",
        "复评 R6：放进剪贴板时要标成敏感内容：不标，系统的剪贴板预览与输入法会把凭证原文亮出来",
        (Edit(CLIPBOARD_PORT, "        clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }\n", "        clip.description.extras = PersistableBundle()\n"),),
        app_tests("ClipboardWiringTest"), APP_RESULTS,
        ("放进去时标成敏感内容",),
    ),
    Mutation(
        "r6-no-notice-after-copy",
        "复评 R6：复制之后要用一句话说明剪贴板里的凭证的下落（面板已经抹掉了）：不说，用户不知道离开应用就清、也不知道后台清不了",
        (Edit(PAGE, "                    Toast.makeText(context, describeClipboardNotice(), Toast.LENGTH_LONG).show() // 面板已经抹掉了，复制之后的下落只能这样告诉用户\n", ""),),
        app_tests("ClipboardWiringTest"), APP_RESULTS,
        ("复制之后提示一句话",),
    ),
    Mutation(
        "r6-notice-hides-platform-limit",
        "复评 R6：提示必须如实说“已在后台时系统不让读、清不了、要自行清空”：藏起这个平台限制，用户会以为一定清掉了",
        (Edit(DESCRIPTIONS, '        "已在后台时系统不让本应用读取，清不了，请自行清空。清掉后只能轮换出新的。"', '        "清掉后只能轮换出新的。"'),),
        app_tests("DestinationTextTest"), APP_RESULTS,
        ("复评 R6：复制之后的提示如实说清掉的条件与做不到的事",),
    ),
    # ---- 复评 R8：授权那一行的时间读不懂就当没授权（不确定就是不许） ----
    Mutation(
        "r8-grant-time-not-checked",
        "复评 R8：授权那一行的时间也要逐字合规：不看时间，被改坏的行（granted v2 … nonsense）仍然放行文字",
        (Edit(POLICY_SQL, "        if (!isCanonicalInstant(parts[5])) return null\n", ""),),
        platform_tests("DataFlowPolicySqlTest"), PLATFORM_RESULTS,
        ("复评 R8：授权那一行的时间读不懂就当没授权",),
    ),
    Mutation(
        "r8-grant-time-lenient",
        "复评 R8：时间要按规范写法逐字往返才算：宽松解析认下的写法（偏移量、小写、多余的零、闰秒）不能算授权",
        (Edit(POLICY_SQL, "        Instant.parse(text).toString() == text\n", "        Instant.parse(text)\n        true\n"),),
        platform_tests("DataFlowPolicySqlTest"), PLATFORM_RESULTS,
        ("复评 R8：授权那一行的时间读不懂就当没授权",),
    ),
    Mutation(
        "r8-grant-time-unparseable-accepted",
        "复评 R8：解析不出来的时间按“不是授权”处理：不能因为解析抛了异常就放行（fail-closed）",
        (Edit(POLICY_SQL, "    } catch (_: RuntimeException) {\n        false\n    }", "    } catch (_: RuntimeException) {\n        true\n    }"),),
        platform_tests("DataFlowPolicySqlTest"), PLATFORM_RESULTS,
        ("复评 R8：授权那一行的时间读不懂就当没授权",),
    ),
    Mutation(
        "r8-grant-time-over-strict",
        "复评 R8：严格不能误伤：grantTo 自己写出的时刻读回来必须还是授权，过严会让用户的授权悄悄失效、文字永远发不出去",
        (Edit(POLICY_SQL, "        Instant.parse(text).toString() == text\n", "        Instant.parse(text).toString() == text && text.length == 20\n"),),
        platform_tests("DataFlowPolicySqlTest"), PLATFORM_RESULTS,
        ("复评 R8：严格不等于误伤",),
    ),
    # ---- 复评 R9：存档读失败时先关门（Closed），之后按退避重试，读到了发出真实值（不能永远停在 Closed 上） ----
    Mutation(
        "r9-sqlite-states-no-recovery",
        "复评 R9：SqliteDataFlowPolicy 的存档变化流要走恢复算子：改回“每次变动读一次、失败就停在 Closed”，一次暂时的数据库故障之后引擎与界面永远不许发（接线守卫）",
        (
            Edit(SQLITE_POLICY, "changes.recoveringStoredStates(::readOrNull).distinctUntilChanged()", "changes.map { stored() }.distinctUntilChanged()"),
            Edit(SQLITE_POLICY, "import kotlinx.coroutines.flow.distinctUntilChanged\n", "import kotlinx.coroutines.flow.distinctUntilChanged\nimport kotlinx.coroutines.flow.map\n"),
        ),
        app_tests("StoredStatesWiringTest"), APP_RESULTS,
        ("SqliteDataFlowPolicy 的存档变化流走恢复算子",),
    ),
    Mutation(
        "r9-sqlite-read-closes-before-operator",
        "复评 R9：读失败要让算子看得见（null）：在读里先吞成 Closed，算子分不清“真的最保守”与“暂时读不到”，就不会重试",
        (Edit(SQLITE_POLICY, "DataFlowPolicySql(AndroidSqlDb(db)).read() }\n    } catch (cancelled: CancellationException) {\n        throw cancelled\n    } catch (_: Exception) {\n        null\n", "DataFlowPolicySql(AndroidSqlDb(db)).readOrClosed() }\n    } catch (cancelled: CancellationException) {\n        throw cancelled\n    } catch (_: Exception) {\n        null\n"),),
        app_tests("StoredStatesWiringTest"), APP_RESULTS,
        ("SqliteDataFlowPolicy 的存档变化流走恢复算子",),
    ),
    Mutation(
        "r9-sqlite-read-swallows-cancellation",
        "复评 R9：读失败的捕获不能吞掉取消：吞了，收集者取消之后读还当成“数据库故障”继续重试",
        (Edit(SQLITE_POLICY, "DataFlowPolicySql(AndroidSqlDb(db)).read() }\n    } catch (cancelled: CancellationException) {\n        throw cancelled\n    } catch (_: Exception) {\n        null\n", "DataFlowPolicySql(AndroidSqlDb(db)).read() }\n    } catch (_: Exception) {\n        null\n"),),
        app_tests("StoredStatesWiringTest"), APP_RESULTS,
        ("SqliteDataFlowPolicy 的存档变化流走恢复算子",),
    ),
    Mutation(
        "r9-operator-no-retry",
        "复评 R9：读不出来要一直按退避重试到读到为止：只试一轮，数据库好了也没有任何东西再触发读，授权好好存着却永远发不出去（原来的缺陷）",
        (Edit(STORED_STATES, "        while (true) {\n", "        repeat(1) {\n"),),
        sync_tests("StoredStatesRecoveryTest"), SYNC_RESULTS,
        ("读不出来：先立刻回答 Closed，再按", "评审说的那个缺口"),
    ),
    Mutation(
        "r9-operator-no-closed-on-failure",
        "复评 R9：读不出来的当下就要回答 Closed（不确定就是不许）：不发，引擎与界面沿用故障前的旧答案，授权已被撤销也照发（fail-open）",
        (Edit(STORED_STATES, "            if (failures == 0) send(StoredDataFlow.Closed)\n", ""),),
        sync_tests("StoredStatesRecoveryTest"), SYNC_RESULTS,
        ("读不出来：先立刻回答 Closed，再按", "评审说的那个缺口", "故障期间又来了新的变动"),
    ),
    Mutation(
        "r9-operator-closed-after-first-delay",
        "复评 R9：Closed 要在读失败的那一刻发，不能等第一次退避之后才发：这段时间里旧答案仍然放行",
        (
            Edit(STORED_STATES, "            if (failures == 0) send(StoredDataFlow.Closed)\n            delay(", "            delay("),
            Edit(STORED_STATES, "            failures += 1\n", "            if (failures == 0) send(StoredDataFlow.Closed)\n            failures += 1\n"),
        ),
        sync_tests("StoredStatesRecoveryTest"), SYNC_RESULTS,
        ("读不出来：先立刻回答 Closed，再按",),
    ),
    Mutation(
        "r9-operator-closed-on-every-failure",
        "复评 R9：读不出来期间只发一次 Closed：每次重试失败都再发一次，下游每次都被唤醒、重复重算",
        (Edit(STORED_STATES, "            if (failures == 0) send(StoredDataFlow.Closed)\n", "            send(StoredDataFlow.Closed)\n"),),
        sync_tests("StoredStatesRecoveryTest"), SYNC_RESULTS,
        ("读不出来：先立刻回答 Closed，再按",),
    ),
    Mutation(
        "r9-operator-backoff-no-cap",
        "复评 R9：退避要有 30 秒的封顶：不封顶会翻倍到几分钟、几小时，数据库早就好了还不去读",
        (Edit(STORED_STATES, "delay((STORED_STATES_RETRY_BASE_MS shl failures.coerceAtMost(MAX_DOUBLINGS)).coerceAtMost(STORED_STATES_RETRY_MAX_MS))", "delay((STORED_STATES_RETRY_BASE_MS shl failures.coerceAtMost(MAX_DOUBLINGS)))"),),
        sync_tests("StoredStatesRecoveryTest"), SYNC_RESULTS,
        ("读不出来：先立刻回答 Closed，再按",),
    ),
    Mutation(
        "r9-operator-backoff-constant",
        "复评 R9：退避要翻倍：恒定 2 秒会在数据库长时间故障时每 2 秒打一次盘",
        (Edit(STORED_STATES, "delay((STORED_STATES_RETRY_BASE_MS shl failures.coerceAtMost(MAX_DOUBLINGS)).coerceAtMost(STORED_STATES_RETRY_MAX_MS))", "delay(STORED_STATES_RETRY_BASE_MS)"),),
        sync_tests("StoredStatesRecoveryTest"), SYNC_RESULTS,
        ("读不出来：先立刻回答 Closed，再按",),
    ),
    Mutation(
        "r9-operator-not-latest",
        "复评 R9：新的变动要打断旧的重试、立刻重读：不打断，新变动被挡在重试后面，用户刚改的设置要等旧的退避走完才生效",
        (Edit(STORED_STATES, "    collectLatest {\n", "    collect {\n"), Edit(STORED_STATES, "return@collectLatest", "return@collect")),
        sync_tests("StoredStatesRecoveryTest"), SYNC_RESULTS,
        ("故障期间又来了新的变动",),
    ),
    # ---- 复评 R10：预览确认的目标在暂停期间被记成“没建投递”，界面要如实说，也不能摆出两个什么也不做的按钮 ----
    Mutation(
        "r10-vm-drops-delivery",
        "复评 R10：视图模型要把账本里的网关投递与拒收原因交给界面：丢掉，被拒收的目标在续签恢复之后看上去仍是“等你确认”",
        (Edit(SEND_REVIEW_VM, "it.eligible, it.delivery, it.refusal) },", "it.eligible) },"),),
        app_tests("SendReviewViewModelTest"), APP_RESULTS,
        # 真跑之后改正的预期：组合测试直接构造 ReviewTarget、不经过 toReview()，拦这一处的是走完整条路径的行为测试
        ("复评 R10：预览确认的目标在目的地暂停期间被记成没建投递",),
    ),
    Mutation(
        "r10-awaiting-ignores-refused",
        "复评 R10：界面的“等你决定”必须与账本的 awaitingUser 同一个判断：忽略被拒收的，就会摆出确认与不发送两个返回 NotEligible 的按钮",
        (Edit(SEND_REVIEW_VM, " && eligible && delivery != GatewayDelivery.REFUSED\n", " && eligible\n"),),
        app_tests("SendReviewViewModelTest"), APP_RESULTS,
        ("复评 R10：预览确认的目标在目的地暂停期间被记成没建投递", "复评 R10：界面认为在等你决定，当且仅当账本认为在等你决定"),
    ),
    Mutation(
        "r10-describe-ignores-refusal",
        "复评 R10：没建投递的目标，预览页要如实说“没有送给它”：沿用“等你确认 / 自动发送 / 你已确认发送”，用户会以为它会发",
        (Edit(DESCRIPTIONS, "    decision != TargetDecision.SKIPPED && delivery == GatewayDelivery.REFUSED -> describeRefusal(refusal)\n", ""),),
        app_tests("DestinationTextTest"), APP_RESULTS,
        ("复评 R10：网关没有为这个目标建投递时，预览页如实说没送给它",),
    ),
    Mutation(
        "r10-skipped-overridden-by-refusal",
        "复评 R10：用户自己选了不发送的目标仍说“你选择了不发送”：被拒收的说法盖过它，就把用户的决定说成了系统的拒收",
        (Edit(DESCRIPTIONS, "    decision != TargetDecision.SKIPPED && delivery == GatewayDelivery.REFUSED -> describeRefusal(refusal)\n", "    delivery == GatewayDelivery.REFUSED -> describeRefusal(refusal)\n"),),
        app_tests("DestinationTextTest"), APP_RESULTS,
        ("复评 R10：网关没有为这个目标建投递时，预览页如实说没送给它",),
    ),
    Mutation(
        "r10-refusal-reasons-merged",
        "复评 R10：暂停期间错过的与网关拒收的要分开说：合成一句，用户分不清“恢复就能发”还是“这条永远不会发”（暂停的不补发）",
        (Edit(
            DESCRIPTIONS,
            '    if (refusal == Refusal.SUSPENDED) "没有送给它：它的凭证被撤销、暂停期间这条文字没有建投递，恢复之后也不补发"\n    else "没有送给它：网关没有为它建投递"\n',
            '    "没有送给它：网关没有为它建投递"\n',
        ),),
        app_tests("DestinationTextTest"), APP_RESULTS,
        ("复评 R10：网关没有为这个目标建投递时，预览页如实说没送给它",),
    ),
    Mutation(
        "r10-screen-uses-old-describe",
        "复评 R10：待发送页写每个目标的那句话要带上网关投递与拒收原因（接线守卫）：用老的两个参数，界面又会对着被拒收的目标写“等你确认”",
        (Edit(SEND_REVIEW_SCREEN, "describeDecision(target.decision, target.eligible, target.delivery, target.refusal))", "describeDecision(target.decision, target.eligible))"),),
        app_tests("ReviewTargetWiringTest"), APP_RESULTS,
        ("待发送页给每个目标写的那句话带上网关投递与拒收原因",),
    ),
    Mutation(
        "r10-buttons-ignore-ledger",
        "复评 R10：确认与不发送两个按钮只在 awaitingUser 时才有（接线守卫）：改成只看“目的地可用”，被拒收的目标又摆出两个什么也不做的按钮",
        (Edit(SEND_REVIEW_SCREEN, "if (target.awaitingUser) {", "if (target.eligible) {"),),
        app_tests("ReviewTargetWiringTest"), APP_RESULTS,
        ("待发送页给每个目标写的那句话带上网关投递与拒收原因",),
    ),
    # ---- 复评 R11：详情页“本机之外的变化”流（同步状态）暂时出故障，不能让它的收集者从此死掉 ----
    Mutation(
        "r11-detail-refresh-uncontained",
        "复评 R11：详情页收集“同步状态变化”要用与其他读流同一套的重试围栏：裸收集，一次暂时的故障就让这个收集者永远结束，详情页再也不随网关侧变化刷新",
        (Edit(DETAIL_VM, 'refreshOn.containReadFailures("destination.detail.refresh", log).collect', "refreshOn.collect"),),
        app_tests("ReadFailureContainmentTest"), APP_RESULTS,
        ("复评 R11：详情页的“本机之外的变化”流",),
    ),
    # ---- 复评 R12：叫醒要在提交确认之前登记（先登记、再提交），并且不能被规划器手里过时的“没活了”取消 ----
    Mutation(
        "r12-confirm-no-prewake",
        "复评 R12：用户确认之前要先登记叫醒：不套这层，提交之后才等规划器异步登记，两者之间进程被杀就再也没人把应用叫起来",
        (Edit(WAKE_GATE, "            wakes.ahead { inner.decide(key, destinationId, choice, now) } ?: DecideResult.WakeUnavailable\n", "            inner.decide(key, destinationId, choice, now)\n"),),
        sync_tests("ResumeRecoveryTest", "ResumePlannerTest"), SYNC_RESULTS,
        ("确认预览：提交的那一刻系统里已经登记着叫醒", "确认提交之后、规划器还没来得及反应进程就被杀", "预先登记被系统拒绝"),
    ),
    Mutation(
        "r12-prewake-after-commit",
        "复评 R12：登记叫醒必须在提交之前：先提交、后登记，两者之间被杀（提交已落账、叫醒还没有）就没人来发",
        (Edit(WAKE_GATE, "    suspend fun <T> ahead(commit: suspend () -> T): T? = lock.withLock {\n        try {\n            scheduler.ensureScheduled()\n            told = null\n        } catch (cancelled: CancellationException) {\n            throw cancelled\n        } catch (failure: Exception) {\n            log.event(\"resume.prewake-failed ${failure::class.simpleName}\")\n            return@withLock null\n        }\n        commit()\n    }\n", "    suspend fun <T> ahead(commit: suspend () -> T): T? = lock.withLock {\n        val committed = commit()\n        try {\n            scheduler.ensureScheduled()\n            told = null\n        } catch (cancelled: CancellationException) {\n            throw cancelled\n        } catch (failure: Exception) {\n            log.event(\"resume.prewake-failed ${failure::class.simpleName}\")\n            return@withLock null\n        }\n        committed\n    }\n"),),
        sync_tests("ResumeRecoveryTest", "ResumePlannerTest"), SYNC_RESULTS,
        ("确认预览：提交的那一刻系统里已经登记着叫醒", "确认提交之后、规划器还没来得及反应进程就被杀"),
    ),
    Mutation(
        "r12-prewake-failure-blocks-confirm",
        "复评 R12 / 再评 S8：系统拒绝登记要收在门里（不提交、回 WakeUnavailable）：让异常往外抛，确认的调用方拿到的是一个异常而不是明确的“没记下”",
        (Edit(WAKE_GATE, "    suspend fun <T> ahead(commit: suspend () -> T): T? = lock.withLock {\n        try {\n            scheduler.ensureScheduled()\n            told = null\n        } catch (cancelled: CancellationException) {\n            throw cancelled\n        } catch (failure: Exception) {\n            log.event(\"resume.prewake-failed ${failure::class.simpleName}\")\n            return@withLock null\n        }\n        commit()\n    }\n", "    suspend fun <T> ahead(commit: suspend () -> T): T? = lock.withLock {\n        scheduler.ensureScheduled()\n        told = null\n        commit()\n    }\n"),),
        sync_tests("ResumeRecoveryTest", "ResumePlannerTest"), SYNC_RESULTS,
        ("预先登记被系统拒绝",),
    ),
    Mutation(
        "r12-skip-also-prewakes",
        "复评 R12：只有“确认发送”才欠追加、才预先登记：连“不发送”也登记，白白打扰系统（登记了又没有活）",
        (Edit(WAKE_GATE, "        if (choice == Choice.CONFIRM) {\n            wakes.ahead { inner.decide(key, destinationId, choice, now) } ?: DecideResult.WakeUnavailable\n        } else {\n            inner.decide(key, destinationId, choice, now)\n        }\n", "        wakes.ahead { inner.decide(key, destinationId, choice, now) } ?: DecideResult.WakeUnavailable\n"),),
        sync_tests("ResumeRecoveryTest", "ResumePlannerTest"), SYNC_RESULTS,
        ("“不发送”不欠任何追加",),
    ),
    Mutation(
        "r12-planner-outside-lock",
        "复评 R12：规划器“现算 + 登记或取消”要在门的锁里：不在锁里，读完账到取消之间用户确认了，手里过时的“没活了”把刚登记的叫醒抹掉",
        (Edit(WAKE_GATE, "        lock.withLock {\n            val now = needed()\n", "        run {\n            val now = needed()\n"),),
        sync_tests("ResumeRecoveryTest", "ResumePlannerTest"), SYNC_RESULTS,
        ("规划器正在现算“没活了”的当口用户确认了", "确认正在提交（叫醒已经预先登记）的当口"),
    ),
    Mutation(
        "r12-ahead-outside-lock",
        "复评 R12：用户确认的“登记 + 提交”要在门的锁里：不在锁里，规划器正在现算时确认插进去，随后规划器过时的取消把叫醒抹掉",
        (
            Edit(WAKE_GATE, "    suspend fun <T> ahead(commit: suspend () -> T): T? = lock.withLock {\n", "    suspend fun <T> ahead(commit: suspend () -> T): T? = run {\n"),
            Edit(WAKE_GATE, "            return@withLock null\n", "            return@run null\n"),
        ),
        sync_tests("ResumeRecoveryTest", "ResumePlannerTest"), SYNC_RESULTS,
        ("规划器正在现算“没活了”的当口用户确认了", "确认正在提交（叫醒已经预先登记）的当口"),
    ),
    Mutation(
        "r12-commit-outside-lock",
        "复评 R12：提交本身也要在锁里：只有登记在锁里、提交在锁外，规划器能在“已登记、未落账”的当口现算出“没活了”并取消，随后提交落账时系统里没有叫醒",
        (Edit(WAKE_GATE, "    suspend fun <T> ahead(commit: suspend () -> T): T? = lock.withLock {\n        try {\n            scheduler.ensureScheduled()\n            told = null\n        } catch (cancelled: CancellationException) {\n            throw cancelled\n        } catch (failure: Exception) {\n            log.event(\"resume.prewake-failed ${failure::class.simpleName}\")\n            return@withLock null\n        }\n        commit()\n    }\n", "    suspend fun <T> ahead(commit: suspend () -> T): T? {\n        lock.withLock {\n            try {\n                scheduler.ensureScheduled()\n                told = null\n            } catch (cancelled: CancellationException) {\n                throw cancelled\n            } catch (failure: Exception) {\n                log.event(\"resume.prewake-failed ${failure::class.simpleName}\")\n                return null\n            }\n        }\n        return commit()\n    }\n"),),
        sync_tests("ResumeRecoveryTest", "ResumePlannerTest"), SYNC_RESULTS,
        ("确认正在提交（叫醒已经预先登记）的当口",),
    ),
    Mutation(
        "r12-planner-reads-before-lock",
        "复评 R12：账要在锁里读（现算）：锁外先读好再进锁，等于带着过时的答案去登记或取消",
        (Edit(
            RESUME_PLANNER,
            "            .collect { (pairingState, list, dataFlow) -> gate.reconcile { needsResume(pairingState, list, dataFlow, additionsOwed()) } }\n",
            "            .collect { (pairingState, list, dataFlow) ->\n                val owedNow = additionsOwed()\n                gate.reconcile { needsResume(pairingState, list, dataFlow, owedNow) }\n            }\n",
        ),),
        sync_tests("ResumeRecoveryTest", "ResumePlannerTest"), SYNC_RESULTS,
        ("规划器正在现算“没活了”的当口用户确认了",),
    ),
    Mutation(
        "r12-told-stays-true-after-prewake",
        "复评 R12：预先登记之后要把“告诉过系统什么”忘掉：记成“有”，而提交什么也没改时没有信号叫规划器再算，系统又已把那个叫醒收走，之后新来的活就因“结论没变”而不登记",
        (Edit(WAKE_GATE, "            scheduler.ensureScheduled()\n            told = null\n        } catch", "            scheduler.ensureScheduled()\n            told = true\n        } catch"),),
        sync_tests("ResumeRecoveryTest", "ResumePlannerTest"), SYNC_RESULTS,
        ("预先登记之后账上什么也没变",),
    ),
    Mutation(
        "r12-gate-no-dedupe",
        "复评 R12：结论与上次告诉系统的一样就不再打扰系统：不去重，每个输入变一次就登记或取消一次",
        (Edit(WAKE_GATE, "            if (now != told) {\n", "            if (true) {\n"),),
        sync_tests("ResumeRecoveryTest", "ResumePlannerTest"), SYNC_RESULTS,
        ("结论没变就不重复打扰系统",),
    ),
    Mutation(
        "r12-gate-told-before-apply",
        "复评 R12：登记或取消抛异常时不能记成“告诉过了”：先记后做，系统拒绝之后规划器的重试看到“结论没变”就跳过，请求丢了",
        (Edit(WAKE_GATE, "                if (now) scheduler.ensureScheduled() else scheduler.cancel()\n                told = now\n", "                told = now\n                if (now) scheduler.ensureScheduled() else scheduler.cancel()\n"),),
        sync_tests("ResumeRecoveryTest", "ResumePlannerTest"), SYNC_RESULTS,
        ("系统暂时拒绝登记",),
    ),
    Mutation(
        "r12-runtime-planner-own-gate",
        "复评 R12：规划器要拿和确认路同一道门（接线守卫）：不传，规划器给自己造一道，两条路各锁各的，门形同虚设",
        (Edit(SYNC_RUNTIME, "        gate = wakes,\n", ""),),
        app_tests("ResumeWiringGuardTest"), APP_RESULTS,
        ("确认预览的路与叫醒规划器共用同一道门",),
    ),
    Mutation(
        "r12-runtime-dispatch-unwrapped",
        "复评 R12：界面、引擎用的发送账要是套了“先登记叫醒”壳的那一份（接线守卫）：不套，用户确认走的还是老路",
        (Edit(SYNC_RUNTIME, "val dispatch: DispatchStore = WakeAheadDispatchStore(SqliteDispatchStore(library), wakes)", "val dispatch: DispatchStore = SqliteDispatchStore(library)"),),
        app_tests("ResumeWiringGuardTest"), APP_RESULTS,
        ("确认预览的路与叫醒规划器共用同一道门",),
    ),
    # ---- 复评 R13：意图标签只在传输真正开始时冻结（紧挨着 PUT 之前），被拦下或中途作废的尝试不锁标签 ----
    Mutation(
        "r13-engine-seals-before-status-query",
        "复评 R13：回到原来的顺序：先构造提交（冻结意图）、再问网关、再问最后一道放行：问完网关被拦下或配对被换，什么也没发，标签却已经锁死",
        (
            Edit(ENGINE, "        val textSha256 = source.textSha256For(key) ?: return blocked(gateway, key, BlockedReason.LOCAL_DATA_MISSING)\n", "        val submission = source.submissionFor(key) ?: return blocked(gateway, key, BlockedReason.LOCAL_DATA_MISSING)\n        val textSha256 = submission.textSha256\n"),
            Edit(ENGINE, "        val built = pairing.whileCurrent(gateway) { Built(source.submissionFor(key)) } ?: return stale(key)\n        val submission = built.submission ?: return blocked(gateway, key, BlockedReason.LOCAL_DATA_MISSING)\n", ""),
        ),
        sync_tests("DispatchEngineTest"), SYNC_RESULTS,
        ('复评 R13：紧挨着请求的最后一道放行没通过', '复评 R13：结果不确定、从没真正发出去过的任务先问网关', '复评 R13：问网关的路上用户改配了另一个网关'),
    ),
    Mutation(
        "r13-engine-seals-before-final-check",
        "复评 R13：构造提交（冻结意图）要排在最后一道放行之后：排在前面，授权在这一步收回时请求没发、标签已锁",
        (
            Edit(ENGINE, "        val built = pairing.whileCurrent(gateway) { Built(source.submissionFor(key)) } ?: return stale(key)\n        val submission = built.submission ?: return blocked(gateway, key, BlockedReason.LOCAL_DATA_MISSING)\n", ""),
            Edit(ENGINE, "        // 请求离开手机之前的最后一问（复评 R4）：状态查询的回答在路上时授权可能收回了。\n", "        val submission = source.submissionFor(key) ?: return blocked(gateway, key, BlockedReason.LOCAL_DATA_MISSING)\n        // 请求离开手机之前的最后一问（复评 R4）：状态查询的回答在路上时授权可能收回了。\n"),
        ),
        sync_tests("DispatchEngineTest"), SYNC_RESULTS,
        ('复评 R13：紧挨着请求的最后一道放行没通过', '复评 R13：结果不确定、从没真正发出去过的任务先问网关'),
    ),
    Mutation(
        "r13-engine-peeks-through-submission",
        "复评 R13：先看哈希不能借道构造提交：借道就是冻结，问网关之前标签已锁（哈希本身可以取，只是不能经过会冻结的入口）",
        (Edit(ENGINE, "        val textSha256 = source.textSha256For(key) ?: return blocked(gateway, key, BlockedReason.LOCAL_DATA_MISSING)\n", "        val textSha256 = source.submissionFor(key)?.textSha256 ?: return blocked(gateway, key, BlockedReason.LOCAL_DATA_MISSING)\n"),),
        sync_tests("DispatchEngineTest"), SYNC_RESULTS,
        ('复评 R13：紧挨着请求的最后一道放行没通过', '复评 R13：结果不确定、从没真正发出去过的任务先问网关', '复评 R13：问网关的路上用户改配了另一个网关'),
    ),
    Mutation(
        "r13-source-peek-asks-publications",
        "复评 R13：只看哈希的入口也去问发送账：问发送账就是冻结意图，“不冻结”的承诺落了空",
        (Edit(LIBRARY_SOURCE, "        return builder.build(entry, revision)?.textSha256\n", "        return builder.build(entry, revision, publications.publicationFor(key))?.textSha256\n"),),
        sync_tests("SubmissionBuilderTest", "DispatchEngineTest"), SYNC_RESULTS,
        ('复评 R13：只看文字的哈希不问发送账', '复评 R13：紧挨着请求的最后一道放行没通过', '复评 R13：结果不确定、从没真正发出去过的任务先问网关', '复评 R13：问网关的路上用户改配了另一个网关'),
    ),
    Mutation(
        "r13-engine-missing-at-seal-retried",
        "复评 R13：看过哈希之后、构造提交之前本机把文字删了：构造提交读不到就是“本机没有可发的内容”，停下等用户；当成可重试，会无限重试一条永远发不出的文字",
        (Edit(ENGINE, "        val submission = built.submission ?: return blocked(gateway, key, BlockedReason.LOCAL_DATA_MISSING)\n", "        val submission = built.submission ?: return retryLater(gateway, key)\n"),),
        sync_tests("SyncEngineTest"), SYNC_RESULTS,
        ("复评 R13：看过哈希之后、构造提交之前本机把这条文字删了",),
    ),
    # ---- 再评审 R1：撤销截止点——替换凭证看不到撤销之前入库的文字（网关侧，pytest 集成测试） ----
    Mutation(
        "r1-gw-claim-ignores-cutoff",
        "再评审 R1（网关）：领取这一层也要过撤销截止点（纵深防御）：不过，升级前就留在旧库里、入库时间不晚于截止点的排队 Delivery 会被替换凭证领走"
        "（正常路径里撤销已把排着的取消了，只有旧库的数据走得到这里，所以抓住它的是升级用例；真跑发现我原先把预期写成了全路径用例）",
        (Edit(GATEWAY_DELIVERIES, '      AND {AFTER_REVOCATION_CUTOFF}\n    ORDER BY d.created_at, d.delivery_id\n', '    ORDER BY d.created_at, d.delivery_id\n'),),
        gateway_tests(REVOCATION_CUTOFF_TESTS), GATEWAY_RESULTS,
        ('test_a_suspension_that_ended_before_the_upgrade_gets_its_cutoff_from_the_credential_history',),
    ),
    Mutation(
        "r1-gw-lease-paths-ignore-cutoff",
        "再评审 R1（网关）：确认、续租、拒收也要过截止点：不过，旧租约在替换凭证面前仍然有效",
        (Edit(GATEWAY_DELIVERIES, '          AND {AFTER_REVOCATION_CUTOFF}\n        """,\n        (destination.tenant_id, destination.destination_id, delivery_id),\n', '        """,\n        (destination.tenant_id, destination.destination_id, delivery_id),\n'),),
        gateway_tests(REVOCATION_CUTOFF_TESTS), GATEWAY_RESULTS,
        ('test_the_replacement_credential_reads_nothing_from_before_the_revocation_on_any_path',),
    ),
    Mutation(
        "r1-gw-list-search-ignore-cutoff",
        "再评审 R1（网关）：列表与搜索要过截止点：不过，搜得到撤销之前的全文片段就是泄露",
        (Edit(GATEWAY_QUERIES, '                  AND {AFTER_REVOCATION_CUTOFF}\n            )\n', '            )\n'),),
        gateway_tests(REVOCATION_CUTOFF_TESTS), GATEWAY_RESULTS,
        ('test_the_replacement_credential_reads_nothing_from_before_the_revocation_on_any_path',),
    ),
    Mutation(
        "r1-gw-get-ignores-cutoff",
        "再评审 R1（网关）：按编号读取也要过截止点：不过，知道编号的 Agent 能直接读到撤销之前的文字",
        (Edit(GATEWAY_QUERIES, '              AND {AFTER_REVOCATION_CUTOFF}\n              {rev_filter}\n', '              {rev_filter}\n'),),
        gateway_tests(REVOCATION_CUTOFF_TESTS), GATEWAY_RESULTS,
        ('test_the_replacement_credential_reads_nothing_from_before_the_revocation_on_any_path',),
    ),
    Mutation(
        "r1-gw-cutoff-never-recorded",
        "再评审 R1（网关）：暂停结束（签发替换凭证）的那一刻要记下截止点：不记，上面所有的过滤都形同虚设",
        (Edit(GATEWAY_STORE, '            if destination_id in _suspended_destination_ids(conn, auth.tenant_id, [destination_id]):\n', '            if False and destination_id in _suspended_destination_ids(conn, auth.tenant_id, [destination_id]):\n'),),
        gateway_tests(REVOCATION_CUTOFF_TESTS), GATEWAY_RESULTS,
        ('test_the_replacement_credential_reads_nothing_from_before_the_revocation_on_any_path', 'test_each_new_suspension_moves_the_cutoff_forward'),
    ),
    Mutation(
        "r1-gw-cutoff-ignores-clock-ahead",
        "再评审 R1（网关）：截止点要与已有文字的最晚入库时间取较大者：只取当前时间，网关时钟曾经跑快过的话，旧文字会落到截止点之后而重新可见",
        (Edit(GATEWAY_STORE, "COALESCE((SELECT MAX(stored_at) FROM revisions WHERE tenant_id = ?), '')", "COALESCE((SELECT MAX(stored_at) FROM revisions WHERE tenant_id = ? AND 0), '')"),),
        gateway_tests(REVOCATION_CUTOFF_TESTS), GATEWAY_RESULTS,
        ('test_a_text_stored_while_the_gateway_clock_ran_ahead_is_still_older_than_the_cutoff',),
    ),
    Mutation(
        "r1-gw-rotation-sets-cutoff",
        "再评审 R1（网关）：只有“暂停之后恢复”才设截止点：轮换（总留着一条有效凭证）也设，轮换时还排着的文字就被藏起来",
        (Edit(GATEWAY_STORE, '            if destination_id in _suspended_destination_ids(conn, auth.tenant_id, [destination_id]):\n', '            if True or destination_id in _suspended_destination_ids(conn, auth.tenant_id, [destination_id]):\n'),),
        gateway_tests(REVOCATION_CUTOFF_TESTS), GATEWAY_RESULTS,
        ('test_rotating_without_a_suspension_hides_nothing',),
    ),
    Mutation(
        "r1-gw-migration-no-backfill",
        "再评审 R1（网关）：升级迁移要按凭证历史给已经发生过的暂停补上截止点：不补，升级前就暂停过的 Destination 的旧文字对替换凭证照样可见",
        (Edit(GATEWAY_MIGRATION_0005, 'UPDATE destinations\nSET revocation_cutoff = MAX(\n', 'UPDATE destinations\nSET revocation_cutoff = NULL + MAX(\n'),),
        gateway_tests(REVOCATION_CUTOFF_TESTS), GATEWAY_RESULTS,
        ('test_a_suspension_that_ended_before_the_upgrade_gets_its_cutoff_from_the_credential_history',),
    ),
    Mutation(
        "r1-gw-migration-backfills-rotations",
        "再评审 R1（网关）：迁移只给“旧凭证全部先于新凭证被撤销”的暂停补截止点：连轮换也补，升级后轮换过的 Destination 排着的文字就被藏起来",
        (Edit(GATEWAY_MIGRATION_0005, '                AND (p.revoked_at IS NULL OR p.revoked_at >= c.issued_at)\n', '                AND (p.revoked_at IS NULL AND 0)\n'),),
        gateway_tests(REVOCATION_CUTOFF_TESTS), GATEWAY_RESULTS,
        ('test_a_suspension_that_ended_before_the_upgrade_gets_its_cutoff_from_the_credential_history',),
    ),
    # ---- 第三轮 S1：升级补截止点不信时间先后（网关迁移 0005，pytest 集成测试） ----
    Mutation(
        "s1-gw-migration-ignores-revision-times",
        "第三轮 S1（网关）：补出的截止点要不低于库里已有文字的最晚入库时间：只看凭证的时间，网关时钟跑快时入库的文字（时间戳比替换凭证还晚）就落到截止点之后，替换凭证读得到撤销之前的文字",
        (Edit(GATEWAY_MIGRATION_0005, "    COALESCE((SELECT MAX(r.stored_at) FROM revisions AS r WHERE r.tenant_id = destinations.tenant_id), '')\n", "    ''\n"),),
        gateway_tests(REVOCATION_CUTOFF_TESTS), GATEWAY_RESULTS,
        ('test_an_upgrade_hides_a_text_stored_while_the_clock_ran_ahead_of_the_replacement_credential', 'test_a_suspension_that_ended_before_the_upgrade_gets_its_cutoff_from_the_credential_history'),
    ),
    Mutation(
        "s1-gw-migration-trusts-time-order",
        "第三轮 S1（网关）：认出“结束过的暂停”不能只靠时间先后：时钟调回去之后，替换凭证的签发时间比旧凭证的签发、撤销时间还早，按时间先后认不出这是暂停，不补截止点，替换凭证什么都读得到（还要看插入顺序与“轮换在签发的同一瞬间撤销旧凭证”）",
        (Edit(GATEWAY_MIGRATION_0005, "              WHERE p.tenant_id = c.tenant_id AND p.destination_id = c.destination_id AND p.rowid < c.rowid\n", "              WHERE p.tenant_id = c.tenant_id AND p.destination_id = c.destination_id AND p.rowid < c.rowid AND 0\n"),),
        gateway_tests(REVOCATION_CUTOFF_TESTS, BACKFILL_TESTS), GATEWAY_RESULTS,
        (f'{BACKFILL_CASE}[suspension-after-rollback]',),
    ),
    Mutation(
        "s1-gw-migration-treats-every-later-credential-as-suspension",
        "第三轮 S1（网关）：不看时间先后的识别不能把轮换也当成暂停：不排除“旧凭证在新凭证签发的同一瞬间被撤销”，每个签发过第二条凭证的 Destination 都被补上截止点，排着的文字就被悄悄藏起来",
        (Edit(GATEWAY_MIGRATION_0005, "                AND p.revoked_at = c.issued_at\n", "                AND p.revoked_at = c.issued_at AND 0\n"),),
        gateway_tests(REVOCATION_CUTOFF_TESTS), GATEWAY_RESULTS,
        ('test_an_upgrade_does_not_mistake_a_rotation_after_a_clock_rollback_for_a_suspension', 'test_a_suspension_that_ended_before_the_upgrade_gets_its_cutoff_from_the_credential_history'),
    ),
    # ---- 再评审 R2：延迟的追加与首次入库不为截止点之前的文字建 Delivery ----
    Mutation(
        "r2-gw-addition-ignores-cutoff",
        "再评审 R2（网关）：延迟到达的追加要看这条文字的入库时间：不看，替换凭证之后才到的追加照样为撤销之前入库的文字建一条永远领不到的 PENDING",
        (Edit(GATEWAY_STORE, '            suspended |= _past_cutoff_destination_ids(conn, tenant_id, missing, stored["stored_at"])\n', ""),),
        gateway_tests(REVOCATION_CUTOFF_TESTS), GATEWAY_RESULTS,
        ('test_a_delayed_addition_after_the_replacement_credential_creates_nothing_for_an_older_revision',),
    ),
    Mutation(
        "r2-gw-first-store-ignores-cutoff",
        "再评审 R2（网关）：首次入库的 publication 也要过截止点：不过，时钟回拨后入库的文字（入库时间不晚于截止点）照样建一条永远领不到的 PENDING",
        (Edit(GATEWAY_STORE, '            suspended |= _past_cutoff_destination_ids(conn, tenant_id, destination_ids, now_iso)\n', ""),),
        gateway_tests(REVOCATION_CUTOFF_TESTS), GATEWAY_RESULTS,
        ('test_a_first_store_that_is_not_later_than_the_cutoff_creates_no_delivery_either',),
    ),
    Mutation(
        "r2-gw-cutoff-ignores-revision-age",
        "再评审 R2（网关）：只跳过入库时间不晚于截止点的文字：有截止点就一律跳过，恢复之后入库的新文字也建不出 Delivery",
        (Edit(GATEWAY_STORE, '          AND revocation_cutoff IS NOT NULL AND revocation_cutoff >= ?\n', '          AND revocation_cutoff IS NOT NULL AND ? IS NOT NULL\n'),),
        gateway_tests(REVOCATION_CUTOFF_TESTS), GATEWAY_RESULTS,
        ('test_an_addition_for_a_text_stored_after_the_replacement_credential_works', 'test_text_stored_after_the_replacement_credential_is_visible_on_every_path'),
    ),
    # ---- 评审 R7：手机只和说合同 1.2 的网关说话（联系网关传任何东西之前先问版本） ----
    Mutation(
        "r7-verdict-accepts-older-versions",
        "评审 R7：通告里没有 1.2、只有更旧的版本，就是“网关太旧”：把更旧的也算兼容，手机照发，网关回 422，每条文字都被当成“请求不合规”永久拒绝",
        (Edit(GATEWAY_CLIENT, "                Wire.SCHEMA_VERSION in advertised -> ContractVerdict.COMPATIBLE\n", "                Wire.SCHEMA_VERSION in advertised || advertised.any { it in Wire.OLDER_VERSIONS } -> ContractVerdict.COMPATIBLE\n"),),
        sync_tests("ContractVerdictTest"), SYNC_RESULTS,
        ("通告的全是更旧的版本才是太旧",),
    ),
    Mutation(
        "r7-verdict-unknown-is-too-old",
        "评审 R7：看不懂的通告（只有更新的版本、读不懂的版本号）不能冤枉网关“太旧”：把一切不兼容的都当太旧，用户会白白去升级一个其实更新的网关",
        (Edit(GATEWAY_CLIENT, "                advertised.isNotEmpty() && advertised.all { it in Wire.OLDER_VERSIONS } -> ContractVerdict.TOO_OLD\n", "                advertised.isNotEmpty() -> ContractVerdict.TOO_OLD\n"),),
        sync_tests("ContractVerdictTest"), SYNC_RESULTS,
        ("无法判断的不放行也不冤枉",),
    ),
    Mutation(
        "r7-guard-too-old-passes",
        "评审 R7：太旧的网关一个数据请求都不能发：把“太旧”当兼容放行，旧网关会把每条文字都当成不合规永久拒绝",
        (Edit(CONTRACT_GUARD, "                ContractVerdict.TOO_OLD -> ContractAnswer.TOO_OLD\n", "                ContractVerdict.TOO_OLD -> ContractAnswer.COMPATIBLE\n"),),
        sync_tests("SyncEngineContractTest", "DestinationContractTest"), SYNC_RESULTS,
        ("网关太旧：一个数据请求都不发", "网关太旧：列表、创建、撤销、签发都不发请求"),
    ),
    Mutation(
        "r7-guard-unconfirmed-reads-as-too-old",
        "评审 R7：没问成（网关出错、通告无法判断）不是“太旧”：当成太旧会给配对记录打上网关太旧的标记，用户被指向错误的处置",
        (Edit(CONTRACT_GUARD, "                ContractVerdict.UNCONFIRMED -> ContractAnswer.UNCONFIRMED\n", "                ContractVerdict.UNCONFIRMED -> ContractAnswer.TOO_OLD\n"),),
        sync_tests("SyncEngineContractTest", "DestinationContractTest"), SYNC_RESULTS,
        ("问不到版本（网关出错", "问不到版本不是太旧"),
    ),
    Mutation(
        "r7-guard-trusts-any-earlier-pairing",
        "评审 R7：兼容的结论只对确认过的那份配对有效：只要确认过一次就一直信，重新配对（也许配到了另一台旧网关）之后不再问",
        (Edit(CONTRACT_GUARD, "            if (verified?.isSamePairing(gateway) == true) return ContractAnswer.COMPATIBLE\n", "            if (verified != null) return ContractAnswer.COMPATIBLE\n"),),
        sync_tests("SyncEngineContractTest", "DestinationContractTest"), SYNC_RESULTS,
        ("重新配对之后：旧配对确认过的不算数",),
    ),
    Mutation(
        "r7-guard-never-remembers",
        "评审 R7：兼容的结论在同一份配对、同一个进程里只问一次：不记，每个管理操作、每次同步都多一次询问",
        (Edit(CONTRACT_GUARD, "        if (answer == ContractAnswer.COMPATIBLE) synchronized(lock) { if (generation == asked) verified = gateway }\n", ""),),
        sync_tests("SyncEngineContractTest", "DestinationContractTest"), SYNC_RESULTS,
        ("兼容的网关：同一份配对在同一个进程里只问一次版本", "兼容的网关：管理操作之间只问一次版本"),
    ),
    Mutation(
        "r7-guard-remembers-too-old",
        "评审 R7：只有“兼容”才记成这份配对已确认：把“太旧”（以及被拒、公钥不符）也记成已确认，之后同一份配对的请求一律当兼容放行——旧网关挨过第一次拦，往后什么数据请求都收得到"
        "（真跑发现我原先把这条的后果想成“更新后恢复不了”，那是下一条的事；这一条红的是“旧网关一个请求都不发”的那几个）",
        (Edit(CONTRACT_GUARD, "        if (answer == ContractAnswer.COMPATIBLE) synchronized(lock) { if (generation == asked) verified = gateway }\n", "        if (answer != ContractAnswer.UNCONFIRMED) synchronized(lock) { if (generation == asked) verified = gateway }\n"),),
        sync_tests("SyncEngineContractTest", "DestinationContractTest"), SYNC_RESULTS,
        ("网关还是旧的：再来多少轮也不发", "网关太旧：列表、创建、撤销、签发都不发请求", "生成凭证：“网关太旧”的标记可能已经过时"),
    ),
    Mutation(
        "r7-guard-caches-too-old-verdict",
        "评审 R7：“太旧”不记：把它也记在内存里（单独的“太旧”记忆），用户更新了网关之后手机还拿着旧结论，要重启才恢复；评审要求的是更新后自己恢复、不用重新配对",
        (
            Edit(CONTRACT_GUARD, "    private var verified: PairedGateway? = null\n", "    private var verified: PairedGateway? = null\n    private var tooOld: PairedGateway? = null\n"),
            Edit(CONTRACT_GUARD, "            if (verified?.isSamePairing(gateway) == true) return ContractAnswer.COMPATIBLE\n", "            if (verified?.isSamePairing(gateway) == true) return ContractAnswer.COMPATIBLE\n            if (tooOld?.isSamePairing(gateway) == true) return ContractAnswer.TOO_OLD\n"),
            Edit(CONTRACT_GUARD, "        if (answer == ContractAnswer.COMPATIBLE) synchronized(lock) { if (generation == asked) verified = gateway }\n", "        if (answer == ContractAnswer.COMPATIBLE) synchronized(lock) { if (generation == asked) verified = gateway }\n        if (answer == ContractAnswer.TOO_OLD) synchronized(lock) { tooOld = gateway }\n"),
        ),
        sync_tests("SyncEngineContractTest", "DestinationContractTest"), SYNC_RESULTS,
        ("更新网关之后自动恢复：不需要重新配对", "更新网关之后管理页自动恢复"),
    ),
    Mutation(
        "r7-engine-additions-skip-version-check",
        "评审 R7：追加 Delivery 也是联系网关，也要先问版本：不问，旧网关没有这个端点，追加会被当成永久拒绝",
        (Edit(ENGINE, "        if (!additionsMayProceed(gateway, permit.gated { contract.check(gateway, address, client) } ?: return)) return\n", "        if (!additionsMayProceed(gateway, ContractAnswer.COMPATIBLE)) return\n"),),
        sync_tests("DestinationContractTest"), SYNC_RESULTS,
        ("追加 Delivery 之前也问版本",),
    ),
    Mutation(
        "r7-service-too-old-lets-requests-through",
        "评审 R7：管理页遇到太旧的网关一个管理请求都不发：照常连上去，列表、创建、撤销、签发都会撞上旧网关的 422",
        (Edit(SERVICE, "            ContractAnswer.TOO_OLD -> {\n                pairing.markGatewayTooOld(gateway)\n                Connect.Blocked(ServiceFailure.NeedsRepair(NeedsUser.GATEWAY_TOO_OLD))\n            }\n", "            ContractAnswer.TOO_OLD -> Connect.Ready(Session(gateway, address, client))\n"),),
        sync_tests("DestinationContractTest"), SYNC_RESULTS,
        ("网关太旧：列表、创建、撤销、签发都不发请求",),
    ),
    Mutation(
        "policy-strict-local-ignored",
        "评审 B1：严格仅本地是统一门禁，压过授权：只看授权就等于开着严格仅本地也照发",
        (Edit(POLICY, "    val textMayEnterGateway: Boolean get() = gatewayTextGranted && !strictLocal", "    val textMayEnterGateway: Boolean get() = gatewayTextGranted"),),
        sync_tests("DataFlowGateTest"), SYNC_RESULTS,
        ("授权了文字进网关、但开着严格仅本地", "稍后重试的任务到点了也受门禁管"),
    ),
    Mutation(
        "policy-unset-means-granted",
        "评审 B1：从没设置过时必须是“没授权”（默认关）：默认成已授权，等于配对就自动同意文字进网关",
        (Edit(
            POLICY_SQL,
            "        grantedTo = value(TEXT_GRANT)?.let(::grantee),",
            '        grantedTo = value(TEXT_GRANT)?.let(::grantee) ?: GatewayIdentity("00000000-0000-4000-8000-000000000000", "0".repeat(64)),',
        ),),
        platform_tests("DataFlowPolicySqlTest"), PLATFORM_RESULTS,
        ("从没设置过：没有授权",),
    ),
    Mutation(
        "policy-unreadable-opens",
        "评审 B1：设置读不出来时必须按最保守的算（不授权、严格仅本地）：读不出来当成“没设置过”，严格仅本地就被悄悄松开了",
        (Edit(POLICY_SQL, "    } catch (_: Exception) {\n        StoredDataFlow.Closed\n    }", "    } catch (_: Exception) {\n        StoredDataFlow.Unset\n    }"),),
        platform_tests("DataFlowPolicySqlTest"), PLATFORM_RESULTS,
        ("读不出来（表不存在、连接坏了）",),
    ),
    Mutation(
        "r3-grant-is-global",
        "复评 R3：授权只对给出它的那个网关算数：只看“有人授权过”，等于换了网关之后新网关照样收到文字",
        (Edit(POLICY, "gatewayTextGranted = gateway != null && grantedTo == gateway", "gatewayTextGranted = grantedTo != null"),),
        sync_tests("GatewayBoundConsentTest"), SYNC_RESULTS,
        ("授权给了网关 A，重新配对到网关 B 之后：B 一个字节也收不到，文字留在手机上",),
    ),
    Mutation(
        "r3-grant-ignores-the-key-pin",
        "复评 R3：网关的身份是“编号 + 公钥指纹”：只认编号，同编号换了公钥的另一台网关就会继承原来的同意",
        (Edit(ANDROID + "sync/src/main/kotlin/app/auri/sync/pairing/PairedGateway.kt", "GatewayIdentity(gatewayId, pin.hex, pairingEpoch)", "GatewayIdentity(gatewayId, \"\", pairingEpoch)"),),
        sync_tests("GatewayBoundConsentTest"), SYNC_RESULTS,
        ("同一个网关编号、公钥换了：是另一台网关，原来的同意对它不算数",),
    ),
    Mutation(
        "r3-pairing-skips-the-grant-clearing",
        "复评 R3：配对成功、换配对记录之前要先把别的网关的授权清掉（主动清理）：不清的话两步之间新网关挂着旧授权",
        (Edit(PAIRING_SERVICE, "                    beforeSaving(stamped.identity)\n", "                    if (false) beforeSaving(stamped.identity)\n"),),
        sync_tests("PairingServiceTest"), SYNC_RESULTS,
        ("配对成功：先让授权只留给新网关，再换配对记录",),
    ),
    Mutation(
        "r3-sql-clearing-keeps-the-wrong-grant",
        "复评 R3：清理要作废“给的不是新网关”的授权：条件写反就是留下了不该留的、作废了该留的",
        (Edit(POLICY_SQL, "        if (granted != null && granted != keep) put(TEXT_GRANT, revoked(now))", "        if (granted != null && granted == keep) put(TEXT_GRANT, revoked(now))"),),
        platform_tests("DataFlowPolicySqlTest"), PLATFORM_RESULTS,
        ("换配对的清理：授权不是给新网关的就作废并留下记录",),
    ),
    Mutation(
        "r3-runtime-forgets-the-clearing-hook",
        "复评 R3：装配里忘了把“换配对前清授权”接到配对服务上：单测各自全绿，设备上换网关后旧授权还在（读的时候的绑定是最后一道防线，不是清理的替代）",
        (Edit(SYNC_RUNTIME, "beforeSaving = { policy.forgetGrantExcept(it) }, ", ""),),
        app_tests("SyncStatusWiringTest"), APP_RESULTS,
        ("复评 R3：文字授权在装配里绑在配对着的网关上",),
    ),
    Mutation(
        "r3-hint-forgets-the-binding",
        "复评 R3：开关旁的说明不讲“同意只对现在配对的这台网关有效”：用户不知道换网关后要重新打开",
        (Edit(APP_UI + "SyncScreen.kt", "同意只对现在配对的这台网关有效，改配别的网关后要重新打开。", ""),),
        app_tests("SyncScreenTextTest"), APP_RESULTS,
        ("复评 R3：开关旁的说明讲清同意只对现在配对的这台网关有效",),
    ),
    Mutation(
        "policy-resume-ignores-gate",
        "评审 B1：没放行时不该要求系统把应用叫醒去发（醒来也发不出去，只会白白打扰系统）",
        (Edit(RESUME_PLANNER, "    policy.textMayEnterGateway && pairing is PairingState.Paired", "    pairing is PairingState.Paired"),),
        sync_tests("DataFlowGateTest", "ResumePlannerTest"), SYNC_RESULTS,
        ("needsResume：授权并且没开严格仅本地才需要叫醒", "被系统叫醒的补发也过同一道门"),
    ),
    Mutation(
        "policy-status-claims-up-to-date",
        "评审 B1：没放行时同步状态不能说“已同步/正在发送”：必须是“文字被留在本机”",
        (Edit(SYNC_STATUS, "                !dataFlow.textMayEnterGateway -> SyncHeadline.HELD_BY_POLICY", "                false -> SyncHeadline.HELD_BY_POLICY"),),
        sync_tests("DataFlowGateTest", "SyncStatusTest"), SYNC_RESULTS,
        ("同步状态：配了对但没放行时",),
    ),
    Mutation(
        "ui-send-page-hides-the-hold",
        "评审 B1：文字没放行时待发送页必须如实说“点确认只是记下决定、现在不会发出”：把提示藏起来就是让用户以为发出去了",
        (Edit(DESCRIPTIONS, "    state.textMayEnterGateway -> null", "    true -> null"),),
        app_tests("DestinationTextTest"), APP_RESULTS,
        ("没放行文字时待发送页顶上的提示",),
    ),
    Mutation(
        "ui-send-page-ignores-policy-state",
        "评审 B1：待发送页的状态必须带着真实的放行状态（而不是写死放行）",
        (Edit(SEND_REVIEW_VM, "            dataFlow = dataFlow,\n", "            dataFlow = DataFlowState(gatewayTextGranted = true, strictLocal = false),\n"),),
        app_tests("SendReviewViewModelTest"), APP_RESULTS,
        ("没授权文字进网关时：页面状态如实标着没放行", "还没读到放行状态之前页面按没放行算"),
    ),
    # ---- 评审 B3：撤销凭证 = 暂停（网关的那一半由 tests/integration/test_credential_suspension.py 守着；这里是手机的那一半）----
    Mutation(
        "b3-client-ignores-credential-active",
        "评审 B3：手机必须读网关给的 credential_active：不读就永远不知道目的地被暂停，还会继续替它规划、等用户确认",
        (CLIENT_IGNORES_CREDENTIAL_ACTIVE,),
        sync_tests("DestinationClientTest"), SYNC_RESULTS,
        ("凭证被撤销的 Destination（合同样本）读成暂停",),
    ),
    Mutation(
        "real-gw-b3-client-ignores-credential-active",
        "评审 B3（真网关）：真网关暂停了目的地，手机刷新之后必须知道（不读 credential_active 就不知道）",
        (CLIENT_IGNORES_CREDENTIAL_ACTIVE,),
        REAL, REAL_RESULTS,
        ("凭证被撤销：排着的投递取消，新文字不再给它建投递",),
    ),
    Mutation(
        "b3-client-missing-field-means-suspended",
        "评审 B3：旧网关没有 credential_active 时必须按“签发过就有效”算：按“无效”算，每个旧网关上的目的地都会被误判成暂停",
        (Edit(GATEWAY_CLIENT, '            credentialActive = reply.booleanOrNull("credential_active") ?: issued,', '            credentialActive = reply.booleanOrNull("credential_active") ?: false,'),),
        sync_tests("DestinationClientTest"), SYNC_RESULTS,
        ("旧网关没有 credential_active 字段时按“签发过就有效”算",),
    ),
    Mutation(
        "b3-sql-suspension-not-refused",
        "评审 B3：得知目的地暂停时，还没建 Delivery 的目标必须记成没送达（恢复之后也不补发）：不记，恢复后新凭证就会领到暂停前攒的文字",
        (Edit(
            DISPATCH_SQL,
            "        destinations.filter { it.suspended }.forEach { suspended ->\n"
            "            sql.update(\n"
            "                \"UPDATE dispatch_targets SET delivery = ?, refusal = ? WHERE destination_id = ? AND delivery = ? AND decision <> ?\",\n"
            "                GatewayDelivery.REFUSED.name,\n"
            "                Refusal.SUSPENDED.name,\n"
            "                suspended.destinationId,\n"
            "                GatewayDelivery.NOT_YET.name,\n"
            "                TargetDecision.SKIPPED.name,\n"
            "            )\n"
            "        }\n",
            "",
        ),),
        platform_tests("SqliteDispatchStoreContractTest"), PLATFORM_RESULTS,
        ("learning_that_a_destination_is_suspended_refuses_what_the_gateway_does_not_have_yet",),
    ),
    Mutation(
        "b3-sql-eligible-ignores-suspension",
        "评审 B3：暂停的目的地不能算“会收到新文字”：算的话，界面还会让用户确认发给它",
        (Edit(DISPATCH_SQL, "                eligible = destination != null && destination.receivesNewText && destination.allows(intent),", "                eligible = destination != null && destination.active && destination.allows(intent),"),),
        platform_tests("SqliteDispatchStoreContractTest"), PLATFORM_RESULTS,
        ("learning_that_a_destination_is_suspended_refuses_what_the_gateway_does_not_have_yet",),
    ),
    Mutation(
        "b3-sql-plan-ignores-suspension",
        "评审 B3：暂停期间新录的文字不能为暂停的目的地规划目标（网关也不会建 Delivery）",
        (Edit(DISPATCH_SQL, "            destinations().filter { it.receivesNewText }.forEach { destination ->", "            destinations().filter { it.active }.forEach { destination ->"),),
        platform_tests("SqliteDispatchStoreContractTest"), PLATFORM_RESULTS,
        ("a_suspended_destination_is_not_planned_for_new_texts",),
    ),
    Mutation(
        "b3-sql-decide-allows-refused",
        "评审 B3：目的地暂停过的预览目标，恢复之后也不能再确认（暂停期间的文字不补发）",
        (Edit(DISPATCH_SQL, "        if (!target.eligible || target.delivery == GatewayDelivery.REFUSED) return@transaction DecideResult.NotEligible", "        if (!target.eligible) return@transaction DecideResult.NotEligible"),),
        platform_tests("SqliteDispatchStoreContractTest"), PLATFORM_RESULTS,
        ("a_preview_target_of_a_suspended_destination_stops_asking_the_user_and_stays_closed_after_the_resume",),
    ),
    Mutation(
        "b3-awaiting-ignores-refusal",
        "评审 B3：被拒绝建 Delivery 的预览目标不能再算“等用户确认”（否则恢复之后待发送页又来问用户）",
        (Edit(DISPATCH_STORE, "    val awaitingUser: Boolean get() = decision == TargetDecision.AWAITING && eligible && delivery != GatewayDelivery.REFUSED", "    val awaitingUser: Boolean get() = decision == TargetDecision.AWAITING && eligible"),),
        platform_tests("SqliteDispatchStoreContractTest"), PLATFORM_RESULTS,
        ("a_preview_target_of_a_suspended_destination_stops_asking_the_user_and_stays_closed_after_the_resume",),
    ),
    Mutation(
        "b3-will-receive-ignores-refusal",
        "评审 B3：被拒绝建 Delivery 的目标不能再进提交的 publication（目的地恢复之后，网关就会为暂停期间的文字建 Delivery）",
        (Edit(DISPATCH_STORE, "    val willReceive: Boolean get() = eligible && delivery != GatewayDelivery.REFUSED && (decision == TargetDecision.AUTO || decision == TargetDecision.CONFIRMED)", "    val willReceive: Boolean get() = eligible && (decision == TargetDecision.AUTO || decision == TargetDecision.CONFIRMED)"),),
        platform_tests("SqliteDispatchStoreContractTest"), PLATFORM_RESULTS,
        ("learning_that_a_destination_is_suspended_refuses_what_the_gateway_does_not_have_yet",),
    ),
    Mutation(
        "b3-service-resume-keeps-cache-suspended",
        "评审 B3：签发新凭证之后本机缓存必须立刻知道目的地恢复了：不更新，页面一直说“已暂停”，新文字也不规划它",
        (Edit(SERVICE, "listOf(known.copy(credentialIssued = true, credentialActive = true)), authoritative = false", "listOf(known.copy(credentialIssued = true)), authoritative = false"),),
        sync_tests("DestinationServiceTest"), SYNC_RESULTS,
        ("重新签发凭证：目的地恢复，之后的文字照常规划",),
    ),
    Mutation(
        "b3-service-suspended-shown-as-unknown",
        "评审 B3：没送给暂停目的地的文字要说“没有送给它（已暂停）”，不能含糊成“状态未知”",
        (Edit(SERVICE, "            return if (target.refusal == Refusal.SUSPENDED) DeliveryView.Suspended else DeliveryView.Unknown(UnknownReason.DELIVERY_REFUSED)", "            return DeliveryView.Unknown(UnknownReason.DELIVERY_REFUSED)"),),
        sync_tests("DestinationServiceTest"), SYNC_RESULTS,
        ("管理员在网关上撤销了凭证：刷新之后本机知道它暂停了",),
    ),
    Mutation(
        "b3-ui-rotate-confirm-for-suspended",
        "评审 B3：暂停的目的地没有旧凭证可换：重新生成不该弹“确认轮换（旧凭证会失效）”",
        (Edit(DETAIL_VM, "        if (destination.credentialIssued && !destination.suspended) setUi(CredentialUi.ConfirmRotate) else issue()", "        if (destination.credentialIssued) setUi(CredentialUi.ConfirmRotate) else issue()"),),
        app_tests("DestinationDetailViewModelTest"), APP_RESULTS,
        ("目的地因凭证被撤销而暂停：再点生成凭证不弹",),
    ),
    Mutation(
        "b3-app-item-drops-suspended",
        "评审 B3：列表项必须带着“已暂停”：丢掉就没有任何地方告诉用户它暂停了",
        (Edit(DESTINATIONS_VM, "DestinationItem(destinationId, displayName, sendMode, allowedIntents, active, credentialIssued, suspended)", "DestinationItem(destinationId, displayName, sendMode, allowedIntents, active, credentialIssued)"),),
        app_tests("DestinationsViewModelTest"), APP_RESULTS,
        ("凭证被撤销（暂停）的目的地：列表项标着已暂停",),
    ),
    Mutation(
        "b3-text-suspension-promises-backfill",
        "评审 B3：说明里必须讲清暂停期间的文字不补发：删掉这句，用户会以为换了新凭证就能领到之前的文字",
        (Edit(DESCRIPTIONS, "重新生成凭证后恢复，但只对之后的文字生效——暂停期间的文字不会补发。", "重新生成凭证后恢复。"),),
        app_tests("DestinationTextTest"), APP_RESULTS,
        ("凭证被撤销的暂停（评审 B3）",),
    ),
    # ---- 评审 B4：本机核验只认强生物识别，锁屏 PIN 不行 ----
    Mutation(
        "b4-device-credential-allowed",
        "评审 B4：核验只认强生物识别：把设备凭据（PIN/图案/密码）加回来，只设了 PIN 的手机拿到手就能生成凭证",
        (Edit(BIOMETRIC_GATE, "        const val REQUIRED = BiometricManager.Authenticators.BIOMETRIC_STRONG\n", "        const val REQUIRED = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL\n"),),
        platform_tests("BiometricGateTest"), PLATFORM_RESULTS,
        ("只设了锁屏 PIN、没有登记生物识别", "向系统要的核验方式是纯强生物识别"),
    ),
    Mutation(
        "b4-gate-skips-availability-check",
        "评审 B4：弹提示之前必须先问系统“这种核验现在能不能做”：不问，没登记生物识别的手机也会弹提示",
        (Edit(BIOMETRIC_GATE, "        BiometricResults.availability(framework.canAuthenticate(REQUIRED))?.let { return it.also(::record) }\n", ""),),
        platform_tests("BiometricGateTest"), PLATFORM_RESULTS,
        ("只设了锁屏 PIN、没有登记生物识别", "没有硬件与暂时不可用也是不可用", "每一次都现场问系统"),
    ),
    Mutation(
        "b4-text-does-not-say-pin-is-not-enough",
        "评审 B4：没登记生物识别时必须明说锁屏密码不能代替：不说，用户会以为设了密码就行，一直卡在这里",
        (Edit(DESCRIPTIONS, "锁屏密码不能代替：知道密码的人不一定是你。", ""),),
        app_tests("DestinationTextTest"), APP_RESULTS,
        ("没登记生物识别时（评审 B4）",),
    ),
    # ---- 评审 B5：复制出去的凭证到点从剪贴板清掉，只清自己放的那一份 ----
    Mutation(
        "b5-timer-never-clears",
        "评审 B5：复制出去的凭证必须有期限：定时器到点不清，凭证就无限期留在剪贴板里（任何拿到焦点的应用、输入法历史都读得到）",
        (Edit(CLIPBOARD_GUARD, "            delay(TIMEOUT_MS)\n            expire()\n", "            delay(TIMEOUT_MS)\n"),),
        app_tests("SecretClipboardGuardTest"), APP_RESULTS,
        ("复制之后 60 秒，剪贴板里还是我们放的那一份就清空", "再复制一次就重新计时", "日志只有事件名，没有凭证"),
    ),
    Mutation(
        "b5-clears-someone-elses-clip",
        "评审 B5：读得到时必须认归属：到点把别的应用后来复制的东西也清掉，用户刚复制的会议室门牌就没了",
        (Edit(CLIPBOARD_GUARD, '            ClipboardPeek.Other -> log.event("clipboard.kept-other")', "            ClipboardPeek.Other -> clear()"),),
        app_tests("SecretClipboardGuardTest"), APP_RESULTS,
        ("别的应用后来复制了别的东西（读得到）",),
    ),
    # 评审 B5 的 b5-unreadable-keeps-credential 已退役（复评 R6）：它保护的是“读不了也清”，R6 判定这是缺陷、整个反过来；
    # 对偶的保护在下面的 r6-timeout-clears-unreadable。
    Mutation(
        "b5-old-timer-clears-new-copy",
        "评审 B5：再复制一次要重新计时：旧定时器不作废，会在第二次复制后 10 秒就把它清掉，用户来不及粘贴",
        (Edit(CLIPBOARD_GUARD, "        port.put(secret, elapsedMs())\n        timer?.cancel()\n", "        port.put(secret, elapsedMs())\n"),),
        app_tests("SecretClipboardGuardTest"), APP_RESULTS,
        ("再复制一次就重新计时",),
    ),
    Mutation(
        "b5-foreground-never-sweeps",
        "评审 B5：定时器随被杀的进程丢了：回到前台时不补查，凭证就留在剪贴板里，没人再管",
        (Edit(CLIPBOARD_GUARD, "        if (age < 0 || age >= TIMEOUT_MS) {", "        if (false) {"),),
        app_tests("SecretClipboardGuardTest"), APP_RESULTS,
        ("回到前台时发现已经到期", "设备重启过"),
    ),
    Mutation(
        "b5-foreground-clears-before-expiry",
        "评审 B5：回到前台没到期就清：用户刚回来看一眼、还要去粘贴，凭证就没了，只能再轮换一次",
        (Edit(CLIPBOARD_GUARD, "        if (age < 0 || age >= TIMEOUT_MS) {", "        if (age < 0 || age >= 0) {"),),
        app_tests("SecretClipboardGuardTest"), APP_RESULTS,
        ("回到前台时发现已经到期",),
    ),
    Mutation(
        "b5-activity-skips-foreground-check",
        "评审 B5：窗口拿到焦点时不调守卫的补查：被杀的进程留下的凭证永远不会被清",
        (Edit(MAIN_ACTIVITY, "        if (hasFocus) runtime.secretClipboard.onForeground() else runtime.secretClipboard.onLeaving()\n", "        if (!hasFocus) runtime.secretClipboard.onLeaving()\n"),),
        app_tests("ClipboardWiringTest"), APP_RESULTS,
        ("窗口拿到焦点时调守卫",),
    ),
    Mutation(
        "b5-page-does-not-use-guard",
        "评审 B5：页面的复制不交给守卫：凭证放进了剪贴板（或根本没放）却没人计时清理",
        (Edit(PAGE, "                    clipboard.copy(credential.reveal())", "                    credential.reveal()"),),
        app_tests("ClipboardWiringTest"), APP_RESULTS,
        ("凭证复制只经过守卫",),
    ),
    Mutation(
        "b5-notice-states-wrong-seconds",
        "评审 B5：界面告诉用户的期限必须是守卫真正用的：写死一个数，用户按它的话去粘贴，实际早已被清掉",
        (Edit(DESCRIPTIONS, '    "复制之后，离开本应用或满 ${SecretClipboardGuard.TIMEOUT_MS / 1_000} 秒，', '    "复制之后，离开本应用或满 30 秒，'),),
        app_tests("DestinationTextTest"), APP_RESULTS,
        ("凭证面板如实说剪贴板里的凭证到点会被清掉",),
    ),
    # ---- 评审 B6：退到后台或离开页面就换一代，在途的凭证不显示、不留，只告诉用户已丢掉 ----
    Mutation(
        "b6-background-keeps-inflight",
        "评审 B6：退到后台时只抹掉正在显示的、不让在途的请求作废：回来时页面蹦出一个凭证",
        (Edit(DETAIL_VM, "        panel.update { if (it.ui is CredentialUi.Shown || it.ui == CredentialUi.Working) Panel(it.generation + 1) else it }", "        panel.update { if (it.ui is CredentialUi.Shown) Panel(it.generation + 1) else it }"),),
        app_tests("DestinationDetailViewModelTest"), APP_RESULTS,
        ("应用退到后台时凭证还在路上", "退到后台之后用户回来又点了一次"),
    ),
    Mutation(
        "b6-stale-issued-is-silent",
        "评审 B6：作废请求的凭证被丢掉了却不告诉用户：网关已经签发，用户既没看到凭证也不知道要轮换",
        (Edit(DETAIL_VM, "                outcome is CredentialOutcome.Issued && current.ui != CredentialUi.Working && current.ui !is CredentialUi.Shown -> current.copy(ui = CredentialUi.Lost)", "                outcome is CredentialOutcome.Issued && current.ui != CredentialUi.Working && current.ui !is CredentialUi.Shown -> current"),),
        app_tests("DestinationDetailViewModelTest"), APP_RESULTS,
        ("应用退到后台时凭证还在路上", "等核验或网关的时候离开了页面", "退到后台之后用户回来又点了一次"),
    ),
    Mutation(
        "b6-lost-overwrites-newer-request",
        "评审 B6：旧凭证到的时候新一代正在要凭证：不该用“已丢掉”盖掉它的“进行中”",
        (Edit(DETAIL_VM, " && current.ui != CredentialUi.Working && current.ui !is CredentialUi.Shown -> current.copy(ui = CredentialUi.Lost)", " -> current.copy(ui = CredentialUi.Lost)"),),
        app_tests("DestinationDetailViewModelTest"), APP_RESULTS,
        ("旧凭证到的时候新一代正在要凭证",),
    ),
    Mutation(
        "b6-panel-written-without-compare-and-swap",
        "评审 B6：面板改成直接赋值：比完代数再赋值之间页面离开了，凭证会盖在已抹掉的状态上（真线程之间才有的缝）",
        (Edit(DETAIL_VM, "        panel.update { it.copy(ui = ui) }", "        panel.value = panel.value.copy(ui = ui)"),),
        app_tests("CredentialSurfaceGuardTest"), APP_RESULTS,
        ("凭证面板的状态与“第几代”在同一个值里",),
    ),
    Mutation(
        "b6-text-lost-does-not-say-rotate",
        "评审 B6：“已丢掉”的说明必须叫用户轮换：不说，用户看到凭证没了却不知道网关那边已经签发了一个",
        (Edit(DESCRIPTIONS, "请再点“轮换凭证”生成一个新的，旧的那个会立刻失效。", ""),),
        app_tests("DestinationTextTest"), APP_RESULTS,
        ("凭证已丢掉的说明（评审 B6）",),
    ),
    # ---- 评审 H1：叫醒规划器的“欠不欠追加”要和任务表同一刻现算，重启后只凭磁盘上的账也算得出来 ----
    Mutation(
        "h1-planner-ignores-owed-additions",
        "评审 H1：规划器不看欠着的追加：用户确认预览之后（文字已入库）叫醒被取消，进程一被杀就没人再来做",
        (Edit(RESUME_PLANNER, "needsResume(pairingState, list, dataFlow, additionsOwed())", "needsResume(pairingState, list, dataFlow, false)"),),
        sync_tests("ResumeRecoveryTest", "ResumePlannerTest"), SYNC_RESULTS,
        ("预览目标在文字入库之前就被确认", "用户确认提交了、叫醒还没登记进程就被杀", "任务都已入库，但还欠网关追加的 Delivery"),
    ),
    Mutation(
        "h1-planner-owed-one-tick-stale",
        "评审 H1：“欠不欠追加”读到的是上一拍的值（独立一路流的老毛病）：任务入库的那一刻追加刚到期，叫醒却被取消",
        (
            Edit(RESUME_PLANNER, "        combine(pairing, jobs, policy, additionsChanged.onStart { emit(Unit) }) {", "        var owedBefore = false\n        combine(pairing, jobs, policy, additionsChanged.onStart { emit(Unit) }) {"),
            Edit(RESUME_PLANNER, "needsResume(pairingState, list, dataFlow, additionsOwed()) }", "needsResume(pairingState, list, dataFlow, owedBefore).also { owedBefore = additionsOwed() } }"),
        ),
        sync_tests("ResumeRecoveryTest"), SYNC_RESULTS,
        ("预览目标在文字入库之前就被确认",),
    ),
    Mutation(
        "h1-runtime-does-not-watch-the-ledger",
        "评审 H1：SyncRuntime 没把发送账的变化接给规划器：用户确认之后账上欠了追加，规划器没有重新评估",
        (Edit(SYNC_RUNTIME, "        additionsChanged = dispatch.changes(),\n", ""),),
        app_tests("ResumeWiringGuardTest"), APP_RESULTS,
        ("叫醒规划器的“欠不欠追加”读的是发送账",),
    ),
    Mutation(
        "h1-runtime-owed-always-false",
        "评审 H1：SyncRuntime 里“欠不欠追加”写死成不欠：规划器永远只看任务表，预览确认的追加没有叫醒保护",
        (Edit(SYNC_RUNTIME, "        additionsOwed = { dispatch.due().isNotEmpty() },", "        additionsOwed = { false },"),),
        app_tests("ResumeWiringGuardTest"), APP_RESULTS,
        ("叫醒规划器的“欠不欠追加”读的是发送账",),
    ),
    # ---- 评审 H2：换了配对的网关，按旧网关规划的目标在任何请求离开手机之前作废 ----
    Mutation(
        "h2-engine-does-not-align-before-submission",
        "评审 H2：构造提交之前不对账：旧网关的 Destination 编号进了新网关的请求，被 422 拒绝，文字永远卡在 BLOCKED",
        (Edit(ENGINE, "        if (!fenced(gateway) { additions.useGateway(gateway.identity) }) return stale(key)\n", ""),),
        sync_tests("GatewaySwitchTest"), SYNC_RESULTS,
        ("排着的文字不带旧网关的 Destination 编号去新网关",),
    ),
    Mutation(
        "h2-engine-additions-skip-alignment",
        "评审 H2：追加之前不对账：账上欠着的、给旧网关 Destination 的追加被发给了新网关",
        (Edit(ENGINE, "        if (gateway != null && !fenced(gateway) { additions.useGateway(gateway.identity) }) return // 读到配对之后又换了：旧配对的残余不碰新配对的账（复评 R5）\n", ""),),
        sync_tests("GatewaySwitchTest"), SYNC_RESULTS,
        ("旧网关的 Destination 编号不会被追加给新网关",),
    ),
    Mutation(
        "h2-switch-keeps-unsent-targets",
        "评审 H2：换网关只清了缓存、没撤回按旧网关规划的目标（原来的缺陷）：旧编号还留在账上，随时会被带去新网关",
        (Edit(DISPATCH_SQL, "        sql.update(\"DELETE FROM dispatch_targets WHERE delivery = ?\", GatewayDelivery.NOT_YET.name)\n        if (gateway == null)", "        if (gateway == null)"),),
        platform_tests("SqliteDispatchStoreContractTest"), PLATFORM_RESULTS,
        (
            "using_another_gateway_withdraws_every_target_planned_for_the_old_one_but_keeps_what_already_happened",
            "a_text_still_waiting_to_be_sent_when_the_gateway_changes_is_stored_on_the_new_gateway_without_targets",
            "forgetting_the_cache_also_withdraws_the_targets_that_were_planned_from_it",
            "hearing_from_another_gateway_withdraws_the_old_targets_just_like_using_it",
        ),
    ),
    Mutation(
        "h2-switch-erases-history",
        "评审 H2：换网关时连“旧网关上已经有 Delivery”的历史也一起删了：用户看不到文字曾经送给过谁",
        (Edit(DISPATCH_SQL, "        sql.update(\"DELETE FROM dispatch_targets WHERE delivery = ?\", GatewayDelivery.NOT_YET.name)\n        if (gateway == null)", "        sql.update(\"DELETE FROM dispatch_targets\")\n        if (gateway == null)"),),
        platform_tests("SqliteDispatchStoreContractTest"), PLATFORM_RESULTS,
        ("using_another_gateway_withdraws_every_target_planned_for_the_old_one_but_keeps_what_already_happened",),
    ),
    Mutation(
        "h2-use-gateway-switches-every-time",
        "评审 H2：同一个网关每次对账也清一遍：引擎每次发请求前都会调用，缓存与账被反复抹掉",
        (Edit(DISPATCH_SQL, "        if (meta(GATEWAY_ID) == gateway.ledgerKey) return@transaction false\n", "        if (false) return@transaction false\n"),),
        platform_tests("SqliteDispatchStoreContractTest"), PLATFORM_RESULTS,
        ("using_the_same_gateway_again_changes_nothing_and_does_not_move_the_change_signal",),
    ),
    Mutation(
        "h2-apply-destinations-keeps-old-targets",
        "评审 H2：用户先去发送页读到新网关的名单时，只清缓存、不撤回旧目标（原来的缺陷）",
        (Edit(DISPATCH_SQL, "        if (meta(GATEWAY_ID) != gateway.ledgerKey) switchGateway(gateway)\n        if (authoritative) {", "        if (meta(GATEWAY_ID) != gateway.ledgerKey) {\n            sql.update(\"DELETE FROM dispatch_destinations\")\n            setMeta(GATEWAY_ID, gateway.ledgerKey)\n        }\n        if (authoritative) {"),),
        platform_tests("SqliteDispatchStoreContractTest"), PLATFORM_RESULTS,
        ("hearing_from_another_gateway_withdraws_the_old_targets_just_like_using_it",),
    ),
    # ---- 评审 H3：较早发出的列表回答不能盖掉后面的撤销、创建、签发；撤销只进不退 ----
    Mutation(
        "h3-calls-not-serialised",
        "评审 H3：管理类的网关往返不再串行（每次一把新锁）：较早发出的列表请求的回答可以在后面的创建之后才写进缓存，把新建的当成网关没有而删掉",
        (Edit(SERVICE, "    ): ServiceResult<T> = exchange.withLock {", "    ): ServiceResult<T> = Mutex().withLock {"),),
        sync_tests("StaleListResponseTest"), SYNC_RESULTS,
        ("创建之后才到",),
    ),
    Mutation(
        "h3-issue-not-serialised",
        "评审 H3：签发凭证的网关往返不再与别的管理操作串行：较早的列表回答在签发之后才到，把缓存退回“还没有凭证”",
        (Edit(SERVICE, "            return exchange.withLock {", "            return Mutex().withLock {"),),
        sync_tests("StaleListResponseTest"), SYNC_RESULTS,
        ("签发凭证之后才到",),
    ),
    Mutation(
        "h3-store-lets-an-old-snapshot-revive",
        "评审 H3：发送账收到较早算好的名单时不保留已有的撤销：已撤销的 Destination 被改回有效，手机会为它规划目标",
        (Edit(DISPATCH_SQL, ".firstOrNull() ?: destination.revokedAt", ".firstOrNull().let { destination.revokedAt }"),),
        platform_tests("SqliteDispatchStoreContractTest"), PLATFORM_RESULTS,
        ("a_revoked_destination_is_never_restored_by_an_older_snapshot",),
    ),
    # ---- 评审 H4：读发送账失败不能杀掉进程（录音时尤其）；页面如实说读不到并自动重试 ----
    Mutation(
        "h4-send-review-not-contained",
        "评审 H4：待发送页的读没有包：发送账读失败的异常逃出视图模型的作用域（线上就是进程退出，包括正在录音的前台服务）",
        (Edit(SEND_REVIEW_VM, '        .containReadFailures("send.review", log) { last -> emit((last ?: SendReviewUiState()).copy(unreadable = true)) }\n', ""),),
        app_tests("ReadFailureContainmentTest"), APP_RESULTS,
        ("待发送页读不到发送账", "待发送页读到过又读不到了"),
    ),
    Mutation(
        "h4-send-review-pretends-all-is-well",
        "评审 H4：待发送页读失败之后不标“读不到”：页面一直空白或一直显示旧内容，装作没事",
        (Edit(SEND_REVIEW_VM, "{ last -> emit((last ?: SendReviewUiState()).copy(unreadable = true)) }", "{ last -> emit(last ?: SendReviewUiState()) }"),),
        app_tests("ReadFailureContainmentTest"), APP_RESULTS,
        ("待发送页读不到发送账", "待发送页读到过又读不到了"),
    ),
    Mutation(
        "h4-destinations-not-contained",
        "评审 H4：目的地页的读没有包：本机缓存读失败的异常逃出作用域",
        (Edit(DESTINATIONS_VM, '            .containReadFailures("destinations", log) { last -> emit((last ?: Cache()).copy(unreadable = true)) },\n', "            ,\n"),),
        app_tests("ReadFailureContainmentTest"), APP_RESULTS,
        ("目的地页读不到本机缓存",),
    ),
    Mutation(
        "h4-detail-state-not-contained",
        "评审 H4：详情页的状态流没有包：读失败的异常逃出作用域",
        (Edit(DETAIL_VM, '            .containReadFailures("destination.detail", log) { last -> emit((last ?: Cache()).copy(unreadable = true)) },\n', "            ,\n"),),
        app_tests("ReadFailureContainmentTest"), APP_RESULTS,
        ("详情页读不到本机缓存",),
    ),
    Mutation(
        "h4-detail-watch-not-contained",
        "评审 H4：详情页“缓存变了就重读状态”的那个收集器没有包：它是第二个订阅，照样会把读失败的异常带出作用域",
        (Edit(DETAIL_VM, 'controller.destinations.containReadFailures("destination.detail.watch", log).collect', "controller.destinations.collect"),),
        app_tests("ReadFailureContainmentTest"), APP_RESULTS,
        ("详情页读不到本机缓存",),
    ),
    Mutation(
        "h4-detail-says-not-found-when-unreadable",
        "评审 H4：详情页读不到时把 loaded 记成已读过：界面会说“找不到这个目的地（可能已被删除）”，把读不到说成了不存在",
        (Edit(DETAIL_VM, "loaded = cache.loaded, unreadable = cache.unreadable)", "loaded = true, unreadable = cache.unreadable)"),),
        app_tests("ReadFailureContainmentTest"), APP_RESULTS,
        ("详情页读不到本机缓存",),
    ),
    Mutation(
        "h4-read-never-retried",
        "评审 H4：读失败之后不再重试（发出“读不到”就结束）：存储恢复了页面也读不回来",
        (Edit(CONTAINED_READS, "        onFailure(last)\n", "        onFailure(last)\n        return@flow\n"),),
        app_tests("ReadFailureContainmentTest"), APP_RESULTS,
        ("读得到之后自动读回来", "恢复之后换成新的", "恢复后读回来", "重试间隔翻倍增长到上限"),
    ),
    Mutation(
        "h4-retry-backoff-uncapped",
        "评审 H4：重试间隔只翻倍不封顶：存储恢复之后要等越来越久（几分钟到几小时）才读回来",
        (Edit(CONTAINED_READS, ".coerceAtMost(READ_RETRY_MAX_MS))", ")"),),
        app_tests("ReadFailureContainmentTest"), APP_RESULTS,
        ("重试间隔翻倍增长到上限",),
    ),
    Mutation(
        "h4-retry-backoff-never-resets",
        "评审 H4：读到一次之后退避不从头算：偶尔读成功的页面也越等越久",
        (Edit(CONTAINED_READS, "                last = value\n                failures = 0\n", "                last = value\n"),),
        app_tests("ReadFailureContainmentTest"), APP_RESULTS,
        ("读到一次就从头算",),
    ),
    Mutation(
        "h4-retry-hammers-the-store",
        "评审 H4：读失败之后几乎不等就重读（没有退避，只等 1 毫秒）：存储一直坏着，就一直在原地打转，吃满一个核（等 0 毫秒在虚拟时间里会让测试自己死循环，所以用 1）",
        (Edit(CONTAINED_READS, "        delay((READ_RETRY_BASE_MS shl failures.coerceAtMost(MAX_DOUBLINGS)).coerceAtMost(READ_RETRY_MAX_MS))\n", "        delay(1)\n"),),
        app_tests("ReadFailureContainmentTest"), APP_RESULTS,
        ("重试间隔翻倍增长到上限",),
    ),
    Mutation(
        "h4-error-swallowed-as-read-failure",
        "评审 H4：把 Error（比如内存耗尽）也当成读失败吞掉重试：真正的致命问题被藏起来",
        (Edit(CONTAINED_READS, "                if (cause is CancellationException || cause !is Exception) throw cause\n                failure = cause\n", "                if (cause is CancellationException) throw cause\n                failure = cause as? Exception ?: IllegalStateException()\n"),),
        app_tests("ReadFailureContainmentTest"), APP_RESULTS,
        ("Error 不当作读失败吞掉",),
    ),
    Mutation(
        "h4-upstream-cancellation-retried",
        "评审 H4：上游自己抛出的取消异常也当成读失败去重试：取消传不出去，收集者一直收不到取消",
        (Edit(CONTAINED_READS, "                if (cause is CancellationException || cause !is Exception) throw cause\n", "                if (cause !is Exception) throw cause\n"),),
        app_tests("ReadFailureContainmentTest"), APP_RESULTS,
        ("上游自己抛出的取消异常照常往外传",),
    ),
    Mutation(
        "h4-failure-not-logged",
        "评审 H4：读失败不记日志：真机上出了存储问题，adb logcat 里什么线索也没有",
        (Edit(CONTAINED_READS, '        log.event("$name.read.failed ${caught::class.simpleName}")\n', ""),),
        app_tests("ReadFailureContainmentTest"), APP_RESULTS,
        ("待发送页读不到发送账", "目的地页读不到本机缓存", "重试间隔翻倍增长到上限"),
    ),
    Mutation(
        "h4-failure-log-carries-the-message",
        "评审 H4：读失败的日志带上异常信息：里面有数据库路径与文字，写进了 logcat",
        (Edit(CONTAINED_READS, '"$name.read.failed ${caught::class.simpleName}"', '"$name.read.failed ${caught::class.simpleName} ${caught.message}"'),),
        app_tests("ReadFailureContainmentTest"), APP_RESULTS,
        ("待发送页读不到发送账", "目的地页读不到本机缓存", "重试间隔翻倍增长到上限"),
    ),
    Mutation(
        "h4-runtime-scope-without-handler",
        "评审 H4：scopeOf 造的作用域不带异常处理器：逃出来的异常又走到线程默认处理器，进程退出",
        (Edit(AURI_RUNTIME, "CoroutineScope(SupervisorJob() + dispatcher + escapedFailureHandler)", "CoroutineScope(SupervisorJob() + dispatcher)"),),
        app_tests("RuntimeScopeGuardTest"), APP_RESULTS,
        ("运行时里只有一处直接造作用域",),
    ),
    Mutation(
        "h4-one-viewmodel-scope-built-raw",
        "评审 H4：待发送页的视图模型用一个裸作用域（没经 scopeOf）：只有它没有处理器",
        (Edit(AURI_RUNTIME, "SendReviewViewModel(sync.dispatch, itemTexts, SystemClock, sync::syncNow, scopeOf(),", "SendReviewViewModel(sync.dispatch, itemTexts, SystemClock, sync::syncNow, CoroutineScope(SupervisorJob() + Dispatchers.Default),"),),
        app_tests("RuntimeScopeGuardTest"), APP_RESULTS,
        ("运行时里只有一处直接造作用域",),
    ),
    Mutation(
        "h4-handler-logs-the-message",
        "评审 H4：处理器把异常信息也记进日志：里面可能有路径与文字",
        (Edit(AURI_RUNTIME, 'AndroidSyncLog.event("scope.escaped ${failure::class.simpleName}")', 'AndroidSyncLog.event("scope.escaped ${failure::class.simpleName} ${failure.message}")'),),
        app_tests("RuntimeScopeGuardTest"), APP_RESULTS,
        ("逃出来的异常只记事件名与异常类名",),
    ),
    Mutation(
        "h4-unreadable-banner-missing-on-review-page",
        "评审 H4：待发送页没有摆出“读不到”的那句话：状态里说了，页面上看不到",
        (Edit(SEND_REVIEW_SCREEN, "        if (state.unreadable) item { Text(describeUnreadable()) }\n", ""),),
        app_tests("ReadFailureContainmentTest"), APP_RESULTS,
        ("三个页面都把读不到的那句话摆出来",),
    ),
    Mutation(
        "h4-unreadable-banner-missing-on-destinations-page",
        "评审 H4：目的地页没有摆出“读不到”的那句话",
        (Edit(DESTINATIONS_SCREEN, "        if (state.unreadable) item { Text(describeUnreadable()) }\n", ""),),
        app_tests("ReadFailureContainmentTest"), APP_RESULTS,
        ("三个页面都把读不到的那句话摆出来",),
    ),
    Mutation(
        "h4-unreadable-banner-missing-on-detail-page",
        "评审 H4：详情页没有摆出“读不到”的那句话",
        (Edit(DETAIL_SCREEN, "        if (state.unreadable) item { Text(describeUnreadable()) }\n", ""),),
        app_tests("ReadFailureContainmentTest"), APP_RESULTS,
        ("三个页面都把读不到的那句话摆出来",),
    ),
    # ---- 评审 M1：等用户决定的文字不受“最近一页”的限制 ----
    Mutation(
        "m1-view-model-only-reads-the-latest-page",
        "评审 M1：待发送页只读最近一页：更新的文字多过一页之后，更早那条等确认的预览就在页面上消失了，够不着，没法确认或选择不发送",
        (Edit(SEND_REVIEW_VM, "        val items = (recent + dispatch.awaiting().filter { it.key !in shown }).map { it.toReview() }", "        val items = recent.map { it.toReview() }"),),
        app_tests("SendReviewBacklogTest"), APP_RESULTS,
        ("更新的文字多过一页", "更新的文字都已决定"),
    ),
    Mutation(
        "m1-view-model-lists-recent-awaiting-twice",
        "评审 M1：最近一页里本来就在等确认的文字又被“更早的等确认”补了一遍：同一条出现两次（列表按它的键排版，重复的键会让界面崩）",
        (Edit(SEND_REVIEW_VM, "dispatch.awaiting().filter { it.key !in shown }", "dispatch.awaiting()"),),
        app_tests("SendReviewBacklogTest"), APP_RESULTS,
        ("更新的文字多过一页",),
    ),
    Mutation(
        "m1-awaiting-has-a-page-limit",
        "评审 M1：发送账读“等确认的文字”时也只读一页：超过一页的更早的又够不着了",
        (Edit(DISPATCH_SQL, '            ORDER BY i.rowid DESC\n            """.trimIndent(),\n        ) { it.toKey() }.mapNotNull { loadItem(it, known) }.filter { it.awaitingUser }', '            ORDER BY i.rowid DESC LIMIT 30\n            """.trimIndent(),\n        ) { it.toKey() }.mapNotNull { loadItem(it, known) }.filter { it.awaitingUser }'),),
        platform_tests("SqliteDispatchStoreContractTest"), PLATFORM_RESULTS,
        ("awaiting_returns_every_text_still_waiting_for_the_user_newest_first_beyond_any_page_limit",),
    ),
    Mutation(
        "m1-awaiting-keeps-what-can-no-longer-be-decided",
        "评审 M1：目的地被撤销、没什么可决定的文字还留在“等确认”里：页面上一直挂着点了也没用的按钮",
        (Edit(DISPATCH_SQL, ".mapNotNull { loadItem(it, known) }.filter { it.awaitingUser }", ".mapNotNull { loadItem(it, known) }"),),
        platform_tests("SqliteDispatchStoreContractTest"), PLATFORM_RESULTS,
        ("awaiting_leaves_out_what_the_user_decided",),
    ),
    # ---- 评审 M2：预览确认的文字在等用户时，同步页如实说有几条，不只剩一句“已同步” ----
    Mutation(
        "m2-status-never-counts-the-waiting-texts",
        "评审 M2：状态流里等用户确认的条数永远是 0：页面又只剩一句“已同步”，用户不知道有文字在等他，Agent 也一直领不到",
        (Edit(SYNC_STATUS, ".map { dispatch.awaiting().size }", ".map { 0 }"),),
        sync_tests("SyncStatusAwaitingTest"), SYNC_RESULTS,
        ("状态数出有几条在等", "等待的条数随之减少", "只有预览确认目标的那一条"),
    ),
    Mutation(
        "m2-status-does-not-follow-the-ledger",
        "评审 M2：等确认的条数只在订阅那一刻数一次，用户确认或选择不发送之后页面上的数不再变",
        (Edit(SYNC_STATUS, "dispatch.changes().onStart { emit(0L) }", "kotlinx.coroutines.flow.flowOf(0L)"),),
        sync_tests("SyncStatusAwaitingTest"), SYNC_RESULTS,
        ("等待的条数随之减少",),
    ),
    Mutation(
        "m2-status-counts-every-text",
        "评审 M2：把所有文字都数成“在等确认”：自动发送的、已决定的也算，页面天天喊用户去确认",
        (Edit(SYNC_STATUS, ".map { dispatch.awaiting().size }", ".map { dispatch.items(1000).size }"),),
        sync_tests("SyncStatusAwaitingTest"), SYNC_RESULTS,
        ("没有等用户的文字时不多报", "等待的条数随之减少"),
    ),
    Mutation(
        "m2-summary-drops-the-count",
        "评审 M2：汇总函数收到了等确认的条数却没放进状态",
        (Edit(SYNC_STATUS, "SyncStatus(headline, causes.toList(), counts, nextRetryAt, dataFlow, awaitingYou)", "SyncStatus(headline, causes.toList(), counts, nextRetryAt, dataFlow)"),),
        sync_tests("SyncStatusAwaitingTest"), SYNC_RESULTS,
        ("状态数出有几条在等", "只有预览确认目标的那一条"),
    ),
    Mutation(
        "m2-sentence-never-shown",
        "评审 M2：那句话永远不出现（条数再多也返回 null）",
        (Edit(APP_UI + "SyncScreen.kt", "    if (count <= 0) null else \"有 $count 条文字在等你确认", "    if (count <= 100000) null else \"有 $count 条文字在等你确认"),),
        app_tests("SyncScreenTextTest"), APP_RESULTS,
        ("有文字在等用户确认",),
    ),
    Mutation(
        "m2-sentence-claims-synced",
        "评审 M2：那句话说成“已同步”：把等人确认说成了已经送达，用户更不会去处理",
        (Edit(APP_UI + "SyncScreen.kt", "确认之前不会发给对应的 Agent，Agent 领不到它们。", "已同步，Agent 领不到它们。"),),
        app_tests("SyncScreenTextTest"), APP_RESULTS,
        ("有文字在等用户确认",),
    ),
    Mutation(
        "m2-runtime-bypasses-the-ledger",
        "评审 M2：SyncRuntime 又自己拼 combine、只看任务表：设备上页面照旧只剩“已同步”，而单测全绿",
        (Edit(SYNC_RUNTIME, "SyncStatus.watch(pairing.states, jobs.observe(), dataFlow, dispatch)", "kotlinx.coroutines.flow.combine(pairing.states, jobs.observe(), dataFlow) { p, l, d -> SyncStatus.of(p, l, d) }"),),
        app_tests("SyncStatusWiringTest"), APP_RESULTS,
        ("同步状态流经 SyncStatus 的 watch",),
    ),
    Mutation(
        "m2-screen-forgets-the-sentence",
        "评审 M2：同步页不再把那句话摆出来（文案与状态都对，用户看不到）",
        (Edit(APP_UI + "SyncScreen.kt", "            describeAwaitingYou(status.awaitingYou)?.let { Text(it) }\n", ""),),
        app_tests("SyncStatusWiringTest"), APP_RESULTS,
        ("同步页把等用户确认的那句话摆出来",),
    ),
    # ---- 评审 M4：50 个有效 Destination 的上限由网关数（网关那一半另由 tests/integration/test_destination_limit.py 守着；这里是手机的一半，以及用真网关把两半接起来）----
    Mutation(
        "m4-service-trusts-the-cache",
        "评审 M4：创建前先拿本机缓存数一遍就拒绝：缓存是旧的（别的手机建了或撤销了），挡错了人，也不会再去问网关",
        (Edit(SERVICE, "        val result = createOnGateway(request)\n", "        if (dispatch.destinations().count { it.active } >= 50) return ServiceResult.Failed(ServiceFailure.LimitReached)\n        val result = createOnGateway(request)\n"),),
        sync_tests("DestinationServiceTest"), SYNC_RESULTS,
        ("缓存过期不能把用户挡在外面",),  # 缓存是空的那条（网关已满）本来就绕得过缓存预检：它守的是另一个破坏，第一次真跑时把它也列进来是预测错了，不是放宽
    ),
    Mutation(
        "m4-service-reports-the-refusal-as-plain-rejection",
        "评审 M4：网关说“到上限了”（409）却被当成笼统的拒绝：用户看到“网关拒绝了”，不知道该去撤销用不到的目的地",
        (LIMIT_READ_AS_PLAIN_REJECTION,),
        sync_tests("DestinationServiceTest"), SYNC_RESULTS,
        ("网关上有效的 Destination 已满 50 个而本机缓存不知道",),
    ),
    Mutation(
        "real-gw-m4-service-reports-the-refusal-as-plain-rejection",
        "评审 M4（真网关）：真网关拒绝第 51 个时，手机说的必须是“到上限了”",
        (LIMIT_READ_AS_PLAIN_REJECTION,),
        REAL, REAL_RESULTS,
        ("创建第 51 个被真网关拒绝",),
    ),
    Mutation(
        "m4-service-maps-every-rejection-to-limit",
        "评审 M4：创建被拒的任何原因都说成“到上限了”（比如请求不合规的 422）：用户去撤销一堆目的地也没用，真正的原因被盖住",
        (Edit(SERVICE, "this is ServiceFailure.Rejected && status == 409 && code == ErrorCode.REQUEST_INVALID", "this is ServiceFailure.Rejected"),),
        sync_tests("DestinationServiceTest"), SYNC_RESULTS,
        ("网关别的拒绝（比如请求不合规的 422）不会被当成上限",),
    ),
    Mutation(
        "m4-text-says-one-text-cannot-go-to-more",
        "评审 M4：上限的话又说回“一条文字最多只能发给这么多个”：现在多出来的目的地照样发得到，上限是网关管的账，用户会被说糊涂",
        (Edit(DESCRIPTIONS, "网关上有效的目的地已经有 50 个，最多只能有这么多，所以没有创建新的。请先撤销用不到的，再试一次。", "有效的目的地已经有 50 个，一条文字最多只能发给这么多个。请先撤销用不到的。"),),
        app_tests("DestinationTextTest"), APP_RESULTS,
        ("到了有效目的地的上限（评审 M4）",),
    ),
    Mutation(
        "m4-text-does-not-say-what-to-do",
        "评审 M4：上限的话只说“满了”，没说该先撤销用不到的：用户不知道怎么才能再建",
        (Edit(DESCRIPTIONS, "所以没有创建新的。请先撤销用不到的，再试一次。", "所以没有创建新的。"),),
        app_tests("DestinationTextTest"), APP_RESULTS,
        ("到了有效目的地的上限（评审 M4）",),
    ),
    Mutation(
        "real-gw-m4-gateway-no-limit",
        "评审 M4（真网关）：网关不数名额：缓存是空的另一部手机照样建出第 51 个，手机再怎么读 409 也没用",
        (Edit(GATEWAY_STORE, "            if active >= MAX_ACTIVE_DESTINATIONS:\n", "            if False:\n"),),
        REAL, REAL_RESULTS,
        ("创建第 51 个被真网关拒绝",),
    ),
    Mutation(
        "real-gw-m4-gateway-counts-revoked",
        "评审 M4（真网关）：网关把已撤销的也算进名额：撤销一个并不能空出名额，用户撤销了还是建不了新的",
        (Edit(GATEWAY_STORE, '"SELECT COUNT(*) FROM destinations WHERE tenant_id = ? AND revoked_at IS NULL", (auth.tenant_id,)', '"SELECT COUNT(*) FROM destinations WHERE tenant_id = ?", (auth.tenant_id,)'),),
        REAL, REAL_RESULTS,
        ("创建第 51 个被真网关拒绝",),
    ),
    # ---- 第三轮（再评 S2–S11）：每一项修复至少一条变异；S1 的三条在上面 ----
    Mutation(
        "s2-settle-without-the-pairing-lock",
        "第三轮 S2：网关结果的落账（入库、永久拒绝、停下、等待、重试）与“还是不是这一份配对”的判断在配对存储的同一把锁里：落账不进围栏，改配在判断与写之间插进来，A 的结果记到 B 的任务上，文字永远到不了 B",
        (Edit(ENGINE, "        pairing.whileCurrent(gateway, outcome) ?: stale(key)\n", "        outcome()\n"),),
        sync_tests("ResultFenceAtomicityTest"), SYNC_RESULTS,
        ("A 的入库成功回答落账时用户改配到 B", "问网关结果得到“已入库”时用户改配到 B", "A 的永久拒绝落账时用户改配到 B", "问网关结果得到“正文不同”时用户改配到 B", "令牌被网关拒绝时用户改配到 B", "找不到网关时用户改配到 B", "回答读不懂时用户改配到 B", "A 的永久拒绝回来时用户已经改配到 B"),
    ),
    Mutation(
        "s2-freeze-outside-the-pairing-lock",
        "第三轮 S2：构造提交冻结意图标签也是写账，要在围栏里：不围，旧配对的残余尝试在改配之后还去冻结 B 的标签",
        (Edit(ENGINE, "        val built = pairing.whileCurrent(gateway) { Built(source.submissionFor(key)) } ?: return stale(key)\n", "        val built = Built(source.submissionFor(key))\n"),),
        sync_tests("ResultFenceAtomicityTest"), SYNC_RESULTS,
        ("构造提交冻结意图标签的那次写之前用户改配到 B",),
    ),
    Mutation(
        "s3-permit-never-compares-the-generation",
        "第三轮 S3：每个请求交出去的前一刻要再比一次授权世代：不比，问放行之后（构造提交、换账本、记地址这些本地操作挂着的时候）收回授权，请求照发。“合同版本自愈：定位网关的回答在路上时授权被收回”那条测试盯的是定位之后的第二问、不是这里的世代比较，删了世代比较它照样绿，所以不在必红清单里（第三轮 1d 真跑确认：同一个类里 6 个测试变红 4 个，绿的两个正是它和只数构造提交次数的那条；它由 s3-heal-contract-skips-the-second-ask 盯着）",
        (Edit(ENGINE, "        override suspend fun <T : Any> gated(request: suspend () -> T): T? = PolicyGeneration.handoff({ policy.generation == generation }, request)\n", "        override suspend fun <T : Any> gated(request: suspend () -> T): T? = request()\n"),),
        sync_tests("PolicyGateRaceTest"), SYNC_RESULTS,
        ("构造提交挂在本地数据库里时授权被收回", "结果不确定的任务：构造提交挂着时授权被收回", "问网关结果之前的本地步骤做完时授权被收回"),
    ),
    Mutation(
        "s3-handoff-without-the-policy-lock",
        "第三轮 S3：世代比对与请求交给客户端必须和授权变更互斥；不加锁，另一个线程可以在比对之后、请求之前完成收回",
        (Edit(POLICY_GENERATION, "            val started = synchronized(handoffLock) {\n", "            val started = run {\n"),),
        sync_tests("PolicyHandoffRaceTest"), SYNC_RESULTS,
        ("revokedBetweenGenerationReadAndClientHandoffMustNotSendText",),
    ),
    Mutation(
        "s3-heal-contract-skips-the-second-ask",
        "第三轮 S3：合同版本自愈里，定位的回答回来之后要再问一次放行（探能力也是联系）：不问（换一张新盖的章、不看授权），定位回答在路上时收回授权，能力探测照发。只删这一行、沿用上一张章：整个 :sync:test 真跑全部通过（第三轮 1d，581 个），收回授权之后行为相同（请求被每个请求前的世代比较拦下），只差设置动了但没收回授权时，这一轮自愈多等一轮，对这条规则是等价变异；这里的变异是不问、直接按此刻的世代盖章",
        (Edit(ENGINE, "                permit = permitFor(gateway) ?: return // 定位是一次网络往返：回来再问一次放行\n", "                permit = Permit(policy.generation)\n"),),
        sync_tests("PolicyGateRaceTest"), SYNC_RESULTS,
        ("合同版本自愈：定位网关的回答在路上时授权被收回",),
    ),
    Mutation(
        "s3-last-ask-deleted-keeps-the-old-permit",
        "第三轮 1d（还原严格形式）：紧挨着请求的最后一问只删这一行、沿用上一张章：不是等价变异——最后一问还管着“要不要构造提交（冻结意图标签）”：少了它，被拦下的尝试也先构造了提交再放开；DataFlowGateTest 数最后一问的次数、PolicyGateRaceTest 数构造提交的次数都会变红",
        (Edit(ENGINE, "        // 刚才被收回的，什么都没发出去，退回等待（“不确定”还原成发送之前的样子）\n        permit = permitFor(gateway) ?: return waiting(gateway, key, wasUncertain)\n", "        // 刚才被收回的，什么都没发出去，退回等待（“不确定”还原成发送之前的样子）\n"),),
        sync_tests("DataFlowGateTest", "PolicyGateRaceTest"), SYNC_RESULTS,
        ("联系过网关、合同版本探测之后、紧挨着请求发出的最后一道", "第一次尝试：换账本之后授权被收回，请求之前的最后一问就拦下它"),
    ),
    Mutation(
        "s3-contract-probe-ask-deleted-keeps-the-old-permit",
        "第三轮 1d（还原严格形式）：合同版本探测之后的那一问只删这一行、沿用探测之前的章：不是等价变异——两个已有的测试会变红（真跑确认）",
        (Edit(ENGINE, "        permit = permitFor(gateway) ?: return waiting(gateway, key, wasUncertain)\n\n        // 换了网关的话，", "\n        // 换了网关的话，"),),
        sync_tests("SyncEngineTest", "DataFlowGateTest"), SYNC_RESULTS,
        ("复评 R13：看过哈希之后、构造提交之前本机把这条文字删了", "联系过网关、合同版本探测之后、紧挨着请求发出的最后一道"),
    ),
    Mutation(
        "s3-generation-not-moved-before-the-write",
        "第三轮 S3：改设置开始前世代就要变：不变，“已经开始改、还没落定”的那段时间里请求前的比对会放过",
        (Edit(POLICY_GENERATION, "    fun changing() {\n        synchronized(handoffLock) {\n            counter.incrementAndGet()\n            writing++\n        }\n    }\n", "    fun changing() {\n        synchronized(handoffLock) { }\n    }\n"),),
        sync_tests("PolicyGenerationTest", "PolicyGateRaceTest"), SYNC_RESULTS,
        ("开始改设置的那一刻世代就变了",),
    ),
    Mutation(
        "s3-generation-not-moved-after-settling",
        "第三轮 S3：设置落定之后世代要再变一次：不变，改的途中开始问放行的（读到的可能还是旧值）事后比对照样通过",
        (Edit(POLICY_GENERATION, "    fun changed() {\n        synchronized(handoffLock) {\n            counter.incrementAndGet()\n            if (writing > 0) writing--\n        }\n    }\n", "    fun changed() {\n        synchronized(handoffLock) { }\n    }\n"),),
        sync_tests("PolicyGenerationTest", "PolicyGateRaceTest"), SYNC_RESULTS,
        ("落定之后世代又变一次",),
    ),
    Mutation(
        "s3-sqlite-policy-write-skips-the-generation",
        "第三轮 S3（接线守卫）：SQLite 的授权设置每次写都要先让世代变（再在数据库线程上落定）：写授权设置绕开世代，引擎的请求前比对形同虚设",
        (Edit(SQLITE_POLICY, "            generations.changing()\n            library.withDatabase { db ->\n", "            library.withDatabase { db ->\n"),),
        app_tests("PolicyGateWiringTest"), APP_RESULTS,
        ("SqliteDataFlowPolicy 每次写授权设置都经 write",),
    ),
    Mutation(
        "s3-first-attempt-keeps-the-freeze-when-gated",
        "第三轮 S3：放行拦下的第一次尝试（请求没发出去）要把刚冻结的标签放开：不放开，用户的标签被一次什么也没发的尝试永久锁死",
        (Edit(ENGINE, "            ?: return waiting(gateway, key, wasUncertain, releaseIntent = !wasUncertain)\n", "            ?: return waiting(gateway, key, wasUncertain)\n"),),
        sync_tests("PolicyGateRaceTest"), SYNC_RESULTS,
        ("构造提交挂在本地数据库里时授权被收回",),
    ),
    Mutation(
        "s3-uncertain-attempt-releases-the-freeze",
        "第三轮 S3：上一次可能已经送到的任务，冻结不能放开：放开，网关上也许已经有那个标签，用户之后改了标签会撞上网关的“意图不可更改”",
        (Edit(ENGINE, "            ?: return waiting(gateway, key, wasUncertain, releaseIntent = !wasUncertain)\n", "            ?: return waiting(gateway, key, wasUncertain, releaseIntent = true)\n"),),
        sync_tests("PolicyGateRaceTest"), SYNC_RESULTS,
        ("结果不确定的任务：构造提交挂着时授权被收回",),
    ),
    Mutation(
        "s3-sql-unfreeze-does-nothing",
        "第三轮 S3：放开冻结要真的写进发送账：不写，引擎说放开了、用户却仍改不了标签",
        (Edit(DISPATCH_SQL, "        sql.update(\"UPDATE dispatch_items SET intent_locked = 0 WHERE record_id = ? AND revision = ? AND intent_locked = 1\", key.recordId, key.revision) > 0\n", "        false\n"),),
        platform_tests("SqliteDispatchStoreContractTest"), PLATFORM_RESULTS,
        ("unfreezing_touches_nothing_but_the_freeze_and_ignores_an_unfrozen_or_unknown_item",),
    ),
    Mutation(
        "s4-ledger-key-is-the-gateway-id-only",
        "第三轮 S4：发送账认网关要用“编号 + 公钥指纹”：只认编号，同一个编号换了公钥的另一台网关会继承原来的 Destination 缓存与排着的目标",
        (Edit(PAIRED_GATEWAY, "    val ledgerKey: String get() = \"$gatewayId $keySha256\"\n", "    val ledgerKey: String get() = gatewayId\n"),),
        sync_tests("SameIdOtherKeyTest"), SYNC_RESULTS,
        ("改配成同编号、另一把公钥的网关：A 排着的目标全部撤回，文字不带 A 的编号入库，不被永久拒绝", "用户先去发送页读了同编号另一台网关的名单"),
    ),
    Mutation(
        "s4-ledger-key-is-the-gateway-id-only-on-sqlite",
        "第三轮 S4（SQLite）：同上，落在真实的发送账里：只认编号就不会撤回按旧网关规划的目标",
        (Edit(PAIRED_GATEWAY, "    val ledgerKey: String get() = \"$gatewayId $keySha256\"\n", "    val ledgerKey: String get() = gatewayId\n"),),
        platform_tests("SqliteDispatchStoreContractTest"), PLATFORM_RESULTS,
        ("the_same_gateway_id_with_another_key_is_another_gateway_when_using_it", "the_same_gateway_id_with_another_key_is_another_gateway_when_hearing_from_it"),
    ),
    Mutation(
        "s4-sql-use-gateway-compares-the-id-only",
        "第三轮 S4：引擎每次发请求前对账，比的是完整身份：只比编号，同编号换公钥的网关被当成同一台，旧目标留在账上",
        (Edit(DISPATCH_SQL, "        if (meta(GATEWAY_ID) == gateway.ledgerKey) return@transaction false\n", "        if (meta(GATEWAY_ID)?.substringBefore(' ') == gateway.gatewayId) return@transaction false\n"),),
        platform_tests("SqliteDispatchStoreContractTest"), PLATFORM_RESULTS,
        ("the_same_gateway_id_with_another_key_is_another_gateway_when_using_it",),
    ),
    Mutation(
        "s5-epoch-always-inherited",
        "第三轮 S5：只有“同一台网关重新配对”才沿用世代：不论配成谁都沿用，配到 B 再配回 A 时世代没换，清理没跑成的旧同意在 A 身上复活",
        (Edit(PAIRING_SERVICE, "        return current?.takeIf { it.gatewayId == fresh.gatewayId && it.pin == fresh.pin }?.pairingEpoch ?: newEpoch()\n", "        return current?.pairingEpoch ?: newEpoch()\n"),),
        sync_tests("PairingServiceTest"), SYNC_RESULTS,
        ("对 A 同意过，配到 B、再配回 A：两次授权清理都失败，A 原来的同意也不复活，要用户重新同意",),
    ),
    Mutation(
        "s5-epoch-always-fresh",
        "第三轮 S5：同一台网关重新配对（中间没换过网关）沿用世代：永远换新，用户每次重新配对同一台网关都要重新同意一遍",
        (Edit(PAIRING_SERVICE, "        return current?.takeIf { it.gatewayId == fresh.gatewayId && it.pin == fresh.pin }?.pairingEpoch ?: newEpoch()\n", "        return newEpoch()\n"),),
        sync_tests("PairingServiceTest"), SYNC_RESULTS,
        ("同一台网关重新配对，中间没换过网关：同意沿用，不用重新同意",),
    ),
    Mutation(
        "s5-consent-ignores-the-pairing-epoch",
        "第三轮 S5：同意只对给出它的那一段配对算数：只比网关编号与公钥，同一台网关换了一段配对，旧同意（清理没跑成留下的记录）照样放行文字",
        (Edit(POLICY, "        DataFlowState(gatewayTextGranted = gateway != null && grantedTo == gateway, strictLocal = strictLocal)\n", "        DataFlowState(gatewayTextGranted = gateway != null && grantedTo?.ledgerKey == gateway.ledgerKey, strictLocal = strictLocal)\n"),),
        sync_tests("PairingServiceTest"), SYNC_RESULTS,
        ("对 A 同意过，配到 B、再配回 A：两次授权清理都失败，A 原来的同意也不复活，要用户重新同意", "取消配对后重新配对同一台网关：是新的一段配对，旧同意不复活（清理失败也一样）"),
    ),
    Mutation(
        "s5-consent-ignores-the-pairing-epoch-on-sqlite",
        "第三轮 S5（SQLite）：同上，授权那一行读出来再对身份时不看世代",
        (Edit(POLICY, "        DataFlowState(gatewayTextGranted = gateway != null && grantedTo == gateway, strictLocal = strictLocal)\n", "        DataFlowState(gatewayTextGranted = gateway != null && grantedTo?.ledgerKey == gateway.ledgerKey, strictLocal = strictLocal)\n"),),
        platform_tests("DataFlowPolicySqlTest"), PLATFORM_RESULTS,
        ("复评三 S5：授权记着是哪一段配对里给的",),
    ),
    Mutation(
        "s5-grant-epoch-not-validated",
        "第三轮 S5：授权那一行里的世代写得不合规（空白、过长、含非法字符）就当没授权：不校验，被改坏的行里的世代照单全收",
        (Edit(POLICY_SQL, "            parts[4].takeIf { GatewayIdentity.isValidEpoch(it) } ?: return null,\n", "            parts[4],\n"),),
        platform_tests("DataFlowPolicySqlTest"), PLATFORM_RESULTS,
        ("授权那一行里的世代写得不合规",),
    ),
    Mutation(
        "s5-pairing-store-drops-the-epoch",
        "第三轮 S5：配对的世代要随记录落盘：不存，重启之后世代回到 legacy，授权认的身份对不上（或者更糟，旧授权对上了）",
        (Edit(PAIRING_FILE_STORE, "            pairingEpoch = gateway.pairingEpoch,\n", "            pairingEpoch = GatewayIdentity.LEGACY_EPOCH,\n"),),
        sync_tests("FilePairingStoreTest"), SYNC_RESULTS,
        ("这一段配对的世代随记录落盘",),
    ),
    Mutation(
        "s6-422-permanent-without-rechecking",
        "第三轮 S6：请求被当成“不合规”拒绝，判成永久拒绝之前先重新问一次版本：不重问，网关被降级成旧版本后的 422 就把文字永久拒绝了",
        (Edit(ENGINE, "        val verdict = recheck() ?: return waiting(gateway, key, wasUncertain) // 授权刚被收回：什么都不再发，等\n        return settleVerdict(verdict, key, gateway, wasUncertain) ?: blocked(gateway, key, BlockedReason.REQUEST_REJECTED)\n", "        return blocked(gateway, key, BlockedReason.REQUEST_REJECTED)\n"),),
        sync_tests("ContractRevalidationTest"), SYNC_RESULTS,
        ("兼容过的网关在两次请求之间被降级", "被 422 拒绝后重新问版本也没问成"),
    ),
    Mutation(
        "s6-platform-network-events-keep-the-verdict",
        "第三轮 S6：平台报告断网、网络变了，兼容的记忆要作废：不作废，回来时网关可能已是旧版本，仍拿记着的兼容去发",
        (
            Edit(ENGINE, "        contract.invalidate() // 断过网：回来时网关可能已不是原来的版本，下一次联系前重新问（再评 S6）\n", ""),
            Edit(ENGINE, "        contract.invalidate() // 换了网络（或网络回来了）：同上，重新问版本（再评 S6）\n", ""),
        ),
        sync_tests("ContractRevalidationTest"), SYNC_RESULTS,
        ("平台报告没网又恢复",),
    ),
    Mutation(
        "s6-unreachable-location-keeps-the-verdict",
        "第三轮 S6：连不上网关之后兼容的记忆要作废：不作废，网关回来时是旧版本也照发",
        (Edit(ENGINE, "                if (it == Located.Unreachable) contract.invalidate() // 连不上过：回来时网关可能已不是原来的版本，下一次联系前重新问（再评 S6）\n", ""),),
        sync_tests("ContractRevalidationTest"), SYNC_RESULTS,
        ("网关断过线再回来、回来时已经是旧版本",),
    ),
    Mutation(
        "s6-guard-invalidate-keeps-the-memory",
        "第三轮 S6：作废要真的把兼容的记忆清掉：只加世代、不清记忆，之后的询问仍然直接回“兼容”",
        (Edit(CONTRACT_GUARD, "            verified = null\n            generation += 1\n", "            generation += 1\n"),),
        sync_tests("ContractRevalidationTest"), SYNC_RESULTS,
        ("兼容过的网关在两次请求之间被降级", "网关断过线再回来、回来时已经是旧版本"),
    ),
    Mutation(
        "s6-guard-remembers-an-answer-asked-before-an-invalidation",
        "第三轮 S6：作废之前发出的询问，它的“兼容”答复回来时不能再被记住：不比世代，作废之前的网关说的话把记忆又写回去",
        (Edit(CONTRACT_GUARD, "        if (answer == ContractAnswer.COMPATIBLE) synchronized(lock) { if (generation == asked) verified = gateway }\n", "        if (answer == ContractAnswer.COMPATIBLE) synchronized(lock) { verified = gateway }\n"),),
        sync_tests("ContractRevalidationTest"), SYNC_RESULTS,
        ("已经在路上的版本询问遇到作废",),
    ),
    Mutation(
        "s6-service-keeps-the-verdict-after-a-failed-call",
        "第三轮 S6：管理页的请求没成功（连不上、被拒绝）就要作废兼容的记忆：不作废，网关被降级之后管理页继续发管理请求",
        (Edit(SERVICE, "    private fun <T> GatewayResult<T>.noteOutcome(): GatewayResult<T> = also { if (it !is GatewayResult.Success) contract.invalidate() }\n", "    private fun <T> GatewayResult<T>.noteOutcome(): GatewayResult<T> = this\n"),),
        sync_tests("ContractRevalidationTest"), SYNC_RESULTS,
        ("管理页：兼容之后网关被降级，第一次请求失败就作废记忆",),
    ),
    Mutation(
        "s6-service-unreachable-keeps-the-verdict",
        "第三轮 S6：管理页连不上网关之后兼容的记忆要作废：不作废，回来时是旧网关也照发管理请求",
        (Edit(SERVICE, "                contract.invalidate() // 连不上过：回来时网关可能已不是原来的版本，下一次操作重新问（再评 S6）\n", ""),),
        sync_tests("ContractRevalidationTest"), SYNC_RESULTS,
        ("管理页：连不上过再回来、回来时是旧网关",),
    ),
    Mutation(
        "s6-additions-permanent-rejection-without-rechecking",
        "第三轮 S6：追加 Delivery 被拒绝，记成永久拒绝之前先重新问一次版本：不重问，旧网关没有这个端点的拒绝就把欠着的追加永久放弃了",
        (Edit(ENGINE, "                        if (!additionsMayProceed(gateway, revalidate(gateway, address, client) ?: return)) return\n", ""),),
        sync_tests("ContractRevalidationTest"), SYNC_RESULTS,
        ("追加 Delivery 被旧网关拒绝",),
    ),
    Mutation(
        "s7-too-old-cancels-the-wake",
        "第三轮 S7：网关太旧不算“停下等用户”：把它当成要用户处理的状态，叫醒被取消，进程被杀之后没有任何东西再去问“网关升级了没有”，文字一直留在手机上",
        (Edit(RESUME_PLANNER, "pairing.gateway.needsUser.let { it == null || it == NeedsUser.GATEWAY_TOO_OLD } &&\n", "pairing.gateway.needsUser == null &&\n"),),
        sync_tests("ResumePlannerTest", "ResumeRecoveryTest"), SYNC_RESULTS,
        ("网关太旧（不是连接坏了，用户只要更新网关）：有活就留着叫醒", "网关太旧期间进程被杀：重启后叫醒仍然登记着"),
    ),
    Mutation(
        "s7-any-connection-problem-keeps-the-wake",
        "第三轮 S7：只有“网关太旧”才留着叫醒：公钥不符、令牌被拒也留着，叫醒了也发不出去，白白一直打扰系统",
        (Edit(RESUME_PLANNER, "pairing.gateway.needsUser.let { it == null || it == NeedsUser.GATEWAY_TOO_OLD } &&\n", "pairing.gateway.needsUser.let { true } &&\n"),),
        sync_tests("ResumePlannerTest"), SYNC_RESULTS,
        ("决策表：只有已配对、连接可用、并且有没入库也没被永久拒绝的任务才需要叫醒",),
    ),
    Mutation(
        "s8-finishing-outside-the-lock",
        "第三轮 S8：被叫醒的那一轮的收尾判断要在门的锁里：不在锁里，判断“没活了”之后用户确认的预览，会在 jobFinished 生效之前看到“已经有一个叫醒”而不登记，随后叫醒被这一轮自己带走",
        (Edit(WAKE_GATE, "    suspend fun finishing(needed: suspend () -> Boolean): Boolean = lock.withLock {\n", "    suspend fun finishing(needed: suspend () -> Boolean): Boolean = run {\n"),),
        sync_tests("ResumeRecoveryTest"), SYNC_RESULTS,
        ("叫醒正在跑、收尾判断“没活了”的当口用户确认了",),
    ),
    Mutation(
        "s8-finishing-never-cancels-the-wake",
        "第三轮 S8：收尾判断没活时要同步取消系统里的叫醒（JobScheduler.cancel 立刻生效，jobFinished 不是）：不取消，确认的预先登记看到“还有一个”而什么也不做，叫醒随后被带走",
        (Edit(WAKE_GATE, "            scheduler.cancel()\n            told = false\n", "            told = false\n"),),
        sync_tests("ResumeRecoveryTest"), SYNC_RESULTS,
        ("叫醒正在跑、收尾判断“没活了”的当口用户确认了", "收尾判断没活：不信门里记的"),
    ),
    Mutation(
        "s8-finishing-trusts-what-the-gate-told",
        "第三轮 S8：收尾判断没活时不看门里记的“告诉过系统什么”：这一轮可能是门不知道的任务（持久任务重启后到点、系统自己重排），记着“没有”就不取消，叫醒留在系统里空转",
        (Edit(WAKE_GATE, "        } else {\n            scheduler.cancel()\n            told = false\n        }\n        now\n", "        } else if (told != false) {\n            scheduler.cancel()\n            told = false\n        }\n        now\n"),),
        sync_tests("ResumeRecoveryTest"), SYNC_RESULTS,
        ("收尾判断没活：不信门里记的",),
    ),
    Mutation(
        "s8-prewake-failure-still-commits",
        "第三轮 S8：系统拒绝登记叫醒时确认不能落账：照常提交，账上欠着追加、应用一被杀就再没有东西把它送出去，用户看到的却是“已确认”",
        (Edit(WAKE_GATE, "            return@withLock null\n", ""),),
        sync_tests("ResumeRecoveryTest"), SYNC_RESULTS,
        ("预先登记被系统拒绝：确认不落账",),
    ),
    Mutation(
        "s8-wake-unavailable-commits-anyway",
        "第三轮 S8：登记不上叫醒时发送账的壳不能再去提交：返回“没记下”之后又照常 decide，同样让确认落账而用户被告知没记下",
        (Edit(WAKE_GATE, "            wakes.ahead { inner.decide(key, destinationId, choice, now) } ?: DecideResult.WakeUnavailable\n", "            wakes.ahead { inner.decide(key, destinationId, choice, now) } ?: inner.decide(key, destinationId, choice, now)\n"),),
        sync_tests("ResumeRecoveryTest"), SYNC_RESULTS,
        ("预先登记被系统拒绝：确认不落账",),
    ),
    Mutation(
        "s8-runtime-finishing-skips-the-gate",
        "第三轮 S8（接线守卫）：运行器的“还有没有活”要经过门的收尾入口：绕开它，收尾判断又回到“门外读一下就下结论”",
        (Edit(SYNC_RUNTIME, "            wakes.finishing {\n                val paired = pairing.load()\n", "            run {\n                val paired = pairing.load()\n"),),
        app_tests("ResumeWiringGuardTest"), APP_RESULTS,
        ("被叫醒的那一轮的收尾判断经过门",),
    ),
    Mutation(
        "s8-review-ignores-wake-unavailable",
        "第三轮 S8：确认没落账（系统不让登记后台叫醒）时界面要如实说没记下、请再点一次：不说，用户以为已确认，其实什么都没记",
        (Edit(SEND_REVIEW_VM, "                is DecideResult.WakeUnavailable -> confirmNeedsRetry.value = true\n", "                is DecideResult.WakeUnavailable -> Unit\n"),),
        app_tests("SendReviewViewModelTest"), APP_RESULTS,
        ("再评 S8：系统不让登记后台叫醒时点“确认发送”",),
    ),
    Mutation(
        "s8-review-screen-never-shows-the-retry-notice",
        "第三轮 S8（接线守卫）：待发送页要把“没记下、请再点一次”摆出来：状态里有、页面不画，用户看不到",
        (Edit(SEND_REVIEW_SCREEN, "        if (state.confirmNeedsRetry) item { Text(describeConfirmNeedsRetry()) }\n", ""),),
        app_tests("SendReviewViewModelTest"), APP_RESULTS,
        ("再评 S8：那句话说清没记下、什么都没变、请再点一次",),
    ),
    Mutation(
        "s9-unreachable-first-attempt-keeps-the-freeze",
        "第三轮 S9：第一次尝试时连接层就失败（客户端证明什么也没发出）要放开冻结：不放开，什么也没发的失败尝试把用户的标签永久锁死",
        (Edit(ENGINE, "                is TransportFailure.Unreachable -> waiting(gateway, key, wasUncertain, releaseIntent = releaseIntentIfUnsent)\n", "                is TransportFailure.Unreachable -> waiting(gateway, key, wasUncertain)\n"),),
        sync_tests("UnsentIntentReleaseTest"), SYNC_RESULTS,
        ("第一次尝试：连接层失败（客户端证明什么也没发出）",),
    ),
    Mutation(
        "s9-key-mismatch-first-attempt-keeps-the-freeze",
        "第三轮 S9：第一次尝试时公钥不符（拒绝连接，一定没发出）也要放开冻结：不放开，用户在“要重新配对”的状态里仍改不了标签",
        (Edit(ENGINE, "connectionBlocked(key, gateway, NeedsUser.KEY_MISMATCH, BlockedReason.KEY_MISMATCH, wasUncertain, releaseIntent = releaseIntentIfUnsent)", "connectionBlocked(key, gateway, NeedsUser.KEY_MISMATCH, BlockedReason.KEY_MISMATCH, wasUncertain)"),),
        sync_tests("UnsentIntentReleaseTest"), SYNC_RESULTS,
        ("第一次尝试：公钥不符（拒绝连接，一定没发出）",),
    ),
    Mutation(
        "s9-interrupted-request-releases-the-freeze",
        "第三轮 S9：写出请求之后才断线的不能放开冻结：放开，网关可能已经看到了这个标签，用户之后改标签会撞上网关的“意图不可更改”",
        (Edit(ENGINE, "                is TransportFailure.Interrupted -> retryLater(gateway, key)\n", "                is TransportFailure.Interrupted -> waiting(gateway, key, wasUncertain, releaseIntent = releaseIntentIfUnsent)\n"),),
        sync_tests("UnsentIntentReleaseTest"), SYNC_RESULTS,
        ("只有“一定没发出”才放开",),
    ),
    Mutation(
        "s9-uncertain-attempt-releases-the-freeze",
        "第三轮 S9：上一次可能已经送到的任务，这次连接层失败也不能放开冻结：放开就等于假设上一次没送到",
        (Edit(ENGINE, "releaseIntentIfUnsent = !wasUncertain)", "releaseIntentIfUnsent = true)"),),
        sync_tests("UnsentIntentReleaseTest"), SYNC_RESULTS,
        ("上一次可能已经送到：这一次问过网关、重新提交时连接层失败，冻结绝不放开",),
    ),
    Mutation(
        "s10-focus-loss-does-not-clear-the-clipboard",
        "第三轮 S10：窗口失去焦点（分屏里点了别的窗口、系统对话框、下拉通知栏，Activity 不暂停）时也要叫守卫按“离开”处理：不叫，凭证在这些情形下一直留在剪贴板里",
        (Edit(MAIN_ACTIVITY, "        if (hasFocus) runtime.secretClipboard.onForeground() else runtime.secretClipboard.onLeaving()\n", "        if (hasFocus) runtime.secretClipboard.onForeground()\n"),),
        app_tests("ClipboardWiringTest"), APP_RESULTS,
        ("再评 S10：窗口失去焦点时也叫守卫按“离开”处理",),
    ),
    Mutation(
        "s11-ownership-trusts-any-content",
        "第三轮 S11：归属只看内容的摘要：不核对就当成自己的，别的应用复制了别的东西（哪怕把我们的描述字段整份抄上去）也会被清掉",
        (Edit(CLIP_OWNERSHIP, "        if (MessageDigest.isEqual(sha256(text), ours.digest)) return ClipboardPeek.Ours(ours.at)\n", "        if (true) return ClipboardPeek.Ours(ours.at)\n"),),
        app_tests("ClipOwnershipTest"), APP_RESULTS,
        ("再评 S11：别的应用复制了别的东西",),
    ),
    Mutation(
        "s11-ownership-compares-loosely",
        "第三轮 S11：核对要逐字：宽松比较（去掉首尾空白），多一个换行的别人的内容就被当成我们的",
        (Edit(CLIP_OWNERSHIP, "        if (MessageDigest.isEqual(sha256(text), ours.digest)) return ClipboardPeek.Ours(ours.at)\n", "        if (MessageDigest.isEqual(sha256(text.trim()), ours.digest)) return ClipboardPeek.Ours(ours.at)\n"),),
        app_tests("ClipOwnershipTest"), APP_RESULTS,
        ("逐字比：多一个换行",),
    ),
    Mutation(
        "s11-ownership-keeps-the-secret-text",
        "第三轮 S11：只在内存里留摘要，不留凭证文字：多存一份原文，等于在内存里又留了一份凭证",
        (
            Edit(CLIP_OWNERSHIP, "    private class Mine(val digest: ByteArray, val at: Long)\n", "    private class Mine(val digest: ByteArray, val at: Long, val text: String = \"\")\n"),
            Edit(CLIP_OWNERSHIP, "        mine = Mine(sha256(secret), at)\n", "        mine = Mine(sha256(secret), at, secret)\n"),
        ),
        app_tests("ClipOwnershipTest"), APP_RESULTS,
        ("摘要之外不留任何东西",),
    ),
    Mutation(
        "s11-ownership-keeps-reading-after-being-replaced",
        "第三轮 S11：发现我们放的那一份已被顶掉之后不再去读别人复制的东西：继续读，用户每次回到应用都可能看到系统弹的“读取了你复制的内容”",
        (Edit(CLIP_OWNERSHIP, "        mine = null // 我们放的那一份已经被顶掉了：没什么可清的，也不用再去读别人复制的东西\n", ""),),
        app_tests("ClipOwnershipTest"), APP_RESULTS,
        ("读别人复制的东西能少读就少读",),
    ),
    Mutation(
        "s11-ownership-forget-keeps-the-digest",
        "第三轮 S11：自己清掉之后就忘了：不忘，之后剪贴板里即使又出现一模一样的文字（别人复制的同一段），也会被认成我们的",
        (Edit(CLIP_OWNERSHIP, "    fun forget() {\n        mine = null\n    }\n", "    fun forget() {\n    }\n"),),
        app_tests("ClipOwnershipTest"), APP_RESULTS,
        ("自己清掉之后就忘了",),
    ),
    Mutation(
        "s11-port-does-not-remember-what-it-copied",
        "第三轮 S11（接线守卫）：真机端口放进剪贴板时要记下摘要：不记，之后永远认不出归属，到点与离开都清不掉凭证",
        (Edit(CLIPBOARD_PORT, "        ownership.remember(secret, at)\n", ""),),
        app_tests("ClipboardWiringTest"), APP_RESULTS,
        ("再评 S11：真机端口按内容认归属",),
    ),
    Mutation(
        "s11-port-clear-keeps-the-digest",
        "第三轮 S11（接线守卫）：真机端口清掉剪贴板之后要忘掉摘要：不忘，之后别人复制了同样的文字也被认成我们的",
        (Edit(CLIPBOARD_PORT, "        ownership.forget()\n", ""),),
        app_tests("ClipboardWiringTest"), APP_RESULTS,
        ("再评 S11：真机端口按内容认归属",),
    ),
    # ---- 第三轮续作：交接（bd93376 的锁）在真实客户端上的性质（1b）与“不能空洞通过”（1c）。ID 仍归 S3（授权世代与交接）----
    Mutation(
        "s3-real-client-runs-blocking-work-in-the-callers-prefix",
        "第三轮续作 1b：真实客户端的阻塞工作（解析主机名、连接、握手）必须在 IO 线程上，调用方这一侧只有“派发后挂起”：挪到调用方线程上，交接的锁就握在请求的网络等待之上，用户点开关（授权设置的改动要拿同一把锁）会被卡住",
        (Edit(PINNED_CLIENT, "val work = async(Dispatchers.IO) { runCatching { connection.run() } }", "val work = async(Dispatchers.Unconfined) { runCatching { connection.run() } }"),),
        sync_tests("PolicyHandoffRealClientTest"), SYNC_RESULTS,
        ("主机名解析卡住：在途的请求不挡授权设置的改动", "握手卡住：在途的请求不挡授权设置的改动"),
    ),
    Mutation(
        "s3-handoff-holds-the-lock-for-the-whole-request",
        "第三轮续作 1b：交接只在“比对 + 启动到第一次挂起”这一小段持锁，请求之后的网络等待不持锁：整段请求都握着锁，授权设置的改动要等网络回答才能做完",
        (Edit(POLICY_GENERATION, "async(start = CoroutineStart.UNDISPATCHED) { request() } else null", "async(start = CoroutineStart.UNDISPATCHED) { request() }.also { kotlinx.coroutines.runBlocking { it.join() } } else null"),),
        # 只挑请求跑在 Default/IO 线程上的测试：runTest 里的别的测试把请求放在测试线程上，持锁等请求会把唯一的测试线程卡死（真跑过：整个变异要等满 30 分钟的上限）。
        # 这条变异还要求 Default 线程池至少 3 个线程（一个等请求、一个被锁挡住、一个跑请求的后半截）
        sync_tests("PolicyHandoffRealClientTest", "PolicyHandoffRaceTest.aSuspendedRequestDoesNotHoldUpPolicyChanges"), SYNC_RESULTS,
        ("aSuspendedRequestDoesNotHoldUpPolicyChanges", "主机名解析卡住：在途的请求不挡授权设置的改动"),
    ),
    Mutation(
        "s3-handoff-shields-the-request-from-cancellation",
        "第三轮续作 1b：取消调用方要穿过交接传到在途的请求（关掉它的套接字）：交接把请求包在不可取消的上下文里，调用方被取消了请求还要等到单步超时才结束",
        (
            Edit(POLICY_GENERATION, "suspend fun <T : Any> handoff(permitted: () -> Boolean, request: suspend () -> T): T? = coroutineScope {", "suspend fun <T : Any> handoff(permitted: () -> Boolean, request: suspend () -> T): T? = withContext(NonCancellable) {"),
            Edit(POLICY_GENERATION, "import kotlinx.coroutines.coroutineScope\n", "import kotlinx.coroutines.NonCancellable\nimport kotlinx.coroutines.coroutineScope\nimport kotlinx.coroutines.withContext\n"),
        ),
        sync_tests("PolicyHandoffRealClientTest"), SYNC_RESULTS,
        ("取消调用方：取消穿过交接传到卡在握手里的请求",),
    ),
    Mutation(
        "s3-handoff-reports-a-failed-request-as-withheld",
        "第三轮续作 1b：请求抛出的异常要原样交给调用方：被吞掉、当成“没放行”（null），调用方以为什么都没发，可请求其实已经开始了",
        (Edit(POLICY_GENERATION, "async(start = CoroutineStart.UNDISPATCHED) { request() } else null", "async(start = CoroutineStart.UNDISPATCHED) { runCatching { request() }.getOrNull() } else null"),),
        sync_tests("PolicyHandoffRealClientTest"), SYNC_RESULTS,
        ("请求抛出的异常原样交给调用方",),
    ),
    Mutation(
        "s3-handoff-checks-then-never-sends",
        "第三轮续作 1c：比对放行了就要把请求真的跑起来、结果原样交回：放行了却一个请求也没跑（引擎看到的是“没放行”），交接测试与真实客户端测试都要变红",
        (Edit(POLICY_GENERATION, "if (permitted()) async(", "if (permitted() && false) async("),),
        # 只挑两个要变红的测试：别的测试在这个变异下会等满 runTest 的超时才失败（整类要跑 4 分钟）
        sync_tests("PolicyHandoffRaceTest.revokedBetweenGenerationReadAndClientHandoffMustNotSendText", "PolicyHandoffRealClientTest.放行并运行完的请求把结果原样交回"), SYNC_RESULTS,
        ("revokedBetweenGenerationReadAndClientHandoffMustNotSendText", "放行并运行完的请求把结果原样交回"),
    ),
    Mutation(
        "s3-put-handed-over-with-a-stale-permit",
        "第三轮续作 1c：撤销要等交接的那一支，必须看到 PUT 真的发出去了一次：PUT 拿着过期的章（交接一定拦下它），旧版测试在这一支不数发出的请求、照样绿；现在放行的那一支要求恰好一个 PUT",
        (Edit(ENGINE, "        val put = permit.gated { client.putRevision(address, gateway.token, submission) }\n", "        val put = Permit(-1).gated { client.putRevision(address, gateway.token, submission) }\n"),),
        sync_tests("PolicyHandoffRaceTest.revokedBetweenGenerationReadAndClientHandoffMustNotSendText"), SYNC_RESULTS,
        ("revokedBetweenGenerationReadAndClientHandoffMustNotSendText",),
    ),
    # ---- 第三轮续作 S12：找网关是一串请求（逐个地址探测、再问 mDNS），每一个各过一次门；整串只在第一个之前过一次，第一个请求在路上时收回授权，后面的探测照发 ----
    Mutation(
        "s12-walk-skips-the-gate-for-probes",
        "第三轮 S12：逐个地址探测，每个探测交出去之前都要过门：不过，第一个探测在路上时收回授权，下一个地址的探测照发（一次发给网关的请求，在授权已被收回之后）",
        (Edit(DISCOVERY, "            when (val step = gate.gated { attempt(address) } ?: return Walk.Withheld) {\n", "            when (val step = attempt(address)) {\n"),),
        sync_tests("PolicyHandoffRaceTest", "GatewayLocatorTest"), SYNC_RESULTS,
        ("revocationDuringTheFirstProbeStopsTheNextProbe", "第一个探测放行之后门不放行"),
    ),
    Mutation(
        "s12-walk-skips-the-gate-for-mdns",
        "第三轮 S12：问 mDNS 也是一次联系（组播查询），交出去之前要过门：不过，记下的地址探测在路上时收回授权，mDNS 查询照发",
        (Edit(DISCOVERY, "        gate.gated { discovery.find(gatewayId) } ?: return Walk.Withheld\n", "        discovery.find(gatewayId)\n"),),
        sync_tests("PolicyHandoffRaceTest", "GatewayLocatorTest"), SYNC_RESULTS,
        ("revocationDuringTheLastStoredProbeStopsTheMdnsQuery", "记下的地址都试过之后门不放行：不问 mDNS"),
    ),
    Mutation(
        "s12-walk-keeps-asking-after-the-gate-withholds",
        "第三轮 S12：门一旦不放行，整个找网关就停在那一步：不停（跳过这个地址接着试后面的），门的回答就成了每个地址各自的，之后还会一个一个再问",
        (Edit(DISCOVERY, "            when (val step = gate.gated { attempt(address) } ?: return Walk.Withheld) {\n", "            when (val step = gate.gated { attempt(address) } ?: continue) {\n"),),
        sync_tests("GatewayLocatorTest"), SYNC_RESULTS,
        ("第一个探测放行之后门不放行",),
    ),
    Mutation(
        "s12-withheld-walk-reported-as-unreachable",
        "第三轮 S12：被门拦下的找网关要说“被拦下”（null），不能说成“连不上”：说成连不上，引擎会把它记成这一轮的结论（并作废兼容记忆），可它其实什么都没试完",
        (Edit(LOCATOR, "            Walk.Withheld -> null\n", "            Walk.Withheld -> Located.Unreachable\n"),),
        sync_tests("GatewayLocatorTest"), SYNC_RESULTS,
        ("第一个探测放行之后门不放行", "记下的地址都试过之后门不放行：不问 mDNS", "mDNS 问过之后门不放行"),
    ),
    Mutation(
        "s12-locator-hands-an-open-gate-to-the-walk",
        "第三轮 S12：定位器必须把门交给逐个探测的那一步：不交（用默认的不设防的门），带门的定位退化成没有门",
        (Edit(LOCATOR, "walkAddresses(stored, discovery, gateway.gatewayId, log, gate) { address ->", "walkAddresses(stored, discovery, gateway.gatewayId, log) { address ->"),),
        sync_tests("PolicyHandoffRaceTest", "GatewayLocatorTest"), SYNC_RESULTS,
        ("revocationDuringTheFirstProbeStopsTheNextProbe", "revocationDuringTheLastStoredProbeStopsTheMdnsQuery", "第一个探测放行之后门不放行"),
    ),
    Mutation(
        "s12-engine-locates-without-the-permit",
        "第三轮 S12：引擎定位要把这一次的 permit 交给定位器：交不进去（用不设防的那一扇），问放行之后到逐个探测之间授权被收回，后面的探测照发",
        (Edit(ENGINE, "            return locator.locate(gateway, permit)?.also {\n", "            return locator.locate(gateway)?.also {\n"),),
        sync_tests("PolicyHandoffRaceTest"), SYNC_RESULTS,
        ("revocationDuringTheFirstProbeStopsTheNextProbe", "revocationDuringTheLastStoredProbeStopsTheMdnsQuery"),
    ),
    Mutation(
        "s12-open-gate-withholds",
        "第三轮 S12：不设防的门原样运行请求：它拦下请求，配对与目的地管理（不过授权）就再也连不上网关",
        (Edit(REQUEST_GATE, "        override suspend fun <T : Any> gated(request: suspend () -> T): T? = request()\n", "        override suspend fun <T : Any> gated(request: suspend () -> T): T? = null\n"),),
        sync_tests("GatewayLocatorTest"), SYNC_RESULTS,
        ("先连最近一次连上的地址；它通就不再试别的，也不去问 mDNS",),
    ),
    # 下面四条（接线守卫）：每一处定位都要用“问放行时盖的那一张” permit；现盖一张新的（世代取现在的）等于把“问完到交出去之间”的缝又打开——行为测试只摆了发送那一条路径，其余三处靠守卫
    Mutation(
        "s12-attempt-locates-with-a-fresh-permit",
        "第三轮 S12（接线守卫）：发送路径的定位用的是问放行时盖的章：现盖一张新的，问放行与定位之间收回授权就看不出来",
        (Edit(ENGINE, "        val located = location.get(gateway, permit) ?: return waiting(gateway, key, wasUncertain)\n", "        val located = location.get(gateway, Permit(policy.generation)) ?: return waiting(gateway, key, wasUncertain)\n"),),
        app_tests("PolicyGateWiringTest"), APP_RESULTS,
        ("引擎里每一个会发往网关的调用都包在 permit gated 里",),
    ),
    Mutation(
        "s12-probe-locates-with-a-fresh-permit",
        "第三轮 S12（接线守卫）：等待网关的探测用问放行时盖的章去定位：现盖一张新的，同上",
        (Edit(ENGINE, "        when (location.get(gateway, permit) ?: return) {\n", "        when (location.get(gateway, Permit(policy.generation)) ?: return) {\n"),),
        app_tests("PolicyGateWiringTest"), APP_RESULTS,
        ("引擎里每一个会发往网关的调用都包在 permit gated 里",),
    ),
    Mutation(
        "s12-heal-contract-locates-with-a-fresh-permit",
        "第三轮 S12（接线守卫）：合同版本自愈的定位用问放行时盖的章：现盖一张新的，同上",
        (Edit(ENGINE, "        val located = location.get(gateway, permit) ?: return\n        val answer = when (located) {\n", "        val located = location.get(gateway, Permit(policy.generation)) ?: return\n        val answer = when (located) {\n"),),
        app_tests("PolicyGateWiringTest"), APP_RESULTS,
        ("引擎里每一个会发往网关的调用都包在 permit gated 里",),
    ),
    Mutation(
        "s12-additions-locate-with-a-fresh-permit",
        "第三轮 S12（接线守卫）：追加 Delivery 的定位用问放行时盖的章：现盖一张新的，同上",
        (Edit(ENGINE, "        if (gateway == null) return\n        val located = location.get(gateway, permit) ?: return\n", "        if (gateway == null) return\n        val located = location.get(gateway, Permit(policy.generation)) ?: return\n"),),
        app_tests("PolicyGateWiringTest"), APP_RESULTS,
        ("引擎里每一个会发往网关的调用都包在 permit gated 里",),
    ),
    # ---- 第四轮 1：授权设置的写窗口（从“开始改”到“落定”）里，世代是一次性的章；写块没跑起来也不能让窗口永远开着 ----
    Mutation(
        "r4f1-window-reads-return-the-counter",
        "第四轮 1：写开着的时候读世代必须是新的负数章：只返回计数，写还排在队里时问的放行带着新世代、读到旧授权，写提交之后、落定之前请求照发（收回已经提交，请求还是出去了）",
        (Edit(POLICY_GENERATION, "get() = synchronized(handoffLock) { if (writing > 0) --windowReads else counter.get() }", "get() = synchronized(handoffLock) { counter.get() }"),),
        sync_tests("PolicyWriteWindowTest"), SYNC_RESULTS,
        ("收回还排在队里时问的放行", "写进行中读到的世代每次都不同", "写没落定之前交接被拦下", "两次写重叠", "不收回的写也一样"),
    ),
    Mutation(
        "r4f1-window-reads-repeat",
        "第四轮 1：窗口里的章必须每次不同：窗口里每次读都是同一个负数，窗口里记下的章窗口里交接就对得上（同一个章）——请求照发",
        (Edit(POLICY_GENERATION, "get() = synchronized(handoffLock) { if (writing > 0) --windowReads else counter.get() }", "get() = synchronized(handoffLock) { if (writing > 0) -1L else counter.get() }"),),
        sync_tests("PolicyWriteWindowTest"), SYNC_RESULTS,
        ("收回还排在队里时问的放行", "写进行中读到的世代每次都不同", "写没落定之前交接被拦下"),
    ),
    Mutation(
        "r4f1-window-never-opens",
        "第四轮 1：开始改设置要真的开一个窗口：只加计数不开窗口，窗口里读到的世代还是计数，与旧授权对不上的那条缝原样还在",
        (Edit(POLICY_GENERATION, "            counter.incrementAndGet()\n            writing++\n", "            counter.incrementAndGet()\n"),),
        sync_tests("PolicyWriteWindowTest", "PolicyWriteProtocolTest"), SYNC_RESULTS,
        ("收回还排在队里时问的放行", "写进行中读到的世代每次都不同", "正常的写：窗口只在写块提交之后才关上"),
    ),
    Mutation(
        "r4f1-first-settle-closes-every-window",
        "第四轮 1：两次写重叠要都落定才算关上：第一次落定就把计数清零，第二次还在写、窗口却已经关了（旧授权的章在第二次提交之前就能交接）",
        (Edit(POLICY_GENERATION, "            if (writing > 0) writing--\n", "            writing = 0\n"),),
        sync_tests("PolicyWriteWindowTest", "PolicyWriteProtocolTest"), SYNC_RESULTS,
        ("两次写重叠", "凭据的落定是幂等的"),
    ),
    Mutation(
        "r4f1-ticket-settles-twice",
        "第四轮 1：一张凭据的落定只算一次：数据库线程上的 finally 与外层 finally 都落定，第二次再减一个计数——替别人的窗口关了门，世代也多动一次",
        (Edit(POLICY_GENERATION, "            if (state.compareAndSet(OPEN, SETTLED)) this@PolicyGeneration.changed() else state.compareAndSet(NEW, SETTLED)\n", "            this@PolicyGeneration.changed()\n"),),
        sync_tests("PolicyWriteProtocolTest"), SYNC_RESULTS,
        ("凭据的落定是幂等的", "写块已经开始之后调用方被取消"),
    ),
    Mutation(
        "r4f1-ticket-opens-after-settling",
        "第四轮 1：已经落定的凭据不能再开窗口：能开，外层 finally 先落定的那种顺序会留下一个没人关的窗口（之后每个请求都被拦）",
        (Edit(POLICY_GENERATION, "            if (state.compareAndSet(NEW, OPEN)) this@PolicyGeneration.changing()\n", "            state.set(OPEN)\n            this@PolicyGeneration.changing()\n"),),
        sync_tests("PolicyWriteProtocolTest"), SYNC_RESULTS,
        ("没开过窗口的凭据直接落定",),
    ),
    Mutation(
        "r4f1-sqlite-write-without-the-outer-settle",
        "第四轮 1（接线守卫）：SQLite 的写要有外层 finally 再落定一次：写块没跑起来（调用方在块开始前被取消、拿不到数据库连接）时窗口永远关不上，之后每个请求都被拦",
        (Edit(SQLITE_POLICY, "        } finally {\n            generations.changed()\n        }\n        changes.value += 1\n", "        } finally {\n        }\n        changes.value += 1\n"),),
        app_tests("PolicyWriteWiringTest"), APP_RESULTS,
        ("SqliteDataFlowPolicy 的 write 取一张凭据",),
    ),
    Mutation(
        "r4f1-sqlite-write-settles-off-the-database-thread",
        "第四轮 1（接线守卫）：落定要在数据库线程上、紧接着写：只在外层落定，窗口要等回到调用方（隔着一次调度）才关，那一小段里读设置读到的已经是新值、世代却还是章",
        (Edit(SQLITE_POLICY, "                try {\n                    DataFlowPolicySql(AndroidSqlDb(db)).change()\n                } finally {\n                    generations.changed()\n                }\n", "                DataFlowPolicySql(AndroidSqlDb(db)).change()\n"),),
        app_tests("PolicyWriteWiringTest", "PolicyGateWiringTest"), APP_RESULTS,
        ("SqliteDataFlowPolicy 的 write 取一张凭据", "SqliteDataFlowPolicy 每次写授权设置都经 write"),
    ),
    Mutation(
        "r4f1-sqlite-write-touches-the-window-directly",
        "第四轮 1（接线守卫）：窗口只能经 write 里的那张凭据开关：别处直接 changing()/changed()，开了没人关或关了没人开",
        (Edit(SQLITE_POLICY, "    override suspend fun setStrictLocal(on: Boolean) = write { setStrictLocal(on) }\n", "    override suspend fun setStrictLocal(on: Boolean) = write { setStrictLocal(on) }.also { generations.changed() }\n"),),
        app_tests("PolicyWriteWiringTest"), APP_RESULTS,
        ("世代的开窗口与关窗口只经过 write 里的这张凭据",),
    ),
    # ---- 第四轮 F2：升级补截止点失败时关门（网关迁移 0005 的真实 SQL 在真实的更早迁移之后的库上执行；E2 集成测试） ----
    Mutation(
        "r4f2-gw-predecessor-rule-accepts-any-credential",
        "第四轮 F2（网关）：判断“是不是轮换”要看紧挨着的上一条凭证是不是在这一刻被撤销：改回旧规则“有没有哪条凭证在这一刻被撤销”，碰巧相等的时间戳（别的凭证在那一刻被撤销）就被当成轮换，真实的暂停漏补",
        (Edit(GATEWAY_MIGRATION_0005, "                AND p.rowid = (\n                    SELECT MAX(q.rowid) FROM destination_credentials AS q\n                    WHERE q.tenant_id = c.tenant_id AND q.destination_id = c.destination_id AND q.rowid < c.rowid\n                )\n", "                AND p.credential_id <> c.credential_id\n"),),
        gateway_tests(BACKFILL_TESTS), GATEWAY_RESULTS,
        (f"{BACKFILL_CASE}[coincidence-everywhere]",),
    ),
    Mutation(
        "r4f2-gw-predecessor-lookup-crosses-destinations",
        "第四轮 F2（网关）：“紧挨着的上一条”只在同一个 Destination 里找：不分 Destination 地找，凭证在 rowid 上交错时，轮换过的 Destination 的上一条成了别的 Destination 的凭证，干净的轮换被误判成暂停",
        (Edit(GATEWAY_MIGRATION_0005, "                    WHERE q.tenant_id = c.tenant_id AND q.destination_id = c.destination_id AND q.rowid < c.rowid\n", "                    WHERE q.tenant_id = c.tenant_id AND q.rowid < c.rowid\n"),),
        gateway_tests(BACKFILL_TESTS), GATEWAY_RESULTS,
        ("test_each_destination_is_judged_by_its_own_history_even_when_the_credentials_are_interleaved",),
    ),
    Mutation(
        "r4f2-gw-same-instant-issues-not-detected",
        "第四轮 F2（网关）：同一个 Destination 里两条凭证签发在同一刻，先后不明，必须当含糊的历史补上截止点：不认这一条，凭那一刻的相等把它当轮换，真实的暂停漏补",
        (Edit(GATEWAY_MIGRATION_0005, "         AND d.credential_id <> c.credential_id AND d.issued_at = c.issued_at\n", "         AND d.credential_id <> c.credential_id AND d.issued_at = c.issued_at AND 0\n"),),
        gateway_tests(BACKFILL_TESTS), GATEWAY_RESULTS,
        (f"{BACKFILL_CASE}[same-issue-instant]",),
    ),
    Mutation(
        "r4f2-gw-cancel-trace-ignored",
        "第四轮 F2（网关）：撤销的那一瞬间有被取消的投递，是新版网关暂停的痕迹（轮换不取消任何东西）：不认这个痕迹，撤销与重新签发时间戳相同的真实暂停被当成轮换，替换凭证读得到撤销之前已确认的文字",
        (Edit(GATEWAY_MIGRATION_0005, "         AND d.state = 'CANCELLED' AND d.cancelled_at = p.revoked_at\n", "         AND d.state = 'CANCELLED' AND d.cancelled_at = p.revoked_at AND 0\n"),),
        gateway_tests(BACKFILL_TESTS), GATEWAY_RESULTS,
        (f"{BACKFILL_CASE}[cancel-trace]", "test_an_upgrade_hides_the_acknowledged_texts_of_a_suspension_whose_revoke_and_reissue_share_a_timestamp"),
    ),
    Mutation(
        "r4f2-gw-cancel-trace-without-a-later-credential",
        "第四轮 F2（网关）：取消痕迹只在“之后还签发过凭证”时才说明暂停已经结束：不要求后面还有凭证，正在暂停（最后一条凭证刚被撤销、当场取消了排着的投递）的 Destination 也被补上截止点",
        (Edit(GATEWAY_MIGRATION_0005, "              WHERE c.tenant_id = p.tenant_id AND c.destination_id = p.destination_id AND c.rowid > p.rowid\n", "              WHERE c.tenant_id = p.tenant_id AND c.destination_id = p.destination_id AND (c.rowid > p.rowid OR 1)\n"),),
        gateway_tests(BACKFILL_TESTS), GATEWAY_RESULTS,
        (f"{BACKFILL_CASE}[cancel-without-later-credential]",),
    ),
    Mutation(
        "r4f2-gw-cancel-trace-from-another-destination",
        "第四轮 F2（网关）：只认本 Destination 的取消痕迹：不分 Destination，别的 Destination 恰好在这一刻取消的投递会把一次干净的轮换认成暂停",
        (Edit(GATEWAY_MIGRATION_0005, "          ON d.tenant_id = p.tenant_id AND d.destination_id = p.destination_id\n", "          ON d.tenant_id = p.tenant_id\n"),),
        gateway_tests(BACKFILL_TESTS), GATEWAY_RESULTS,
        ("test_a_cancelled_delivery_of_another_destination_does_not_make_a_rotation_look_like_a_suspension",),
    ),
    Mutation(
        "r4f2-gw-cancel-trace-needs-no-matching-instant",
        "第四轮 F2（网关）：取消的时刻必须正好等于撤销的时刻才算痕迹：只要这个 Destination 有被取消的投递就算，普通的取消（例如删除一条 Record）把干净的轮换都补成暂停，排着的文字被悄悄藏起来",
        (Edit(GATEWAY_MIGRATION_0005, "         AND d.state = 'CANCELLED' AND d.cancelled_at = p.revoked_at\n", "         AND d.state = 'CANCELLED'\n"),),
        gateway_tests(BACKFILL_TESTS), GATEWAY_RESULTS,
        (f"{BACKFILL_CASE}[rotation-unrelated-cancel]",),
    ),
    Mutation(
        "r4f2-gw-cutoff-takes-other-tenants-texts",
        "第四轮 F2（网关）：补出的截止点只取本 Tenant 里已有文字的最晚入库时间：把别的 Tenant 的文字也算进来，一个 Tenant 的时钟异常会让另一个 Tenant 的文字全被藏起来",
        (Edit(GATEWAY_MIGRATION_0005, "    COALESCE((SELECT MAX(r.stored_at) FROM revisions AS r WHERE r.tenant_id = destinations.tenant_id), '')\n", "    COALESCE((SELECT MAX(r.stored_at) FROM revisions AS r), '')\n"),),
        gateway_tests(BACKFILL_TESTS), GATEWAY_RESULTS,
        ("test_the_cutoff_of_an_ambiguous_history_covers_every_text_already_stored_in_the_tenant_and_nothing_from_other_tenants",),
    ),
)

base.MUTATIONS = MUTATIONS


def main(argv: list[str]) -> int:
    return base.main(argv)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
