package app.auri.sync.revisions

import app.auri.capture.contract.RevisionSource
import app.auri.capture.contract.ReviewState
import app.auri.capture.coordinator.Clock
import app.auri.capture.testing.InMemoryRecordLibrary
import app.auri.capture.testing.Samples
import app.auri.sync.destinations.DeliveryView
import app.auri.sync.destinations.GatewayStage
import app.auri.sync.destinations.ResendResult
import app.auri.sync.discovery.GatewayDiscovery
import app.auri.sync.discovery.GatewayLocator
import app.auri.sync.engine.JobKey
import app.auri.sync.gateway.Destination
import app.auri.sync.gateway.Intent
import app.auri.sync.gateway.SendMode
import app.auri.sync.http.HttpExchangeFactory
import app.auri.sync.pairing.GatewayAddress
import app.auri.sync.testing.CollectingLog
import app.auri.sync.testing.InMemoryDataFlowPolicy
import app.auri.sync.testing.InMemoryDispatchFixture
import app.auri.sync.testing.InMemoryPairingStore
import app.auri.sync.testing.ScriptedExchange
import app.auri.sync.testing.SyncSamples
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-TXT-002 / ADR-0009 / SEC-003：
 * - 每次保存产生新的不可变 Revision，旧的可回看；
 * - 新 Revision 默认不自动重发；
 * - 展示哪些 Destination 收到过旧版本，由用户逐个确认重发；
 * - 日志不含正文、音频路径与凭证。
 */
class RecordRevisionServiceTest {
    private val now = Instant.parse("2026-11-24T10:00:00Z")
    private val clock = object : Clock {
        private var current = now
        override fun monotonicNs() = 0L
        override fun now(): Instant = current.also { current = current.plusSeconds(1) }
    }

    private object NoDiscovery : GatewayDiscovery {
        override suspend fun find(gatewayId: String): List<GatewayAddress> = emptyList()
    }

    private fun destination(id: String, name: String, mode: SendMode = SendMode.AUTO_SEND) = Destination(
        destinationId = id,
        displayName = name,
        sendMode = mode,
        allowedIntents = setOf(Intent.CONTEXT, Intent.ACTION_REQUEST),
        createdAt = now,
        createdByDeviceId = Samples.uuid(99),
        credentialIssued = true,
        revokedAt = null,
        credentialActive = true,
    )

    @Test
    fun `保存修订产生新不可变 Revision，旧版本保留且不自动重发；日志不含正文`() = runBlocking {
        val gateway = SyncSamples.pairedGateway()
        val pairing = InMemoryPairingStore(gateway)
        val policy = InMemoryDataFlowPolicy(gateway = gateway.identity).apply { grantTo(gateway.identity) }
        val fixture = InMemoryDispatchFixture()
        val destA = destination(Samples.uuid(10), "我的秘密代理A")
        fixture.store.applyDestinations(gateway.identity, listOf(destA), authoritative = true, now = now)
        val library = InMemoryRecordLibrary { rev ->
            runBlocking { fixture.publish(JobKey(rev.recordId, rev.number), rev.source, rev.createdAt) }
        }
        val entry = Samples.entry(1)
        library.addRecord(entry)
        val rev1 = library.publishRevision(Samples.draft(entry.recordId, text = "初稿秘密内容"))

        val log = CollectingLog()
        val http = HttpExchangeFactory { _ -> ScriptedExchange { _, _ -> error("编辑保存不应联网") } }
        val service = RecordRevisionService(
            library = library,
            dispatch = fixture.store,
            pairing = pairing,
            http = http,
            locator = GatewayLocator(http, NoDiscovery, log),
            policy = policy,
            clock = clock,
            log = log,
            deleteFile = { true },
            onSyncNeeded = {},
        )

        val secretEdit = "修订后的敏感正文12345"
        val outcome = service.saveEdit(entry.recordId, secretEdit)

        assertTrue(outcome is EditOutcome.Saved)
        val rev2 = (outcome as EditOutcome.Saved).revision
        assertEquals(2, rev2.number)
        assertEquals(RevisionSource.USER_EDIT, rev2.source)
        assertEquals(ReviewState.REVIEWED, rev2.reviewState)
        assertEquals(secretEdit, rev2.text)

        val stored = checkNotNull(library.get(entry.recordId))
        assertEquals(listOf(rev1, rev2), stored.revisions)
        assertEquals("旧版本一字不改", "初稿秘密内容", stored.revisions[0].text)

        val pub2 = fixture.store.publicationFor(JobKey(entry.recordId, 2))
        assertTrue("新 Revision 默认不自动重发", pub2.destinationIds.isEmpty())
        assertTrue("日志不得包含正文", log.lines.none { it.contains(secretEdit) || it.contains("初稿秘密内容") })
    }

