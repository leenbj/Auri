package app.auri.sync.testing

import app.auri.capture.contract.RevisionSource
import app.auri.sync.destinations.Choice
import app.auri.sync.destinations.DecideResult
import app.auri.sync.destinations.DispatchStore
import app.auri.sync.destinations.GatewayDelivery
import app.auri.sync.destinations.IntentResult
import app.auri.sync.destinations.TargetDecision
import app.auri.sync.engine.DeliveryAddition
import app.auri.sync.engine.JobKey
import app.auri.sync.engine.Refusal
import app.auri.sync.engine.SyncJobStore
import app.auri.sync.gateway.Destination
import app.auri.sync.gateway.Intent
import app.auri.sync.gateway.Publication
import app.auri.sync.gateway.SendMode
import app.auri.sync.machine.SyncState
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 发送的本地账所在的那一份“磁盘”，以及发布 Revision 时发生的事。 */
interface DispatchFixture {
    val store: DispatchStore

    /** 同步任务表（文字发布时一定登记；不等任何决定）。 */
    val jobs: SyncJobStore

    /** 发布一条 Revision 的那个事务里发生的事：登记同步任务、规划目标（Android：SqliteRecordLibrary 的 publicationStep）。 */
    suspend fun publish(key: JobKey, source: RevisionSource, now: Instant)

    /** 进程重启：同一份磁盘上的全新实例（打开数据库时的建表与回填都再跑一遍）。 */
    fun reopen(): DispatchFixture
}

/**
 * 发送的本地账（[DispatchStore]）的行为合同。内存实现（引擎与界面测试用）与 :syncplatform 的 SQLite 实现（这里在 JVM 上用真正的 SQLite 引擎跑）
 * 都要通过同一套：JVM 上验证过的“文字入库不等决定、预览确认的 Delivery 在确认后才欠、决定幂等、意图在构造提交时冻结、账重启后还在”，
 * 换到真正的数据库上语义不变。
 * 方法名只用普通标识符：继承它的子类的测试名要能被验收工具从源码里读出来。
 */
abstract class DispatchStoreContract {
    abstract fun newFixture(): DispatchFixture

    private val t0 = Instant.parse("2026-11-20T08:00:00.123Z")
    private val gateway = "fa1b98ec-1bfb-53b3-85a3-4d36b680fe6e"
    private val otherGateway = "0f0f0f0f-1bfb-53b3-85a3-4d36b680fe6e"
    private val auto = "65ea2953-505c-5c6f-9474-7cd5b8474104"
    private val preview = "9b1a23f8-5241-4e78-9d21-15c4c720c2c9"
    private val preview2 = "d214122a-4c32-4469-a0cc-24df2a128725"
    private val autoBoth = "3c6e0b8a-9f3d-4c1e-8a54-2f0d6c7e9a11"
    private val recordA = "a4a7c08d-2767-50d6-9238-6fa277033a49"
    private val recordB = "b5b8d19e-3878-5ae7-a349-7fb388144b5a"
    private val keyA = JobKey(recordA, 1)
    private val keyB = JobKey(recordB, 1)

    /** 网关告诉手机的凭证状态：从没签发过 / 有一条有效的 / 签发过但被撤销了、没有新的（= 暂停，评审 B3）。 */
    private enum class Cred { NEVER, ACTIVE, REVOKED }

    private fun destination(
        id: String,
        mode: SendMode,
        intents: Set<Intent> = setOf(Intent.CONTEXT),
        createdAt: Instant = t0,
        revokedAt: Instant? = null,
        name: String = "目的地-${id.take(4)}",
        credential: Cred = Cred.NEVER,
    ) = Destination(
        id, name, mode, intents, createdAt, "ef7b1358-df31-520f-90f3-d98fc5173b46",
        credentialIssued = credential != Cred.NEVER, revokedAt = revokedAt, credentialActive = credential == Cred.ACTIVE,
    )

    private suspend fun DispatchFixture.know(vararg destinations: Destination, authoritative: Boolean = true, gatewayId: String = gateway) =
        store.applyDestinations(gatewayId, destinations.toList(), authoritative, t0)

    private suspend fun DispatchFixture.hasJob(key: JobKey) = jobs.get(key) != null

    /** 同步引擎把这条文字送进了网关（任务 STORED）：此后账上才谈得上“还欠网关哪些 Delivery”。 */
    private suspend fun DispatchFixture.markStored(key: JobKey) {
        val job = jobs.get(key)!!
        jobs.update(job.copy(state = SyncState.STORED, attempts = job.attempts + 1, uncertain = false, retryAt = null, blockedReason = null, updatedAt = t0))
    }

    // ---- 意图标签的默认值 ----

    @Test
    fun the_default_intent_is_context_and_what_the_user_sets_survives_a_restart() = runBlocking {
        val fixture = newFixture()
        assertEquals("没有任何设置时默认是备忘，绝不是请求执行", Intent.CONTEXT, fixture.store.defaultIntent())

        fixture.store.setDefaultIntent(Intent.ACTION_REQUEST)

        assertEquals(Intent.ACTION_REQUEST, fixture.store.defaultIntent())
        assertEquals(Intent.ACTION_REQUEST, fixture.reopen().store.defaultIntent())
    }

    // ---- 自动发送：文字入库的同时就有目标，并标出意图 ----

