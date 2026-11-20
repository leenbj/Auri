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
import pathlib
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
RESUME_PLANNER = ANDROID + "sync/src/main/kotlin/app/auri/sync/resume/ResumePlanner.kt"
SYNC_RUNTIME = SYNCPLATFORM + "SyncRuntime.kt"
SYNC_STATUS = ANDROID + "sync/src/main/kotlin/app/auri/sync/status/SyncStatus.kt"
SEND_REVIEW_VM = APP_DEST + "SendReviewViewModel.kt"
CLIPBOARD_GUARD = APP_DEST + "SecretClipboardGuard.kt"
MAIN_ACTIVITY = ANDROID + "app/src/main/kotlin/app/auri/android/MainActivity.kt"
AURI_RUNTIME = ANDROID + "app/src/main/kotlin/app/auri/android/AuriRuntime.kt"
CONTAINED_READS = APP_DEST + "ContainedReads.kt"
SEND_REVIEW_SCREEN = APP_UI + "SendReviewScreen.kt"
DESTINATIONS_SCREEN = APP_UI + "DestinationsScreen.kt"
DETAIL_SCREEN = APP_UI + "DestinationDetailScreen.kt"

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
POLICY_GATE_OPEN = Edit(ENGINE, "    private suspend fun mayTransmit(): Boolean = policy.current().textMayEnterGateway", "    private suspend fun mayTransmit(): Boolean = true")