    @Test
    fun `空白或超过 1 MiB 的编辑被拒绝，不产生新 Revision`() = runBlocking {
        val gateway = SyncSamples.pairedGateway()
        val fixture = InMemoryDispatchFixture()
        val library = InMemoryRecordLibrary()
        val entry = Samples.entry(1)
        library.addRecord(entry)
        library.publishRevision(Samples.draft(entry.recordId, text = "原文"))

        val http = HttpExchangeFactory { _ -> ScriptedExchange { _, _ -> error("不应联网") } }
        val service = RecordRevisionService(
            library = library,
            dispatch = fixture.store,
            pairing = InMemoryPairingStore(gateway),
            http = http,
            locator = GatewayLocator(http, NoDiscovery),
            policy = InMemoryDataFlowPolicy(gateway = gateway.identity),
            clock = clock,
        )

        assertEquals(EditOutcome.Invalid(EditError.EMPTY_TEXT), service.saveEdit(entry.recordId, ""))
        val tooLong = "啊".repeat(400_000) // > 1 MiB UTF-8
        assertEquals(EditOutcome.Invalid(EditError.TEXT_TOO_LONG), service.saveEdit(entry.recordId, tooLong))
        assertEquals(1, library.get(entry.recordId)!!.revisions.size)
    }

    @Test
    fun `查询收到过旧版本的 Destination 并逐个确认重发（只发用户选的，重复确认幂等）`() = runBlocking {
        val gateway = SyncSamples.pairedGateway()
        val pairing = InMemoryPairingStore(gateway)
        val policy = InMemoryDataFlowPolicy(gateway = gateway.identity).apply { grantTo(gateway.identity) }
        val fixture = InMemoryDispatchFixture()
        val destA = destination(Samples.uuid(10), "代理A")
        val destB = destination(Samples.uuid(11), "代理B")
        fixture.store.applyDestinations(gateway.identity, listOf(destA, destB), authoritative = true, now = now)
        val library = InMemoryRecordLibrary { rev ->
            runBlocking { fixture.publish(JobKey(rev.recordId, rev.number), rev.source, rev.createdAt) }
        }
        val entry = Samples.entry(1)
        library.addRecord(entry)
        val rev1 = library.publishRevision(Samples.draft(entry.recordId, text = "初稿"))
        fixture.store.gatewayHas(JobKey(entry.recordId, 1), listOf(destA.destinationId, destB.destinationId))

        var nudged = 0
        val log = CollectingLog()
        val http = HttpExchangeFactory { _ ->
            ScriptedExchange { _, req ->
                when (req.path) {
                    "/health" -> ScriptedExchange.json(200, """{"status":"ok"}""")
                    "/v1/records/${entry.recordId}/status" -> ScriptedExchange.json(
                        200,
                        """
                        {
                          "schema_version": "1.2",
                          "record_id": "${entry.recordId}",
                          "origin_device_id": "${gateway.deviceId}",
                          "deleted": false,
                          "deleted_at": null,
                          "latest_revision": 2,
                          "revisions": [
                            {"revision": 1, "state": "superseded", "text_sha256": "${rev1.textSha256}", "stored_at": "2026-11-24T10:00:00Z", "intent": "context"}
                          ],
                          "destinations": [
                            {"destination_id": "${destA.destinationId}", "destination_display_name": "代理A", "delivery_id": "${Samples.uuid(81)}", "revision": 1, "delivery_state": "ACKNOWLEDGED", "stored_at": "2026-11-24T10:00:00Z", "lease_expires_at": null, "acknowledged_at": "2026-11-24T10:01:00Z", "rejected_at": null, "cancelled_at": null},
                            {"destination_id": "${destB.destinationId}", "destination_display_name": "代理B", "delivery_id": "${Samples.uuid(82)}", "revision": 1, "delivery_state": "PENDING", "stored_at": "2026-11-24T10:00:00Z", "lease_expires_at": null, "acknowledged_at": null, "rejected_at": null, "cancelled_at": null}
                          ]
                        }
                        """.trimIndent(),
                    )
                    else -> error("unexpected ${req.path}")
                }
            }
        }
        val service = RecordRevisionService(
            library = library,
            dispatch = fixture.store,
            pairing = pairing,
            http = http,
            locator = GatewayLocator(http, NoDiscovery, log),
            policy = policy,
            clock = clock,
            log = log,
            onSyncNeeded = { nudged += 1 },
        )

        val saved = service.saveEdit(entry.recordId, "修订后的正文") as EditOutcome.Saved
        assertEquals(2, saved.revision.number)

        val recipients = service.olderVersionRecipients(entry.recordId, latestRevision = 2)
        assertEquals(2, recipients.size)
        val recA = recipients.single { it.destinationId == destA.destinationId }
        val recB = recipients.single { it.destinationId == destB.destinationId }
        assertEquals(1, recA.receivedRevision)
        assertEquals(DeliveryView.OnGateway(GatewayStage.ACKNOWLEDGED), recA.olderDeliveryView)
        assertEquals(ResendState.NOT_REQUESTED, recA.resendState)
        assertEquals(1, recB.receivedRevision)
        assertEquals(DeliveryView.OnGateway(GatewayStage.STORED), recB.olderDeliveryView)
        assertEquals(ResendState.NOT_REQUESTED, recB.resendState)

        // 只确认重发给代理 B
        val nudgedBeforeResend = nudged
        assertEquals(ResendResult.Requested, service.confirmResend(entry.recordId, 2, destB.destinationId))
        assertEquals(ResendResult.AlreadyRequested, service.confirmResend(entry.recordId, 2, destB.destinationId))
        assertEquals("仅首次确认触发一次同步，重复确认幂等不再触发", nudgedBeforeResend + 1, nudged)

        val pub2 = fixture.store.publicationFor(JobKey(entry.recordId, 2))
        assertEquals("只有用户确认的代理 B 进入重发列表", listOf(destB.destinationId), pub2.destinationIds)
        assertFalse("未确认的代理 A 绝不自动重发", destA.destinationId in pub2.destinationIds)
        assertTrue("日志不含目的地名与正文", log.lines.none { it.contains("代理A") || it.contains("代理B") || it.contains("修订后的正文") })
    }