    @Test
    fun an_auto_send_destination_is_planned_as_AUTO_and_the_job_exists_right_after_publication() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(auto, SendMode.AUTO_SEND))

        fixture.publish(keyA, RevisionSource.ASR, t0)

        assertTrue("自动发送的目标不需要等任何人：同步任务在发布的同一个事务里就有了", fixture.hasJob(keyA))
        val item = fixture.store.item(keyA)!!
        assertFalse("还没有为它构造过提交：标签没有冻结", item.intentLocked)
        assertEquals(Intent.CONTEXT, item.intent)
        assertEquals(listOf(TargetDecision.AUTO), item.targets.map { it.decision })
        assertTrue(item.targets.single().willReceive)
        assertFalse(item.awaitingUser)
        assertEquals(Publication(Intent.CONTEXT, listOf(auto)), fixture.store.publicationFor(keyA))
    }

    @Test
    fun the_intent_in_force_at_publication_is_what_the_item_carries() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(auto, SendMode.AUTO_SEND, setOf(Intent.CONTEXT, Intent.ACTION_REQUEST)))
        fixture.store.setDefaultIntent(Intent.ACTION_REQUEST)

        fixture.publish(keyA, RevisionSource.ASR, t0)

        assertEquals(Intent.ACTION_REQUEST, fixture.store.item(keyA)!!.intent)
        assertEquals(Publication(Intent.ACTION_REQUEST, listOf(auto)), fixture.store.publicationFor(keyA))
        // 之后改默认值，不影响已经发布的那一条
        fixture.store.setDefaultIntent(Intent.CONTEXT)
        assertEquals(Intent.ACTION_REQUEST, fixture.store.item(keyA)!!.intent)
    }

    // ---- 预览确认：文字照常入库；Delivery 在用户确认之后才欠网关 ----

    @Test
    fun a_preview_confirm_destination_does_not_hold_the_text_only_its_delivery_waits_for_the_user() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM))

        fixture.publish(keyA, RevisionSource.ASR, t0)

        assertTrue("预览确认的目的地不拦着文字：同步任务一定在，别的目标不会被它拖住", fixture.hasJob(keyA))
        val item = fixture.store.item(keyA)!!
        assertTrue(item.awaitingUser)
        assertEquals(listOf(TargetDecision.AWAITING), item.targets.map { it.decision })
        assertEquals("没确认就不会为它建 Delivery：第一次提交里没有它", Publication(Intent.CONTEXT, emptyList()), fixture.store.publicationFor(keyA))
        fixture.markStored(keyA)
        assertEquals("没确认就没有欠网关的 Delivery，同步多少轮都不会替用户做决定", emptyList<DeliveryAddition>(), fixture.store.due())
    }

    @Test
    fun confirming_is_idempotent_and_the_delivery_becomes_due_once_the_text_is_on_the_gateway() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM))
        fixture.publish(keyA, RevisionSource.ASR, t0)

        val first = fixture.store.decide(keyA, preview, Choice.CONFIRM, t0.plusSeconds(5))
        val second = fixture.store.decide(keyA, preview, Choice.CONFIRM, t0.plusSeconds(6))
        val third = fixture.store.decide(keyA, preview, Choice.CONFIRM, t0.plusSeconds(7))

        assertEquals(DecideResult.Decided, first)
        assertEquals(DecideResult.AlreadyDecided(TargetDecision.CONFIRMED), second)
        assertEquals(DecideResult.AlreadyDecided(TargetDecision.CONFIRMED), third)
        assertEquals("确认不再创建同步任务：文字入库时就登记了，只有一条", listOf(keyA), fixture.jobs.all().map { it.key })
        assertEquals("文字还没到网关时，确认的目标随第一次提交一起走", Publication(Intent.CONTEXT, listOf(preview)), fixture.store.publicationFor(keyA))
        assertEquals("文字还没入库：第一次提交就带着它，没有什么要追加的", emptyList<DeliveryAddition>(), fixture.store.due())

        fixture.markStored(keyA)

        assertEquals("双击、重试只欠一份", listOf(DeliveryAddition(keyA, Intent.CONTEXT, preview)), fixture.store.due())
    }

    @Test
    fun a_target_confirmed_after_the_text_is_stored_is_due_for_the_same_immutable_revision() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM))
        fixture.publish(keyA, RevisionSource.ASR, t0)
        fixture.store.publicationFor(keyA) // 第一次提交已经构造（目标为空），意图就此冻结
        fixture.markStored(keyA)
        assertEquals(emptyList<DeliveryAddition>(), fixture.store.due())

        fixture.store.decide(keyA, preview, Choice.CONFIRM, t0.plusSeconds(60))

        assertEquals(listOf(DeliveryAddition(keyA, Intent.CONTEXT, preview)), fixture.store.due())
        assertEquals("追加的是同一个 Revision，意图是冻结的那个", 1, fixture.store.due().single().key.revision)
    }

    @Test
    fun skipping_a_preview_destination_never_makes_a_delivery_due() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM))
        fixture.publish(keyA, RevisionSource.ASR, t0)

        assertEquals(DecideResult.Decided, fixture.store.decide(keyA, preview, Choice.SKIP, t0))
        fixture.markStored(keyA)

        assertTrue("跳过也是决定：文字照常入库，只是不送给它", fixture.hasJob(keyA))
        assertEquals(Publication(Intent.CONTEXT, emptyList()), fixture.store.publicationFor(keyA))
        assertEquals(emptyList<DeliveryAddition>(), fixture.store.due())
        assertEquals(TargetDecision.SKIPPED, fixture.store.item(keyA)!!.targets.single().decision)
    }

    @Test
    fun a_decision_is_final_a_later_opposite_choice_changes_nothing() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM), destination(preview2, SendMode.PREVIEW_CONFIRM))
        fixture.publish(keyA, RevisionSource.ASR, t0)
        fixture.store.decide(keyA, preview, Choice.SKIP, t0)

        assertEquals(DecideResult.AlreadyDecided(TargetDecision.SKIPPED), fixture.store.decide(keyA, preview, Choice.CONFIRM, t0))

        assertEquals(TargetDecision.SKIPPED, fixture.store.item(keyA)!!.targets.first { it.destinationId == preview }.decision)
        assertEquals(TargetDecision.AWAITING, fixture.store.item(keyA)!!.targets.first { it.destinationId == preview2 }.decision)
    }

    @Test
    fun an_awaiting_preview_target_does_not_hold_the_auto_target_or_the_text() = runBlocking {
        // 评审 B2：自动发送的 Agent 不能被另一个目的地的用户确认拖住
        val fixture = newFixture()
        fixture.know(destination(auto, SendMode.AUTO_SEND, createdAt = t0), destination(preview, SendMode.PREVIEW_CONFIRM, createdAt = t0.plusSeconds(1)))

        fixture.publish(keyA, RevisionSource.ASR, t0)

        assertTrue(fixture.hasJob(keyA))
        assertEquals(listOf(TargetDecision.AUTO, TargetDecision.AWAITING), fixture.store.item(keyA)!!.targets.map { it.decision })
        assertEquals("第一次提交就带着自动的那个，不等预览的决定", Publication(Intent.CONTEXT, listOf(auto)), fixture.store.publicationFor(keyA))
        fixture.store.decide(keyA, preview, Choice.CONFIRM, t0)
        fixture.markStored(keyA)
        fixture.store.gatewayHas(keyA, listOf(auto)) // 入库的回答里看到了自动的那份
        assertEquals("预览确认的那份稍后追加，自动的不再问", listOf(DeliveryAddition(keyA, Intent.CONTEXT, preview)), fixture.store.due())
    }

    // ---- 目标快照：只对发布那一刻存在的目的地 ----

    @Test
    fun a_destination_created_after_publication_never_receives_the_older_item() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(auto, SendMode.AUTO_SEND))
        fixture.publish(keyA, RevisionSource.ASR, t0)

        fixture.know(destination(auto, SendMode.AUTO_SEND), destination(preview, SendMode.PREVIEW_CONFIRM), authoritative = true)

        assertEquals("新建的 Destination 不会收到更早录的文字", Publication(Intent.CONTEXT, listOf(auto)), fixture.store.publicationFor(keyA))
        assertEquals(listOf(auto), fixture.store.item(keyA)!!.targets.map { it.destinationId })
    }

    @Test
    fun targets_are_ordered_by_the_destination_creation_time_so_the_request_bytes_are_stable() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(preview, SendMode.AUTO_SEND, createdAt = t0.plusSeconds(9)), destination(auto, SendMode.AUTO_SEND, createdAt = t0))

        fixture.publish(keyA, RevisionSource.ASR, t0)

        assertEquals(listOf(auto, preview), fixture.store.publicationFor(keyA).destinationIds)
    }

    // ---- 被撤销、不接收这种意图的目的地：不拦着别人，也收不到 ----

    @Test
    fun revoking_the_awaited_destination_means_it_never_receives_the_text() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM))
        fixture.publish(keyA, RevisionSource.ASR, t0)

        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM, revokedAt = t0.plusSeconds(30)), authoritative = false)

        assertTrue(fixture.hasJob(keyA))
        assertEquals(Publication(Intent.CONTEXT, emptyList()), fixture.store.publicationFor(keyA))
        assertFalse(fixture.store.item(keyA)!!.targets.single().eligible)
        assertEquals(DecideResult.NotEligible, fixture.store.decide(keyA, preview, Choice.CONFIRM, t0))
        fixture.markStored(keyA)
        assertEquals(emptyList<DeliveryAddition>(), fixture.store.due())
    }

    @Test
    fun a_confirmed_destination_that_is_revoked_is_dropped_from_the_publication_and_is_never_due() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM), destination(preview2, SendMode.PREVIEW_CONFIRM, createdAt = t0.plusSeconds(1)))
        fixture.publish(keyA, RevisionSource.ASR, t0)
        fixture.store.decide(keyA, preview, Choice.CONFIRM, t0)

        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM, revokedAt = t0.plusSeconds(30)), authoritative = false)

        fixture.store.decide(keyA, preview2, Choice.CONFIRM, t0.plusSeconds(40))
        assertEquals("发给已撤销的 Destination 会被网关以 REQUEST_INVALID 整个拒收，所以不带它", listOf(preview2), fixture.store.publicationFor(keyA).destinationIds)
        fixture.markStored(keyA)
        assertEquals("追加也不会去要已撤销的 Destination 的 Delivery", listOf(DeliveryAddition(keyA, Intent.CONTEXT, preview2)), fixture.store.due())
    }

    @Test
    fun a_destination_revoked_after_the_confirmation_but_before_the_send_is_not_in_the_publication() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM))
        fixture.publish(keyA, RevisionSource.ASR, t0)
        fixture.store.decide(keyA, preview, Choice.CONFIRM, t0)
        assertEquals(listOf(preview), fixture.store.publicationFor(keyA).destinationIds)

        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM, revokedAt = t0.plusSeconds(30)), authoritative = false)

        assertEquals("撤销之后再发的那一次不能带上它", emptyList<String>(), fixture.store.publicationFor(keyA).destinationIds)
    }

    @Test
    fun a_destination_that_does_not_accept_the_intent_is_not_eligible_and_never_receives_it() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM, intents = setOf(Intent.CONTEXT)))
        fixture.store.setDefaultIntent(Intent.ACTION_REQUEST)

        fixture.publish(keyA, RevisionSource.ASR, t0)

        assertTrue(fixture.hasJob(keyA))
        assertFalse(fixture.store.item(keyA)!!.targets.single().eligible)
        assertEquals("不接收请求执行的 Destination 没有什么可确认的", DecideResult.NotEligible, fixture.store.decide(keyA, preview, Choice.CONFIRM, t0))
        assertEquals(emptyList<String>(), fixture.store.publicationFor(keyA).destinationIds)
    }

    @Test
    fun an_auto_send_destination_that_does_not_accept_the_intent_is_not_in_the_publication_and_does_not_block_the_others() = runBlocking {
        val fixture = newFixture()
        fixture.know(
            destination(auto, SendMode.AUTO_SEND, intents = setOf(Intent.CONTEXT)),
            destination(autoBoth, SendMode.AUTO_SEND, intents = setOf(Intent.CONTEXT, Intent.ACTION_REQUEST), createdAt = t0.plusSeconds(1)),
        )
        fixture.store.setDefaultIntent(Intent.ACTION_REQUEST)

        fixture.publish(keyA, RevisionSource.ASR, t0)

        assertTrue("没有要等用户的目标，同步任务马上就有", fixture.hasJob(keyA))
        // 授权了自动发送、却不接收这个标签的目的地：放进去网关会把整条 422 永久拒收，文字就丢在手机上了
        assertEquals(listOf(autoBoth), fixture.store.publicationFor(keyA).destinationIds)
    }

    // ---- 意图标签：构造提交之前可以改，构造提交的那一刻冻结（评审 M3）----

    @Test
    fun the_intent_can_be_changed_until_the_submission_is_built_and_the_submission_carries_the_changed_value() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM, intents = setOf(Intent.CONTEXT)))
        fixture.publish(keyA, RevisionSource.ASR, t0)

        assertEquals(IntentResult.Changed, fixture.store.setIntent(keyA, Intent.ACTION_REQUEST, t0))

        val item = fixture.store.item(keyA)!!
        assertEquals(Intent.ACTION_REQUEST, item.intent)
        assertFalse("文字已经登记了同步任务，但引擎还没为它构造提交：标签还能改", item.intentLocked)
        assertEquals("提交带的就是改后的标签", Publication(Intent.ACTION_REQUEST, emptyList()), fixture.store.publicationFor(keyA))
    }

    @Test
    fun building_the_submission_freezes_the_intent_so_a_later_change_is_refused_and_the_frozen_value_stays() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(auto, SendMode.AUTO_SEND, setOf(Intent.CONTEXT, Intent.ACTION_REQUEST)))
        fixture.publish(keyA, RevisionSource.ASR, t0)

        val built = fixture.store.publicationFor(keyA)

        assertTrue("构造提交就是冻结：此后标签不可更改", fixture.store.item(keyA)!!.intentLocked)
        assertEquals(IntentResult.Locked, fixture.store.setIntent(keyA, Intent.ACTION_REQUEST, t0))
        assertEquals("网关上这一版的意图不可更改，本地也不改", Intent.CONTEXT, fixture.store.item(keyA)!!.intent)
        assertEquals("再次构造（重试）带的还是冻结的那一个，请求字节不变", built, fixture.store.publicationFor(keyA))
        assertEquals(IntentResult.NotFound, fixture.store.setIntent(keyB, Intent.ACTION_REQUEST, t0))
    }

    @Test
    fun an_item_whose_text_is_already_registered_for_sync_can_still_change_its_intent_before_the_first_send() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(auto, SendMode.AUTO_SEND, setOf(Intent.CONTEXT, Intent.ACTION_REQUEST)))
        fixture.publish(keyA, RevisionSource.ASR, t0)

        assertTrue("文字入库不等决定，任务一开始就在", fixture.hasJob(keyA))
        assertEquals("没网、等网关、排队时，意图还能改（旧实现在这时就已经把它锁死了）", IntentResult.Changed, fixture.store.setIntent(keyA, Intent.ACTION_REQUEST, t0))
        assertEquals(IntentResult.Unchanged, fixture.store.setIntent(keyA, Intent.ACTION_REQUEST, t0))
        assertEquals(Publication(Intent.ACTION_REQUEST, listOf(auto)), fixture.store.publicationFor(keyA))
    }

    @Test
    fun changing_the_intent_to_one_the_awaited_destination_accepts_keeps_it_awaiting() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM, intents = setOf(Intent.CONTEXT, Intent.ACTION_REQUEST)))
        fixture.publish(keyA, RevisionSource.ASR, t0)

        assertEquals(IntentResult.Changed, fixture.store.setIntent(keyA, Intent.ACTION_REQUEST, t0))

        assertTrue(fixture.store.item(keyA)!!.awaitingUser)
        fixture.store.decide(keyA, preview, Choice.CONFIRM, t0)
        assertEquals(Publication(Intent.ACTION_REQUEST, listOf(preview)), fixture.store.publicationFor(keyA))
    }

    // ---- 不是 ASR 初稿的、没规划过的：只入库 ----

    @Test
    fun a_user_edit_revision_is_never_planned_or_held_it_is_only_stored() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(auto, SendMode.AUTO_SEND), destination(preview, SendMode.PREVIEW_CONFIRM, createdAt = t0.plusSeconds(1)))

        fixture.publish(JobKey(recordA, 2), RevisionSource.USER_EDIT, t0)

        assertTrue("用户修订不自动重发（ADR-0009），也不等任何确认：照常入库", fixture.hasJob(JobKey(recordA, 2)))
        assertNull(fixture.store.item(JobKey(recordA, 2)))
        assertEquals(Publication.StoreOnly, fixture.store.publicationFor(JobKey(recordA, 2)))
    }

    @Test
    fun a_later_asr_revision_such_as_a_retranscription_is_only_stored_never_sent_again() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(auto, SendMode.AUTO_SEND), destination(preview, SendMode.PREVIEW_CONFIRM, createdAt = t0.plusSeconds(1)))

        fixture.publish(JobKey(recordA, 2), RevisionSource.ASR, t0)

        assertTrue("自动发送的只是 ASR 初稿（ADR-0009）：重新转录出的新版本只入库，也不拦着", fixture.hasJob(JobKey(recordA, 2)))
        assertNull(fixture.store.item(JobKey(recordA, 2)))
        assertEquals(Publication.StoreOnly, fixture.store.publicationFor(JobKey(recordA, 2)))
    }

    @Test
    fun an_item_that_was_never_planned_such_as_one_from_before_the_upgrade_is_only_stored() = runBlocking {
        val fixture = newFixture()

        assertEquals(Publication.StoreOnly, fixture.store.publicationFor(keyA))
        assertNull(fixture.store.item(keyA))
    }

    // ---- 合同上限：一次提交最多带这么多个目标，多出来的由追加补上，一个也不丢（评审 M4）----

    @Test
    fun every_active_destination_is_a_target_none_is_dropped_but_one_submission_carries_at_most_the_contract_limit() = runBlocking {
        val fixture = newFixture()
        val many = (0..Publication.MAX_DESTINATIONS).map { index ->
            destination("d0000000-0000-4000-8000-%012x".format(index), SendMode.AUTO_SEND, createdAt = t0.plusSeconds(index.toLong()))
        }
        fixture.know(*many.toTypedArray())

        fixture.publish(keyA, RevisionSource.ASR, t0)

        val item = fixture.store.item(keyA)!!
        assertEquals("有效的目的地一个也不能悄悄丢：全都是目标", many.map { it.destinationId }, item.targets.map { it.destinationId })
        val carried = fixture.store.publicationFor(keyA).destinationIds
        assertEquals("合同：一次提交最多 ${Publication.MAX_DESTINATIONS} 个目标，先创建的先带", many.take(Publication.MAX_DESTINATIONS).map { it.destinationId }, carried)
        assertTrue("没有要等的决定：同步任务照常有", fixture.hasJob(keyA))
        fixture.markStored(keyA)
        fixture.store.gatewayHas(keyA, carried)
        assertEquals("带不下的那一个由追加补上", listOf(DeliveryAddition(keyA, Intent.CONTEXT, many.last().destinationId)), fixture.store.due())
    }

    // ---- 目的地缓存 ----

    @Test
    fun a_complete_list_from_the_gateway_forgets_destinations_it_no_longer_has() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(auto, SendMode.AUTO_SEND), destination(preview, SendMode.PREVIEW_CONFIRM, createdAt = t0.plusSeconds(1)))
        fixture.publish(keyA, RevisionSource.ASR, t0)

        fixture.know(destination(auto, SendMode.AUTO_SEND))

        assertEquals(listOf(auto), fixture.store.destinations().map { it.destinationId })
        val gone = fixture.store.item(keyA)!!.targets.first { it.destinationId == preview }
        assertNull(gone.destinationName)
        assertFalse(gone.eligible)
        assertEquals(Publication(Intent.CONTEXT, listOf(auto)), fixture.store.publicationFor(keyA))
    }

    @Test
    fun an_incremental_update_adds_or_replaces_only_the_destinations_it_names() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(auto, SendMode.AUTO_SEND))

        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM, createdAt = t0.plusSeconds(1)), authoritative = false)

        assertEquals(listOf(auto, preview), fixture.store.destinations().map { it.destinationId })
    }

    @Test
    fun a_different_gateway_replaces_the_whole_cache() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(auto, SendMode.AUTO_SEND))

        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM), authoritative = false, gatewayId = otherGateway)

        assertEquals("换了网关，旧网关的 Destination 不能留在缓存里（增量更新也一样）", listOf(preview), fixture.store.destinations().map { it.destinationId })
    }

    @Test
    fun after_forgetting_the_cache_new_items_get_no_targets_and_are_only_stored() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(auto, SendMode.AUTO_SEND))
        fixture.store.forgetDestinations(t0)

        fixture.publish(keyA, RevisionSource.ASR, t0)

        assertEquals(emptyList<Destination>(), fixture.store.destinations())
        assertTrue(fixture.hasJob(keyA))
        assertEquals(emptyList<String>(), fixture.store.item(keyA)!!.targets.map { it.destinationId })
    }

    // ---- 凭证被撤销 = Destination 暂停（评审 B3）：网关不为它建新 Delivery、撤销时排着的取消了、恢复之后也不补发 ----

    @Test
    fun learning_that_a_destination_is_suspended_refuses_what_the_gateway_does_not_have_yet_and_a_later_resume_does_not_bring_it_back() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(auto, SendMode.AUTO_SEND, credential = Cred.ACTIVE), destination(preview, SendMode.PREVIEW_CONFIRM, credential = Cred.ACTIVE))
        fixture.publish(keyA, RevisionSource.ASR, t0)
        fixture.store.decide(keyA, preview, Choice.CONFIRM, t0)
        fixture.markStored(keyA)
        assertEquals("暂停之前：两个目标的 Delivery 都欠网关", setOf(auto, preview), fixture.store.due().map { it.destinationId }.toSet())

        fixture.know(destination(auto, SendMode.AUTO_SEND, credential = Cred.REVOKED), destination(preview, SendMode.PREVIEW_CONFIRM, credential = Cred.ACTIVE))

        val suspended = fixture.store.item(keyA)!!.targets.first { it.destinationId == auto }
        assertEquals(GatewayDelivery.REFUSED, suspended.delivery)
        assertEquals(Refusal.SUSPENDED, suspended.refusal)
        assertFalse("暂停的目的地不会收到新文字", suspended.eligible)
        assertEquals("没暂停的那个照旧欠着", listOf(preview), fixture.store.due().map { it.destinationId })

        fixture.know(destination(auto, SendMode.AUTO_SEND, credential = Cred.ACTIVE), destination(preview, SendMode.PREVIEW_CONFIRM, credential = Cred.ACTIVE))

        val resumed = fixture.store.item(keyA)!!.targets.first { it.destinationId == auto }
        assertEquals("恢复之后，暂停期间的文字也不补发（与网关的规则一致）", GatewayDelivery.REFUSED, resumed.delivery)
        assertEquals(Refusal.SUSPENDED, resumed.refusal)
        assertEquals(listOf(preview), fixture.store.due().map { it.destinationId })
        assertEquals("这份记忆重启之后还在", Refusal.SUSPENDED, fixture.reopen().store.item(keyA)!!.targets.first { it.destinationId == auto }.refusal)
        assertEquals("目的地恢复之后，构造提交时也不点名这个被拒绝过的目标", Publication(Intent.CONTEXT, listOf(preview)), fixture.store.publicationFor(keyA))
    }

    @Test
    fun a_suspended_destination_is_not_planned_for_new_texts_and_is_planned_again_for_the_texts_after_it_resumes() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(auto, SendMode.AUTO_SEND, credential = Cred.REVOKED), destination(preview, SendMode.PREVIEW_CONFIRM, credential = Cred.ACTIVE))

        fixture.publish(keyA, RevisionSource.ASR, t0)
        fixture.know(destination(auto, SendMode.AUTO_SEND, credential = Cred.ACTIVE), destination(preview, SendMode.PREVIEW_CONFIRM, credential = Cred.ACTIVE))
        fixture.publish(keyB, RevisionSource.ASR, t0.plusSeconds(60))

        assertEquals("暂停期间的文字没有给它的目标", listOf(preview), fixture.store.item(keyA)!!.targets.map { it.destinationId })
        assertEquals("恢复之后的文字有", setOf(auto, preview), fixture.store.item(keyB)!!.targets.map { it.destinationId }.toSet())
        assertEquals(Publication(Intent.CONTEXT, listOf(auto)), fixture.store.publicationFor(keyB))
        assertEquals("暂停期间的那条也没有被补上", listOf(preview), fixture.store.item(keyA)!!.targets.map { it.destinationId })
    }

    @Test
    fun a_target_of_a_suspended_destination_is_left_out_of_the_publication_even_if_the_decision_was_already_made() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(auto, SendMode.AUTO_SEND, credential = Cred.ACTIVE))
        fixture.publish(keyA, RevisionSource.ASR, t0)

        fixture.know(destination(auto, SendMode.AUTO_SEND, credential = Cred.REVOKED))

        assertEquals("规划时它还有效；提交时已经暂停：不点名它", Publication(Intent.CONTEXT, emptyList()), fixture.store.publicationFor(keyA))
    }

    @Test
    fun a_preview_target_of_a_suspended_destination_stops_asking_the_user_and_stays_closed_after_the_resume() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM, credential = Cred.ACTIVE))
        fixture.publish(keyA, RevisionSource.ASR, t0)
        assertTrue(fixture.store.item(keyA)!!.awaitingUser)

        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM, credential = Cred.REVOKED))

        assertFalse("暂停了：没什么可决定的", fixture.store.item(keyA)!!.awaitingUser)
        assertEquals(DecideResult.NotEligible, fixture.store.decide(keyA, preview, Choice.CONFIRM, t0))

        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM, credential = Cred.ACTIVE))

        assertFalse("恢复之后也不再问：暂停期间的文字不补发", fixture.store.item(keyA)!!.awaitingUser)
        assertEquals(DecideResult.NotEligible, fixture.store.decide(keyA, preview, Choice.CONFIRM, t0))
        fixture.markStored(keyA)
        assertEquals(emptyList<DeliveryAddition>(), fixture.store.due())
    }

    @Test
    fun a_delivery_the_gateway_already_has_is_not_rewritten_when_the_destination_is_found_suspended() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(auto, SendMode.AUTO_SEND, credential = Cred.ACTIVE))
        fixture.publish(keyA, RevisionSource.ASR, t0)
        fixture.markStored(keyA)
        fixture.store.gatewayHas(keyA, listOf(auto))

        fixture.know(destination(auto, SendMode.AUTO_SEND, credential = Cred.REVOKED))

        val target = fixture.store.item(keyA)!!.targets.single()
        assertEquals("网关上已经有的 Delivery 以网关的状态为准（撤销时它被取消了，状态查询会如实说）", GatewayDelivery.CREATED, target.delivery)
        assertNull(target.refusal)
    }

    @Test
    fun a_skipped_target_stays_skipped_when_its_destination_is_found_suspended() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM, credential = Cred.ACTIVE))
        fixture.publish(keyA, RevisionSource.ASR, t0)
        fixture.store.decide(keyA, preview, Choice.SKIP, t0)

        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM, credential = Cred.REVOKED))

        val target = fixture.store.item(keyA)!!.targets.single()
        assertEquals("用户的决定在先：界面说“你选择了不发送”，不改说暂停", TargetDecision.SKIPPED, target.decision)
        assertEquals(GatewayDelivery.NOT_YET, target.delivery)
    }

    @Test
    fun nothing_known_about_any_destination_means_the_item_is_only_stored_as_before_ticket_22() = runBlocking {
        val fixture = newFixture()

        fixture.publish(keyA, RevisionSource.ASR, t0)

        assertTrue(fixture.hasJob(keyA))
        assertEquals(Publication.StoreOnly, fixture.store.publicationFor(keyA))
    }

    // ---- 进程被杀 ----

    @Test
    fun an_awaiting_preview_target_keeps_waiting_after_a_restart_and_the_backfill_does_not_duplicate_the_job() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM))
        fixture.publish(keyA, RevisionSource.ASR, t0)

        val restarted = fixture.reopen()

        assertEquals("重启时的回填只补“有 Revision 却没有任务”的：这条本来就有任务，不会多一条", listOf(keyA), restarted.jobs.all().map { it.key })
        assertTrue(restarted.store.item(keyA)!!.awaitingUser)
        assertEquals(Publication(Intent.CONTEXT, emptyList()), restarted.store.publicationFor(keyA))
    }

    @Test
    fun a_decision_and_the_ledger_of_what_the_gateway_has_survive_a_restart() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(auto, SendMode.AUTO_SEND, createdAt = t0), destination(preview, SendMode.PREVIEW_CONFIRM, createdAt = t0.plusSeconds(1)))
        fixture.publish(keyA, RevisionSource.ASR, t0)
        fixture.store.decide(keyA, preview, Choice.CONFIRM, t0)
        fixture.store.publicationFor(keyA)
        fixture.markStored(keyA)
        fixture.store.gatewayHas(keyA, listOf(auto))

        val restarted = fixture.reopen()
        val again = restarted.store.decide(keyA, preview, Choice.CONFIRM, t0.plusSeconds(60))

        assertEquals(DecideResult.AlreadyDecided(TargetDecision.CONFIRMED), again)
        assertEquals("重启后账上仍然记得：自动的网关已有，只欠预览确认的那一份", listOf(DeliveryAddition(keyA, Intent.CONTEXT, preview)), restarted.store.due())
        assertTrue("冻结的意图也是落了盘的", restarted.store.item(keyA)!!.intentLocked)
        restarted.store.gatewayHas(keyA, listOf(preview))
        assertEquals("记下网关已有之后再重启，不会再欠", emptyList<DeliveryAddition>(), restarted.reopen().store.due())
    }

    @Test
    fun deciding_something_that_does_not_exist_reports_not_found_and_changes_nothing() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM))
        fixture.publish(keyA, RevisionSource.ASR, t0)

        assertEquals(DecideResult.NotFound, fixture.store.decide(keyB, preview, Choice.CONFIRM, t0))
        assertEquals(DecideResult.NotFound, fixture.store.decide(keyA, auto, Choice.CONFIRM, t0))
        assertEquals(TargetDecision.AWAITING, fixture.store.item(keyA)!!.targets.single().decision)
    }

    @Test
    fun an_auto_target_has_nothing_to_decide() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(auto, SendMode.AUTO_SEND))
        fixture.publish(keyA, RevisionSource.ASR, t0)

        assertEquals(DecideResult.AlreadyDecided(TargetDecision.AUTO), fixture.store.decide(keyA, auto, Choice.SKIP, t0))

        assertEquals("用户在界面上没有“拒绝自动发送”的入口，这里也不能被绕过去改掉授权", Publication(Intent.CONTEXT, listOf(auto)), fixture.store.publicationFor(keyA))
    }

    // ---- 网关上有没有 Delivery 的账 ----

    @Test
    fun nothing_is_due_before_the_text_is_stored_on_the_gateway() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(auto, SendMode.AUTO_SEND))
        fixture.publish(keyA, RevisionSource.ASR, t0)

        assertEquals("文字还没到网关：第一次提交会带着目标，不需要追加", emptyList<DeliveryAddition>(), fixture.store.due())
        fixture.markStored(keyA)
        assertEquals("入库了而账上还没记下网关有它的 Delivery：问一次（网关上幂等，答完就记下）", listOf(DeliveryAddition(keyA, Intent.CONTEXT, auto)), fixture.store.due())
    }

    @Test
    fun the_gateway_ledger_is_per_target_and_recording_it_is_idempotent() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(auto, SendMode.AUTO_SEND, createdAt = t0), destination(autoBoth, SendMode.AUTO_SEND, createdAt = t0.plusSeconds(1)))
        fixture.publish(keyA, RevisionSource.ASR, t0)
        fixture.markStored(keyA)

        fixture.store.gatewayHas(keyA, listOf(auto))
        fixture.store.gatewayHas(keyA, listOf(auto))

        assertEquals(listOf(DeliveryAddition(keyA, Intent.CONTEXT, autoBoth)), fixture.store.due())
        assertEquals(
            listOf(GatewayDelivery.CREATED, GatewayDelivery.NOT_YET),
            fixture.store.item(keyA)!!.targets.map { it.delivery },
        )
        fixture.store.gatewayHas(keyA, listOf("00000000-0000-4000-8000-000000000000")) // 不认识的目标忽略
        fixture.store.gatewayHas(keyB, listOf(auto)) // 不认识的文字忽略
        assertEquals(listOf(DeliveryAddition(keyA, Intent.CONTEXT, autoBoth)), fixture.store.due())
    }

    @Test
    fun a_refused_target_is_never_due_again_and_the_item_says_so() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM))
        fixture.publish(keyA, RevisionSource.ASR, t0)
        fixture.store.decide(keyA, preview, Choice.CONFIRM, t0)
        fixture.markStored(keyA)
        assertEquals(1, fixture.store.due().size)

        fixture.store.refused(keyA, listOf(preview), Refusal.REJECTED)

        assertEquals(emptyList<DeliveryAddition>(), fixture.store.due())
        assertEquals(GatewayDelivery.REFUSED, fixture.store.item(keyA)!!.targets.single().delivery)
        assertEquals("拒绝的记录重启后还在", emptyList<DeliveryAddition>(), fixture.reopen().store.due())
    }

    @Test
    fun a_refusal_never_overwrites_what_the_gateway_already_has_but_a_later_gateway_answer_overrides_a_refusal() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(auto, SendMode.AUTO_SEND))
        fixture.publish(keyA, RevisionSource.ASR, t0)
        fixture.markStored(keyA)
        fixture.store.gatewayHas(keyA, listOf(auto))

        fixture.store.refused(keyA, listOf(auto), Refusal.NOT_CREATED)
        assertEquals("网关明明有它：迟到的拒绝不能改写", GatewayDelivery.CREATED, fixture.store.item(keyA)!!.targets.single().delivery)

        val other = newFixture()
        other.know(destination(auto, SendMode.AUTO_SEND))
        other.publish(keyA, RevisionSource.ASR, t0)
        other.store.refused(keyA, listOf(auto), Refusal.NOT_CREATED)
        other.store.gatewayHas(keyA, listOf(auto))
        assertEquals("之后网关的回答里看到了它：以网关为准", GatewayDelivery.CREATED, other.store.item(keyA)!!.targets.single().delivery)
    }

    @Test
    fun due_lists_the_oldest_item_first_and_each_items_targets_in_creation_order() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(auto, SendMode.AUTO_SEND, createdAt = t0), destination(autoBoth, SendMode.AUTO_SEND, createdAt = t0.plusSeconds(1)))
        fixture.publish(keyA, RevisionSource.ASR, t0)
        fixture.publish(keyB, RevisionSource.ASR, t0.plusSeconds(10))
        fixture.markStored(keyB)
        fixture.markStored(keyA)

        assertEquals(
            listOf(
                DeliveryAddition(keyA, Intent.CONTEXT, auto),
                DeliveryAddition(keyA, Intent.CONTEXT, autoBoth),
                DeliveryAddition(keyB, Intent.CONTEXT, auto),
                DeliveryAddition(keyB, Intent.CONTEXT, autoBoth),
            ),
            fixture.store.due(),
        )
    }

    // ---- 列表与变化信号 ----

    @Test
    fun items_are_listed_newest_first_and_per_destination() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(auto, SendMode.AUTO_SEND), destination(preview, SendMode.PREVIEW_CONFIRM, createdAt = t0.plusSeconds(1)))
        fixture.publish(keyA, RevisionSource.ASR, t0)
        fixture.publish(keyB, RevisionSource.ASR, t0.plusSeconds(10))

        assertEquals(listOf(keyB, keyA), fixture.store.items(10).map { it.key })
        assertEquals("limit 生效", listOf(keyB), fixture.store.items(1).map { it.key })
        assertEquals(listOf(keyB, keyA), fixture.store.itemsFor(preview, 10).map { it.key })
        assertEquals(emptyList<JobKey>(), fixture.store.itemsFor("00000000-0000-4000-8000-000000000000", 10).map { it.key })
    }

    @Test
    fun every_kind_of_change_moves_the_change_signal() = runBlocking {
        val fixture = newFixture()
        var last = fixture.store.changes().first()

        suspend fun assertMoved(what: String, change: suspend () -> Unit) {
            change()
            val now = fixture.store.changes().first()
            assertTrue("$what 之后变化信号必须往前走（$last → $now）", now > last)
            last = now
        }

        assertMoved("缓存目的地") { fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM, setOf(Intent.CONTEXT, Intent.ACTION_REQUEST))) }
        assertMoved("发布新文字") { fixture.publish(keyA, RevisionSource.ASR, t0) }
        assertMoved("改意图") { fixture.store.setIntent(keyA, Intent.ACTION_REQUEST, t0) }
        assertMoved("做决定") { fixture.store.decide(keyA, preview, Choice.CONFIRM, t0) }
        assertMoved("构造提交（冻结意图）") { fixture.store.publicationFor(keyA) }
        assertMoved("记下网关已有它的 Delivery") { fixture.store.gatewayHas(keyA, listOf(preview)) }
        assertMoved("记下网关拒绝建 Delivery") { fixture.store.refused(keyA, listOf(preview), Refusal.REJECTED) }
        assertMoved("改默认意图") { fixture.store.setDefaultIntent(Intent.ACTION_REQUEST) }
        assertMoved("忘掉缓存") { fixture.store.forgetDestinations(t0) }
    }
}
