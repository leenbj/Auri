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
ENGINE = SYNC_ENGINE + "SyncEngine.kt"
POLICY = ANDROID + "sync/src/main/kotlin/app/auri/sync/policy/DataFlowPolicy.kt"
POLICY_SQL = SYNCPLATFORM + "policy/DataFlowPolicySql.kt"
RESUME_PLANNER = ANDROID + "sync/src/main/kotlin/app/auri/sync/resume/ResumePlanner.kt"
SYNC_STATUS = ANDROID + "sync/src/main/kotlin/app/auri/sync/status/SyncStatus.kt"
SEND_REVIEW_VM = APP_DEST + "SendReviewViewModel.kt"

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
PREVIEW_NOT_HELD = Edit(DISPATCH_STORE, "    val awaitingUser: Boolean get() = decision == TargetDecision.AWAITING && eligible", "    val awaitingUser: Boolean get() = false")
IGNORE_ELIGIBILITY = Edit(
    DISPATCH_STORE,
    "    val willReceive: Boolean get() = eligible && (decision == TargetDecision.AUTO || decision == TargetDecision.CONFIRMED)",
    "    val willReceive: Boolean get() = decision == TargetDecision.AUTO || decision == TargetDecision.CONFIRMED",
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

# 评审 B1：引擎问“文字放行了吗”的那一处——真网关测试与单测各跑一遍，用同一份编辑
POLICY_GATE_OPEN = Edit(ENGINE, "    private suspend fun mayTransmit(): Boolean = policy.current().textMayEnterGateway", "    private suspend fun mayTransmit(): Boolean = true")

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
        (Edit(DETAIL_VM, "    fun leave() {\n        generation++\n        wipeCredential()\n    }", "    fun leave() {\n        generation++\n    }"),),
        app_tests("DestinationDetailViewModelTest"), APP_RESULTS,
        ("凭证只显示一次", "等核验或网关的时候离开了页面"),
    ),
    Mutation(
        "vm-late-credential-after-leave-shown",
        "等核验或网关时页面离开了：之后才到的凭证不能再显示出来（没人看着，也不能留着）",
        (Edit(DETAIL_VM, "            if (mine != generation) return@launch // 等待期间页面离开了：没人看得到它，不显示，不留", "            // 变异：离开之后才到的凭证照样显示"),),
        app_tests("DestinationDetailViewModelTest"), APP_RESULTS,
        ("等核验或网关的时候离开了页面",),
    ),
    Mutation(
        "vm-backgrounded-keeps-credential",
        "应用退到后台时正在显示的凭证必须抹掉（回来时看到的和进程被杀后一样是空的）",
        (Edit(DETAIL_VM, "        if (credential.value is CredentialUi.Shown) wipeCredential()", "        Unit"),),
        app_tests("DestinationDetailViewModelTest"), APP_RESULTS,
        ("应用退到后台：正在显示的凭证抹掉",),
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
    # ---- 4. 预览确认：确认之前没有 Delivery；确认幂等；意图标签只是数据 ----
    # 拦住文字的逻辑在存储实现里（Android 上是 SQLite：DispatchSql.releaseIfReady 看 awaitingUser）；:sync 的测试与真网关测试用的是内存实现，
    # 它们的价值在“网关那一侧看到什么”，所以拦截本身的变异放在 :syncplatform 的 SQLite 合同测试上（JVM 里跑真 SQLite 引擎）。
    Mutation(
        "dispatch-preview-not-held",
        "预览确认的 Destination 在用户确认之前必须拦住这条文字（否则网关马上就为它建 Delivery）：等用户决定的目标必须被认出来",
        (PREVIEW_NOT_HELD,),
        platform_tests("SqliteDispatchStoreContractTest", "DispatchSqlTest"), PLATFORM_RESULTS,
        ("a_preview_confirm_destination_holds_the_item", "a_held_item_stays_held_after_a_restart"),
    ),
    Mutation(
        "dispatch-sql-preview-not-held",
        "Android 的 SQLite 发送账：预览确认的目标必须记成“等用户”，不能记成自动",
        (Edit(DISPATCH_SQL, "                    (if (destination.sendMode == SendMode.AUTO_SEND) TargetDecision.AUTO else TargetDecision.AWAITING).name,", "                    TargetDecision.AUTO.name,"),),
        platform_tests("SqliteDispatchStoreContractTest", "DispatchSqlTest"), PLATFORM_RESULTS,
        ("a_preview_confirm_destination_holds_the_item",),
    ),
    Mutation(
        "dispatch-sql-decide-not-idempotent",
        "重复确认必须被认出来（已经决定过）：不能当成新的决定再处理一遍",
        (Edit(DISPATCH_SQL, "        if (target.decision != TargetDecision.AWAITING) return@transaction DecideResult.AlreadyDecided(target.decision)\n", ""),),
        platform_tests("SqliteDispatchStoreContractTest"), PLATFORM_RESULTS,
        ("confirming_releases_the_job_exactly_once_and_repeated_confirms_change_nothing",),
    ),
    Mutation(
        "dispatch-ignores-eligibility",
        "不接收这种意图标签（或已撤销）的 Destination 不能出现在 publication 里（否则网关把整条 422 永久拒收）",
        (IGNORE_ELIGIBILITY,),
        platform_tests("SqliteDispatchStoreContractTest", "DispatchSqlTest"), PLATFORM_RESULTS,
        ("a_destination_revoked_after_the_release_but_before_the_send_is_not_in_the_publication", "an_auto_send_destination_that_does_not_accept_the_intent_is_not_in_the_publication"),
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
    Mutation(
        "real-gw-source-ignores-publication",
        "真网关：自动发送的 Destination 必须在同一次同步里收到待领取的投递",
        (IGNORE_PUBLICATION,),
        REAL, REAL_RESULTS,
        ("自动发送：转录完成后一轮同步",),
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
        "policy-second-gate-missing",
        "评审 B1：取到要发的内容之后、真正发出之前还要再问一次放行（中间用户可能收回了授权）：少这一道，收回之后还会发出一个请求",
        (Edit(ENGINE, "        if (!mayTransmit()) return waiting(key, wasUncertain)\n", ""),),
        sync_tests("DataFlowGateTest"), SYNC_RESULTS,
        ("问过之后、请求发出之前授权被收回",),
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
)

base.MUTATIONS = MUTATIONS


def main(argv: list[str]) -> int:
    return base.main(argv)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
