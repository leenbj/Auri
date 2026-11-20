package app.auri.sync.testing

import app.auri.capture.contract.RevisionSource
import app.auri.sync.destinations.Choice
import app.auri.sync.destinations.DecideResult
import app.auri.sync.destinations.DispatchStore
import app.auri.sync.destinations.IntentResult
import app.auri.sync.destinations.TargetDecision
import app.auri.sync.engine.JobKey
import app.auri.sync.engine.SyncJobStore
import app.auri.sync.gateway.Destination
import app.auri.sync.gateway.Intent
import app.auri.sync.gateway.Publication
import app.auri.sync.gateway.SendMode
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

    /** 同步任务表（发布时放行的、或用户的最后一个决定放行的任务都进这里）。 */
    val jobs: SyncJobStore

    /** 发布一条 Revision 的那个事务里发生的事：规划目标、必要时创建同步任务（Android：SqliteRecordLibrary 的 publicationStep）。 */
    suspend fun publish(key: JobKey, source: RevisionSource, now: Instant)

    /** 进程重启：同一份磁盘上的全新实例（打开数据库时的建表与回填都再跑一遍）。 */
    fun reopen(): DispatchFixture
}

/**
 * 发送的本地账（[DispatchStore]）的行为合同。内存实现（引擎与界面测试用）与 :syncplatform 的 SQLite 实现（这里在 JVM 上用真正的 SQLite 引擎跑）
 * 都要通过同一套：JVM 上验证过的“预览确认之前不放行、决定幂等、重启后仍然拦着”，换到真正的数据库上语义不变。
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
    private val recordA = "a4a7c08d-2767-50d6-9238-6fa277033a49"
    private val recordB = "b5b8d19e-3878-5ae7-a349-7fb388144b5a"
    private val keyA = JobKey(recordA, 1)
    private val keyB = JobKey(recordB, 1)

    private fun destination(
        id: String,
        mode: SendMode,
        intents: Set<Intent> = setOf(Intent.CONTEXT),
        createdAt: Instant = t0,
        revokedAt: Instant? = null,
        name: String = "目的地-${id.take(4)}",
    ) = Destination(id, name, mode, intents, createdAt, "ef7b1358-df31-520f-90f3-d98fc5173b46", credentialIssued = false, revokedAt = revokedAt)

    private suspend fun DispatchFixture.know(vararg destinations: Destination, authoritative: Boolean = true, gatewayId: String = gateway) =
        store.applyDestinations(gatewayId, destinations.toList(), authoritative, t0)

    private suspend fun DispatchFixture.hasJob(key: JobKey) = jobs.get(key) != null

    // ---- 意图标签的默认值 ----

    @Test
    fun the_default_intent_is_context_and_what_the_user_sets_survives_a_restart() = runBlocking {
        val fixture = newFixture()
        assertEquals("没有任何设置时默认是备忘，绝不是请求执行", Intent.CONTEXT, fixture.store.defaultIntent())

        fixture.store.setDefaultIntent(Intent.ACTION_REQUEST)

        assertEquals(Intent.ACTION_REQUEST, fixture.store.defaultIntent())
        assertEquals(Intent.ACTION_REQUEST, fixture.reopen().store.defaultIntent())
    }

    // ---- 自动发送：发布时就放行，并标出意图与目标 ----

    @Test
    fun an_auto_send_destination_is_planned_as_AUTO_and_the_job_exists_right_after_publication() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(auto, SendMode.AUTO_SEND))

        fixture.publish(keyA, RevisionSource.ASR, t0)

        assertTrue("自动发送的目标不需要等任何人：同步任务在发布的同一个事务里就有了", fixture.hasJob(keyA))
        val item = fixture.store.item(keyA)!!
        assertTrue(item.sent)
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

    // ---- 预览确认：用户确认之前，没有同步任务，也就没有 Delivery ----

    @Test
    fun a_preview_confirm_destination_holds_the_item_so_no_job_and_no_delivery_exist_before_the_user_confirms() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM))

        fixture.publish(keyA, RevisionSource.ASR, t0)

        assertFalse("预览确认：用户确认之前不能有同步任务（没有任务就没有请求，网关上也就没有 Delivery）", fixture.hasJob(keyA))
        val item = fixture.store.item(keyA)!!
        assertFalse(item.sent)
        assertTrue(item.awaitingUser)
        assertEquals(listOf(TargetDecision.AWAITING), item.targets.map { it.decision })
        assertEquals("没确认就不会送给它", Publication(Intent.CONTEXT, emptyList()), fixture.store.publicationFor(keyA))
    }

    @Test
    fun confirming_releases_the_job_exactly_once_and_repeated_confirms_change_nothing() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM))
        fixture.publish(keyA, RevisionSource.ASR, t0)

        val first = fixture.store.decide(keyA, preview, Choice.CONFIRM, t0.plusSeconds(5))
        val second = fixture.store.decide(keyA, preview, Choice.CONFIRM, t0.plusSeconds(6))
        val third = fixture.store.decide(keyA, preview, Choice.CONFIRM, t0.plusSeconds(7))

        assertEquals(DecideResult.Decided(releasedForSending = true), first)
        assertEquals(DecideResult.AlreadyDecided(TargetDecision.CONFIRMED), second)
        assertEquals(DecideResult.AlreadyDecided(TargetDecision.CONFIRMED), third)
        assertEquals("双击、重试只放行一次：只有一条同步任务", listOf(keyA), fixture.jobs.all().map { it.key })
        assertEquals("放行那一刻的时间，不是后来重复点击的时间", t0.plusSeconds(5), fixture.jobs.get(keyA)!!.createdAt)
        assertEquals(Publication(Intent.CONTEXT, listOf(preview)), fixture.store.publicationFor(keyA))
        assertTrue(fixture.store.item(keyA)!!.sent)
    }

    @Test
    fun skipping_the_only_awaiting_destination_releases_the_item_for_storing_without_delivery() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM))
        fixture.publish(keyA, RevisionSource.ASR, t0)

        assertEquals(DecideResult.Decided(releasedForSending = true), fixture.store.decide(keyA, preview, Choice.SKIP, t0))

        assertTrue("跳过也是决定：文字照常入库，只是不送给它", fixture.hasJob(keyA))
        assertEquals(Publication(Intent.CONTEXT, emptyList()), fixture.store.publicationFor(keyA))
        assertEquals(TargetDecision.SKIPPED, fixture.store.item(keyA)!!.targets.single().decision)
    }

    @Test
    fun a_decision_is_final_a_later_opposite_choice_changes_nothing() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM), destination(preview2, SendMode.PREVIEW_CONFIRM))
        fixture.publish(keyA, RevisionSource.ASR, t0)
        fixture.store.decide(keyA, preview, Choice.SKIP, t0)

        assertEquals(DecideResult.AlreadyDecided(TargetDecision.SKIPPED), fixture.store.decide(keyA, preview, Choice.CONFIRM, t0))

        assertEquals("还在等另一个目标，所以仍然没放行", false, fixture.hasJob(keyA))
        assertEquals(TargetDecision.SKIPPED, fixture.store.item(keyA)!!.targets.first { it.destinationId == preview }.decision)
    }

    @Test
    fun with_several_awaiting_destinations_the_item_is_released_only_after_the_last_decision() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM, createdAt = t0), destination(preview2, SendMode.PREVIEW_CONFIRM, createdAt = t0.plusSeconds(1)))
        fixture.publish(keyA, RevisionSource.ASR, t0)

        assertEquals(DecideResult.Decided(releasedForSending = false), fixture.store.decide(keyA, preview, Choice.CONFIRM, t0))
        assertFalse(fixture.hasJob(keyA))
        assertEquals(DecideResult.Decided(releasedForSending = true), fixture.store.decide(keyA, preview2, Choice.SKIP, t0))

        assertTrue(fixture.hasJob(keyA))
        assertEquals(Publication(Intent.CONTEXT, listOf(preview)), fixture.store.publicationFor(keyA))
    }

    @Test
    fun mixed_auto_and_preview_destinations_the_auto_one_also_waits_because_deliveries_cannot_be_added_later() = runBlocking {
        // 网关只在这一版第一次入库时创建 Delivery（缺口 G13）：先发给自动的、等确认后再补预览的，补不上。所以一起等，一起发。
        val fixture = newFixture()
        fixture.know(destination(auto, SendMode.AUTO_SEND, createdAt = t0), destination(preview, SendMode.PREVIEW_CONFIRM, createdAt = t0.plusSeconds(1)))

        fixture.publish(keyA, RevisionSource.ASR, t0)

        assertFalse(fixture.hasJob(keyA))
        assertEquals(listOf(TargetDecision.AUTO, TargetDecision.AWAITING), fixture.store.item(keyA)!!.targets.map { it.decision })
        fixture.store.decide(keyA, preview, Choice.CONFIRM, t0)
        assertEquals(Publication(Intent.CONTEXT, listOf(auto, preview)), fixture.store.publicationFor(keyA))
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
    fun revoking_the_awaited_destination_releases_the_item_and_it_never_reaches_the_revoked_destination() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM))
        fixture.publish(keyA, RevisionSource.ASR, t0)
        assertFalse(fixture.hasJob(keyA))

        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM, revokedAt = t0.plusSeconds(30)), authoritative = false)

        assertTrue("目的地被撤销后没有什么可等的：放行，只入库", fixture.hasJob(keyA))
        assertEquals(Publication(Intent.CONTEXT, emptyList()), fixture.store.publicationFor(keyA))
        assertFalse(fixture.store.item(keyA)!!.targets.single().eligible)
        assertEquals(DecideResult.NotEligible, fixture.store.decide(keyA, preview, Choice.CONFIRM, t0))
    }

    @Test
    fun a_confirmed_destination_that_is_revoked_while_another_one_is_still_awaited_is_dropped_from_the_publication() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM), destination(preview2, SendMode.PREVIEW_CONFIRM, createdAt = t0.plusSeconds(1)))
        fixture.publish(keyA, RevisionSource.ASR, t0)
        fixture.store.decide(keyA, preview, Choice.CONFIRM, t0)

        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM, revokedAt = t0.plusSeconds(30)), authoritative = false)

        assertFalse("第二个目的地还没决定，仍然拦着", fixture.hasJob(keyA))
        fixture.store.decide(keyA, preview2, Choice.CONFIRM, t0.plusSeconds(40))
        assertTrue(fixture.hasJob(keyA))
        assertEquals("发给已撤销的 Destination 会被网关以 REQUEST_INVALID 整个拒收，所以不带它", listOf(preview2), fixture.store.publicationFor(keyA).destinationIds)
    }

    @Test
    fun a_destination_revoked_after_the_release_but_before_the_send_is_not_in_the_publication() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM))
        fixture.publish(keyA, RevisionSource.ASR, t0)
        fixture.store.decide(keyA, preview, Choice.CONFIRM, t0)
        assertTrue(fixture.hasJob(keyA))
        assertEquals(listOf(preview), fixture.store.publicationFor(keyA).destinationIds)

        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM, revokedAt = t0.plusSeconds(30)), authoritative = false)

        assertEquals("同步任务已经在了，撤销之后再发的那一次不能带上它", emptyList<String>(), fixture.store.publicationFor(keyA).destinationIds)
    }

    @Test
    fun a_destination_that_does_not_accept_the_intent_is_not_eligible_and_does_not_hold_the_item() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM, intents = setOf(Intent.CONTEXT)))
        fixture.store.setDefaultIntent(Intent.ACTION_REQUEST)

        fixture.publish(keyA, RevisionSource.ASR, t0)

        assertTrue("不接收请求执行的 Destination 没有什么可确认的", fixture.hasJob(keyA))
        assertFalse(fixture.store.item(keyA)!!.targets.single().eligible)
        assertEquals(DecideResult.NotEligible, fixture.store.decide(keyA, preview, Choice.CONFIRM, t0))
        assertEquals(emptyList<String>(), fixture.store.publicationFor(keyA).destinationIds)
    }

    // ---- 意图标签：交给同步之前可以改，之后不可以 ----

    @Test
    fun the_intent_can_be_changed_while_the_item_is_held_and_it_can_release_the_item() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM, intents = setOf(Intent.CONTEXT)))
        fixture.publish(keyA, RevisionSource.ASR, t0)
        assertFalse(fixture.hasJob(keyA))

        // 改成请求执行：这个目的地不收，没有什么可等的了
        assertEquals(IntentResult.Changed(releasedForSending = true), fixture.store.setIntent(keyA, Intent.ACTION_REQUEST, t0))

        assertTrue(fixture.hasJob(keyA))
        assertEquals(Intent.ACTION_REQUEST, fixture.store.item(keyA)!!.intent)
        assertEquals(Publication(Intent.ACTION_REQUEST, emptyList()), fixture.store.publicationFor(keyA))
    }

    @Test
    fun changing_the_intent_to_one_the_awaited_destination_accepts_keeps_the_item_held() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM, intents = setOf(Intent.CONTEXT, Intent.ACTION_REQUEST)))
        fixture.publish(keyA, RevisionSource.ASR, t0)

        assertEquals(IntentResult.Changed(releasedForSending = false), fixture.store.setIntent(keyA, Intent.ACTION_REQUEST, t0))
        assertEquals(IntentResult.Unchanged, fixture.store.setIntent(keyA, Intent.ACTION_REQUEST, t0))

        assertFalse(fixture.hasJob(keyA))
        fixture.store.decide(keyA, preview, Choice.CONFIRM, t0)
        assertEquals(Publication(Intent.ACTION_REQUEST, listOf(preview)), fixture.store.publicationFor(keyA))
    }

    @Test
    fun once_the_item_is_with_sync_its_intent_is_locked() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(auto, SendMode.AUTO_SEND, setOf(Intent.CONTEXT, Intent.ACTION_REQUEST)))
        fixture.publish(keyA, RevisionSource.ASR, t0)

        assertEquals(IntentResult.Locked, fixture.store.setIntent(keyA, Intent.ACTION_REQUEST, t0))

        assertEquals("网关上这一版的意图不可更改，本地也不改", Intent.CONTEXT, fixture.store.item(keyA)!!.intent)
        assertEquals(IntentResult.NotFound, fixture.store.setIntent(keyB, Intent.ACTION_REQUEST, t0))
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

    // ---- 合同上限 ----

    @Test
    fun a_text_never_has_more_targets_than_one_submission_may_carry() = runBlocking {
        val fixture = newFixture()
        val many = (0..Publication.MAX_DESTINATIONS).map { index ->
            destination("d0000000-0000-4000-8000-%012x".format(index), SendMode.AUTO_SEND, createdAt = t0.plusSeconds(index.toLong()))
        }
        fixture.know(*many.toTypedArray())

        fixture.publish(keyA, RevisionSource.ASR, t0)

        val item = fixture.store.item(keyA)!!
        assertEquals("合同：一次提交最多 ${Publication.MAX_DESTINATIONS} 个目标；超过的不规划，也不让发送卡死", Publication.MAX_DESTINATIONS, item.targets.size)
        assertEquals("先创建的先算", many.take(Publication.MAX_DESTINATIONS).map { it.destinationId }, item.targets.map { it.destinationId })
        assertEquals(Publication.MAX_DESTINATIONS, fixture.store.publicationFor(keyA).destinationIds.size)
        assertTrue("没有要等的决定：照常放行", fixture.hasJob(keyA))
    }

    // ---- 目的地缓存 ----

    @Test
    fun a_complete_list_from_the_gateway_forgets_destinations_it_no_longer_has() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(auto, SendMode.AUTO_SEND), destination(preview, SendMode.PREVIEW_CONFIRM, createdAt = t0.plusSeconds(1)))
        fixture.publish(keyA, RevisionSource.ASR, t0)
        assertFalse(fixture.hasJob(keyA))

        fixture.know(destination(auto, SendMode.AUTO_SEND))

        assertEquals(listOf(auto), fixture.store.destinations().map { it.destinationId })
        assertTrue("网关已经不认这个 Destination：没什么可等的，放行", fixture.hasJob(keyA))
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

    @Test
    fun nothing_known_about_any_destination_means_the_item_is_only_stored_as_before_ticket_22() = runBlocking {
        val fixture = newFixture()

        fixture.publish(keyA, RevisionSource.ASR, t0)

        assertTrue(fixture.hasJob(keyA))
        assertEquals(Publication.StoreOnly, fixture.store.publicationFor(keyA))
    }

    // ---- 进程被杀 ----

    @Test
    fun a_held_item_stays_held_after_a_restart_the_backfill_must_not_resurrect_its_job() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM))
        fixture.publish(keyA, RevisionSource.ASR, t0)

        val restarted = fixture.reopen()

        assertFalse("重启时的回填会给“有 Revision 却没有任务”的补任务——被拦住的文字不能因此被放行", restarted.hasJob(keyA))
        assertTrue(restarted.store.item(keyA)!!.awaitingUser)
    }

    @Test
    fun a_decision_survives_a_restart_and_confirming_again_afterwards_still_yields_one_job() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM))
        fixture.publish(keyA, RevisionSource.ASR, t0)
        fixture.store.decide(keyA, preview, Choice.CONFIRM, t0)

        val restarted = fixture.reopen()
        val again = restarted.store.decide(keyA, preview, Choice.CONFIRM, t0.plusSeconds(60))

        assertEquals(DecideResult.AlreadyDecided(TargetDecision.CONFIRMED), again)
        assertEquals(listOf(keyA), restarted.jobs.all().map { it.key })
        assertEquals(Publication(Intent.CONTEXT, listOf(preview)), restarted.store.publicationFor(keyA))
    }

    @Test
    fun deciding_something_that_does_not_exist_reports_not_found_and_changes_nothing() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(preview, SendMode.PREVIEW_CONFIRM))
        fixture.publish(keyA, RevisionSource.ASR, t0)

        assertEquals(DecideResult.NotFound, fixture.store.decide(keyB, preview, Choice.CONFIRM, t0))
        assertEquals(DecideResult.NotFound, fixture.store.decide(keyA, auto, Choice.CONFIRM, t0))
        assertFalse(fixture.hasJob(keyA))
    }

    @Test
    fun an_auto_target_has_nothing_to_decide() = runBlocking {
        val fixture = newFixture()
        fixture.know(destination(auto, SendMode.AUTO_SEND))
        fixture.publish(keyA, RevisionSource.ASR, t0)

        assertEquals(DecideResult.AlreadyDecided(TargetDecision.AUTO), fixture.store.decide(keyA, auto, Choice.SKIP, t0))

        assertEquals("用户在界面上没有“拒绝自动发送”的入口，这里也不能被绕过去改掉授权", Publication(Intent.CONTEXT, listOf(auto)), fixture.store.publicationFor(keyA))
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
        assertMoved("做决定") { fixture.store.decide(keyA, preview, Choice.SKIP, t0) }
        assertMoved("改默认意图") { fixture.store.setDefaultIntent(Intent.ACTION_REQUEST) }
        assertMoved("忘掉缓存") { fixture.store.forgetDestinations(t0) }
    }
}