# 评审 B3：手机读 Destination 的 credential_active（真网关测试与单测各跑一遍，用同一份编辑）
GATEWAY_CLIENT = SYNC_GATEWAY + "GatewayClient.kt"
CLIENT_IGNORES_CREDENTIAL_ACTIVE = Edit(
    GATEWAY_CLIENT,
    '            credentialActive = reply.booleanOrNull("credential_active") ?: issued,',
    "            credentialActive = issued,",
)

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
            "                    if (result.value.deliveries.any { it.destinationId == addition.destinationId }) additions.gatewayHas(addition.key, ids)\n                    else additions.refused(addition.key, ids, Refusal.NOT_CREATED)\n",
            "                    additions.gatewayHas(addition.key, ids)\n",
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
        (Edit(
            ENGINE,
            "        // 落盘之后、联系网关之前再问一次放行：刚才被收回的，连网关都不去碰，退回等待（“不确定”还原成发送之前的样子）\n"
            "        if (!mayTransmit()) return waiting(key, wasUncertain)\n"
            "        val address = when (val located = location.get(gateway)) {",
            "        source.submissionFor(key)\n"
            "        if (!mayTransmit()) return waiting(key, wasUncertain)\n"
            "        val address = when (val located = location.get(gateway)) {",
        ),),
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
        "评审 B1：落盘之后、联系网关之前还要再问一次放行（中间用户可能收回了授权）：少这一道，收回之后还会去连网关",
        (Edit(
            ENGINE,
            "        // 落盘之后、联系网关之前再问一次放行：刚才被收回的，连网关都不去碰，退回等待（“不确定”还原成发送之前的样子）\n"
            "        if (!mayTransmit()) return waiting(key, wasUncertain)\n",
            "",
        ),),
        sync_tests("DataFlowGateTest"), SYNC_RESULTS,
        ("问过之后、请求发出之前授权被收回",),
    ),
    Mutation(
        "policy-second-gate-missing",
        "评审 B1：取到要发的内容之后、真正发出之前还要再问一次放行（中间用户可能收回了授权）：少这一道，收回之后还会发出一个请求",
        (Edit(
            ENGINE,
            "        // 请求离开手机之前的最后一问：刚才被收回的，什么都没发出去，退回等待（“不确定”还原成发送之前的样子）\n"
            "        if (!mayTransmit()) return waiting(key, wasUncertain)\n",
            "",
        ),),
        sync_tests("DataFlowGateTest"), SYNC_RESULTS,
        ("联系过网关、取到要发的内容之后、请求发出之前授权被收回",),
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
        (Edit(POLICY_SQL, "        gatewayTextGranted = value(TEXT_GRANT)?.let(::isGrant) ?: false,", "        gatewayTextGranted = value(TEXT_GRANT)?.let(::isGrant) ?: true,"),),
        platform_tests("DataFlowPolicySqlTest"), PLATFORM_RESULTS,
        ("从没设置过：没有授权",),
    ),
    Mutation(
        "policy-unreadable-opens",
        "评审 B1：设置读不出来时必须按最保守的算（不授权、严格仅本地）：读不出来就放行是 fail-open",
        (Edit(POLICY_SQL, "    } catch (_: Exception) {\n        DataFlowState.Closed\n    }", "    } catch (_: Exception) {\n        DataFlowState(gatewayTextGranted = true, strictLocal = false)\n    }"),),
        platform_tests("DataFlowPolicySqlTest"), PLATFORM_RESULTS,
        ("读不出来（表不存在、连接坏了）",),
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
    Mutation(
        "b5-unreadable-keeps-credential",
        "评审 B5：用户退出去粘贴时应用在后台、系统不让读剪贴板：认不出归属就不清，凭证会一直留着",
        (Edit(CLIPBOARD_GUARD, "            is ClipboardPeek.Ours, ClipboardPeek.Unreadable -> clear()", "            is ClipboardPeek.Ours -> clear()\n            ClipboardPeek.Unreadable -> Unit"),),
        app_tests("SecretClipboardGuardTest"), APP_RESULTS,
        ("应用不在前台（系统不让读剪贴板）",),
    ),
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
        (Edit(MAIN_ACTIVITY, "        if (hasFocus) runtime.secretClipboard.onForeground()\n", ""),),
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
        (Edit(DESCRIPTIONS, '    "复制之后请在 ${SecretClipboardGuard.TIMEOUT_MS / 1_000} 秒内粘贴给 Agent', '    "复制之后请在 30 秒内粘贴给 Agent'),),
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
        (Edit(ENGINE, "        additions.useGateway(gateway.gatewayId)\n        val submission = source.submissionFor(key)", "        val submission = source.submissionFor(key)"),),
        sync_tests("GatewaySwitchTest"), SYNC_RESULTS,
        ("排着的文字不带旧网关的 Destination 编号去新网关",),
    ),
    Mutation(
        "h2-engine-additions-skip-alignment",
        "评审 H2：追加之前不对账：账上欠着的、给旧网关 Destination 的追加被发给了新网关",
        (Edit(ENGINE, "        if (gateway != null) additions.useGateway(gateway.gatewayId)\n", ""),),
        sync_tests("GatewaySwitchTest"), SYNC_RESULTS,
        ("旧网关的 Destination 编号不会被追加给新网关",),
    ),
    Mutation(
        "h2-switch-keeps-unsent-targets",
        "评审 H2：换网关只清了缓存、没撤回按旧网关规划的目标（原来的缺陷）：旧编号还留在账上，随时会被带去新网关",
        (Edit(DISPATCH_SQL, '        sql.update("DELETE FROM dispatch_targets WHERE delivery = ?", GatewayDelivery.NOT_YET.name)\n        if (gatewayId == null)', "        if (gatewayId == null)"),),
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
        (Edit(DISPATCH_SQL, '        sql.update("DELETE FROM dispatch_targets WHERE delivery = ?", GatewayDelivery.NOT_YET.name)\n        if (gatewayId == null)', '        sql.update("DELETE FROM dispatch_targets")\n        if (gatewayId == null)'),),
        platform_tests("SqliteDispatchStoreContractTest"), PLATFORM_RESULTS,
        ("using_another_gateway_withdraws_every_target_planned_for_the_old_one_but_keeps_what_already_happened",),
    ),
    Mutation(
        "h2-use-gateway-switches-every-time",
        "评审 H2：同一个网关每次对账也清一遍：引擎每次发请求前都会调用，缓存与账被反复抹掉",
        (Edit(DISPATCH_SQL, "        if (meta(GATEWAY_ID) == gatewayId) return@transaction false\n", "        if (false) return@transaction false\n"),),
        platform_tests("SqliteDispatchStoreContractTest"), PLATFORM_RESULTS,
        ("using_the_same_gateway_again_changes_nothing_and_does_not_move_the_change_signal",),
    ),
    Mutation(
        "h2-apply-destinations-keeps-old-targets",
        "评审 H2：用户先去发送页读到新网关的名单时，只清缓存、不撤回旧目标（原来的缺陷）",
        (Edit(DISPATCH_SQL, "        if (meta(GATEWAY_ID) != gatewayId) switchGateway(gatewayId)\n        if (authoritative) {", '        if (meta(GATEWAY_ID) != gatewayId) {\n            sql.update("DELETE FROM dispatch_destinations")\n            setMeta(GATEWAY_ID, gatewayId)\n        }\n        if (authoritative) {'),),
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
)

base.MUTATIONS = MUTATIONS


def main(argv: list[str]) -> int:
    return base.main(argv)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
