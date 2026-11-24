package app.auri.android.revisions

import app.auri.capture.coordinator.Clock
import app.auri.capture.library.AudioRetentionCleaner
import app.auri.capture.testing.InMemoryRecordLibrary
import app.auri.capture.testing.Samples
import app.auri.platform.storage.AudioRetentionSetting
import app.auri.sync.destinations.ResendResult
import app.auri.sync.discovery.GatewayDiscovery
import app.auri.sync.discovery.GatewayLocator
import app.auri.sync.engine.JobKey
import app.auri.sync.gateway.Destination
import app.auri.sync.gateway.Intent
import app.auri.sync.gateway.SendMode
import app.auri.sync.http.HttpExchangeFactory
import app.auri.sync.pairing.GatewayAddress
import app.auri.sync.revisions.ExportFormat
import app.auri.sync.revisions.RecordRevisionService
import app.auri.sync.revisions.SendStatusFilter
import app.auri.sync.testing.CollectingLog
import app.auri.sync.testing.InMemoryDataFlowPolicy
import app.auri.sync.testing.InMemoryDispatchFixture
import app.auri.sync.testing.InMemoryPairingStore
import app.auri.sync.testing.ScriptedExchange
import app.auri.sync.testing.SyncSamples
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 23 ViewModel 行为测试（FR-TXT-002 / FR-TXT-004 / FR-TXT-005）：
 * - 筛选（时间、关键词、发送状态）与用户主动触发 TXT/Markdown 导出（文件名不含正文）；
 * - 音频保留期默认 30 天，可修改并持久化；
 * - 详情页改字草稿不产生 Revision，保存才产生新 Revision 且不自动重发；
 * - 详情页明示“Agent 已读走的内容无法收回”，支持仅删音频与删除整条 Record。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RevisionsViewModelTest {
    private val now = Instant.parse("2026-11-24T12:00:00Z")
    private val clock = object : Clock {
        private var current = now
        override fun monotonicNs() = 0L
        override fun now() = current.also { current = current.plusSeconds(1) }
    }

    private object NoDiscovery : GatewayDiscovery {
        override suspend fun find(gatewayId: String): List<GatewayAddress> = emptyList()
    }

    @Test
    fun `列表页按关键词和发送状态筛选，导出由用户主动触发且文件名不含正文`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val library = InMemoryRecordLibrary()
        val fixture = InMemoryDispatchFixture()
        val e1 = Samples.entry(1, startedAt = now.minusSeconds(3600))
        val e2 = Samples.entry(2, startedAt = now)
        library.addRecord(e1)
        library.addRecord(e2)
        library.publishRevision(Samples.draft(e1.recordId, text = "秘密买咖啡"))
        library.publishRevision(Samples.draft(e2.recordId, text = "周会记录"))
        fixture.jobs.enqueue(JobKey(e1.recordId, 1), now)
        fixture.jobs.enqueue(JobKey(e2.recordId, 1), now)

        val dir = Files.createTempDirectory("auri-vm-ret").toFile()
        try {
            val retention = AudioRetentionSetting(File(dir, "retention"))
            val cleaner = AudioRetentionCleaner(library) { true }
            val vm = RecordsViewModel(library, fixture.jobs, fixture.store, retention, cleaner, clock, TestScope(dispatcher))
            advanceUntilIdle()

            assertEquals(2, vm.state.value.records.size)
            assertEquals(30, vm.state.value.retentionDays)
            assertNull("未点击导出时不产生导出数据", vm.state.value.pendingExport)

            vm.setKeyword("咖啡")
            advanceUntilIdle()
            assertEquals(listOf(e1.recordId), vm.state.value.records.map { it.recordId })

            vm.prepareExport(ExportFormat.MARKDOWN)
            advanceUntilIdle()
            val export = checkNotNull(vm.state.value.pendingExport)
            assertTrue(export.fileName.endsWith(".md"))
            assertFalse("文件名不含正文", export.fileName.contains("咖啡"))
            assertTrue(export.content.contains("秘密买咖啡"))

            vm.consumeExport()
            advanceUntilIdle()
            assertNull(vm.state.value.pendingExport)

            vm.setSendStatus(SendStatusFilter.STORED)
            advanceUntilIdle()
            assertTrue(vm.state.value.records.isEmpty())

            vm.setRetentionDays("14")
            advanceUntilIdle()
            assertEquals(14, vm.state.value.retentionDays)
            assertNull(vm.state.value.retentionError)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `详情页编辑草稿不产生 Revision，保存后产生第 2 版且旧版可回看，支持仅删音频和整条删除`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gateway = SyncSamples.pairedGateway()
        val fixture = InMemoryDispatchFixture()
        val destA = Destination(
            Samples.uuid(10), "代理A", SendMode.AUTO_SEND, setOf(Intent.CONTEXT),
            now, Samples.uuid(99), credentialIssued = true, revokedAt = null, credentialActive = true,
        )
        fixture.store.applyDestinations(gateway.identity, listOf(destA), authoritative = true, now = now)
        val library = InMemoryRecordLibrary { rev ->
            runBlocking { fixture.publish(JobKey(rev.recordId, rev.number), rev.source, rev.createdAt) }
        }
        val entry = Samples.entry(1, startedAt = now)
        library.addRecord(entry)
        library.publishRevision(Samples.draft(entry.recordId, text = "初稿正文"))

        val http = HttpExchangeFactory { _ -> ScriptedExchange { _, _ -> ScriptedExchange.json(404, """{"code":"REQUEST_INVALID"}""") } }
        val log = CollectingLog()
        val service = RecordRevisionService(
            library = library,
            dispatch = fixture.store,
            pairing = InMemoryPairingStore(gateway),
            http = http,
            locator = GatewayLocator(http, NoDiscovery, log),
            policy = InMemoryDataFlowPolicy(gateway = gateway.identity),
            clock = clock,
            log = log,
            deleteFile = { true },
        )

        val vm = RecordDetailViewModel(entry.recordId, library, service, clock, TestScope(dispatcher))
        advanceUntilIdle()

        assertTrue("必须明确告知 Agent 已读走的内容无法收回", vm.state.value.deleteRecordNotice.contains("无法收回"))
        assertEquals(1, vm.state.value.revisions.size)

        // 只改草稿不保存：不产生新 Revision
        vm.updateDraft("未保存的草稿")
        advanceUntilIdle()
        assertEquals(1, library.get(entry.recordId)!!.revisions.size)

        // 点击保存：产生第 2 版，旧版保留
        vm.saveEdit()
        advanceUntilIdle()
        assertEquals(2, vm.state.value.revisions.size)
        assertEquals("初稿正文", vm.state.value.revisions[0].text)
        assertEquals("未保存的草稿", vm.state.value.revisions[1].text)

        // 单独删除音频：保留全部文字与 Revision
        assertNotNull(vm.state.value.record?.audio)
        vm.deleteAudioOnly()
        advanceUntilIdle()
        assertNull(vm.state.value.record?.audio)
        assertEquals(2, vm.state.value.revisions.size)

        // 确认重发给代理 A
        vm.confirmResend(destA.destinationId)
        advanceUntilIdle()
        val pub2 = fixture.store.publicationFor(JobKey(entry.recordId, 2))
        assertEquals(listOf(destA.destinationId), pub2.destinationIds)

        // 删除整条 Record
        vm.deleteRecord()
        advanceUntilIdle()
        assertTrue(vm.state.value.deleted)
        assertNull(library.get(entry.recordId))
        assertTrue(library.isDeleted(entry.recordId))
        assertTrue("日志绝不含正文", log.lines.none { it.contains("初稿正文") || it.contains("未保存的草稿") })
    }

    @Test
    fun `UnknownReason 逐项给出真实文案，不把拒收或读不懂说成离线`() {
        val refused = RevisionDescriptions.describeOlderDelivery(
            app.auri.sync.destinations.DeliveryView.Unknown(app.auri.sync.destinations.UnknownReason.DELIVERY_REFUSED),
        )
        val garbled = RevisionDescriptions.describeOlderDelivery(
            app.auri.sync.destinations.DeliveryView.Unknown(app.auri.sync.destinations.UnknownReason.GATEWAY_ANSWER_UNUSABLE),
        )
        assertFalse(refused.contains("离线"))
        assertFalse(garbled.contains("离线"))
        assertTrue(refused.contains("未为该目的地创建投递"))
    }
}