    @Test
    fun `删除音频时文件删除失败不清空库元数据，重发沿用原记录意图并把拒收目标标为不可接收`() = runBlocking {
        val gateway = SyncSamples.pairedGateway()
        val fixture = InMemoryDispatchFixture()
        val onlyContext = Destination(
            Samples.uuid(20), "仅备忘代理", SendMode.AUTO_SEND, setOf(Intent.CONTEXT),
            now, Samples.uuid(99), credentialIssued = true, revokedAt = null, credentialActive = true,
        )
        val both = destination(Samples.uuid(21), "全意图代理")
        fixture.store.applyDestinations(gateway.identity, listOf(onlyContext, both), authoritative = true, now = now)
        val library = InMemoryRecordLibrary { rev ->
            runBlocking { fixture.publish(JobKey(rev.recordId, rev.number), rev.source, rev.createdAt) }
        }
        val entry = Samples.entry(5)
        library.addRecord(entry)
        fixture.store.setDefaultIntent(Intent.ACTION_REQUEST)
        library.publishRevision(Samples.draft(entry.recordId, text = "动作请求初稿"))
        fixture.store.gatewayHas(JobKey(entry.recordId, 1), listOf(both.destinationId))
        // 用户随后改了默认意图为 CONTEXT，但这条 Record 原意图是 ACTION_REQUEST
        fixture.store.setDefaultIntent(Intent.CONTEXT)

        var fileDeleteResult = false
        val http = HttpExchangeFactory { _ -> ScriptedExchange { _, _ -> ScriptedExchange.json(404, """{"code":"REQUEST_INVALID"}""") } }
        val service = RecordRevisionService(
            library = library,
            dispatch = fixture.store,
            pairing = InMemoryPairingStore(gateway),
            http = http,
            locator = GatewayLocator(http, NoDiscovery),
            policy = InMemoryDataFlowPolicy(gateway = gateway.identity),
            clock = clock,
            deleteFile = { fileDeleteResult },
        )

        // OCR #30：文件删不掉时不清空元数据
        assertFalse(service.deleteAudioOnly(entry.recordId))
        assertTrue(library.get(entry.recordId)!!.audio != null)
        fileDeleteResult = true
        assertTrue(service.deleteAudioOnly(entry.recordId))
        assertTrue(library.get(entry.recordId)!!.audio == null)

        // OCR #31/#32/#14：重发沿用原记录意图 ACTION_REQUEST，onlyContext 被拒
        service.saveEdit(entry.recordId, "修订稿")
        assertEquals(ResendResult.NotEligible, service.confirmResend(entry.recordId, 2, onlyContext.destinationId))
        assertEquals(ResendResult.Requested, service.confirmResend(entry.recordId, 2, both.destinationId))
        fixture.store.refused(JobKey(entry.recordId, 2), listOf(both.destinationId), app.auri.sync.engine.Refusal.REJECTED)
        val recipients = service.olderVersionRecipients(entry.recordId, latestRevision = 2, automatic = true)
        assertEquals(ResendState.NOT_ELIGIBLE, recipients.single { it.destinationId == both.destinationId }.resendState)
    }
}
