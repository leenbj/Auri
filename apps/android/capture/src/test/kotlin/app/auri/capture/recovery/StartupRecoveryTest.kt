package app.auri.capture.recovery

import app.auri.capture.adapter.OrphanFailure
import app.auri.capture.adapter.OrphanRecordingSource
import app.auri.capture.adapter.OrphanScan
import app.auri.capture.adapter.RecoveredRecording
import app.auri.capture.adapter.RecoveredRun
import app.auri.capture.adapter.SealedAudio
import app.auri.capture.contract.CaptureStatus
import app.auri.capture.contract.EndReason
import app.auri.capture.pipeline.RecordPipeline
import app.auri.capture.testing.FakeClock
import app.auri.capture.testing.FakeTranscriptionAdapter
import app.auri.capture.testing.InMemoryRecordLibrary
import app.auri.capture.testing.Samples
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 19 第 3、4 项：启动时的恢复入口。它只做一件事（找回孤儿、登记成 Record、交给转录），失败时不让应用起不来、
 * 也不把路径或标识写进日志。采集状态、前台服务、麦克风在重启之后什么都没发生，由 :platform 的 RestartRecoveryTest 用真实存储证明。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StartupRecoveryTest {
    private fun sha(n: Int) = "%064x".format(n)

    private fun orphan(n: Int): RecoveredRecording {
        val meta = Samples.recordingMeta(n)
        val audio = SealedAudio("fake://${Samples.uuid(n)}.wav", sha(99), 44 + 32_000L, 16_000, Samples.format)
        return RecoveredRecording(Samples.uuid(n), Samples.uuid(1000 + n), meta.startedAt, meta.evidence, meta.routeEvidenceId, Samples.format, listOf(RecoveredRun(0, 16_000, sha(n))), audio, 0)
    }

    /** 每次扫描按队列里的下一个动作来：返回结果或抛异常。 */
    private class Source(vararg steps: () -> OrphanScan) : OrphanRecordingSource {
        private val queue = ArrayDeque(steps.toList())
        var scans = 0

        override suspend fun recoverOrphans(isRegistered: suspend (recordId: String) -> Boolean): OrphanScan {
            scans += 1
            return queue.removeFirst()()
        }
    }

    private class Setup(val scope: TestScope, val source: Source) {
        val library = InMemoryRecordLibrary()
        val adapter = FakeTranscriptionAdapter()
        val pipeline = RecordPipeline(library, adapter, FakeClock(scope.testScheduler), scope.backgroundScope)
        val logs = mutableListOf<String>()
        val startup = StartupRecovery(OrphanRecovery(source, library, pipeline)) { logs += it }

        fun settle() = scope.testScheduler.runCurrent()
    }

    @Test
    fun `启动时把磁盘上的孤儿登记成被截断的 Record 并转录；日志只有计数，没有标识`() = runTest {
        val setup = Setup(this, Source({ OrphanScan(listOf(orphan(1)), listOf(OrphanFailure(Samples.uuid(2), "journal_unreadable"))) }))

        val report = setup.startup.run()
        setup.settle()

        assertEquals(RecoveryReport(recovered = 1, withoutAudio = 0, failed = 1), report)
        val record = setup.library.observe().first().single()
        assertEquals(Samples.uuid(1), record.recordId)
        assertEquals(CaptureStatus.PARTIAL, record.status)
        assertEquals(EndReason.PROCESS_TERMINATED, record.endReason)
        assertEquals("像任何别的 Record 一样被转录", 1, setup.adapter.requests.size)
        assertEquals(1, setup.logs.size)
        assertTrue("日志写了计数：${setup.logs}", "recovered=1" in setup.logs.single() && "failed=1" in setup.logs.single())
        assertFalse("日志里不能有 Record 标识", setup.logs.any { Samples.uuid(1) in it || Samples.uuid(2) in it })
    }

    @Test
    fun `没有孤儿：什么都不登记，也不转录，返回零`() = runTest {
        val setup = Setup(this, Source({ OrphanScan(emptyList(), emptyList()) }))

        val report = setup.startup.run()
        setup.settle()

        assertEquals(RecoveryReport(0, 0, 0), report)
        assertTrue(setup.library.observe().first().isEmpty())
        assertTrue(setup.adapter.requests.isEmpty())
    }

    @Test
    fun `存储读不出来：不抛出、不让应用起不来，日志只记失败这个事实而不带路径与标识；下次启动再试得成`() = runTest {
        val secret = "/data/user/0/app.auri.android/files/recordings/${Samples.uuid(1)}.journal: EIO"
        val setup = Setup(this, Source({ throw IOException(secret) }, { OrphanScan(listOf(orphan(1)), emptyList()) }))

        val first = setup.startup.run() // 抛出就会让这个测试失败

        assertNull(first)
        assertEquals(1, setup.logs.size)
        assertFalse("异常消息里的路径与标识不进日志：${setup.logs}", setup.logs.single().contains("/data") || setup.logs.single().contains(Samples.uuid(1)) || setup.logs.single().contains("EIO"))
        assertTrue("记下了失败", "failed" in setup.logs.single())
        assertTrue("失败时一个 Record 也没登记", setup.library.observe().first().isEmpty())

        val second = setup.startup.run() // 下一次启动：磁盘上的东西原样还在
        setup.settle()

        assertEquals(RecoveryReport(1, 0, 0), second)
        assertEquals(1, setup.library.observe().first().size)
    }

    @Test
    fun `登记到一半失败（Record 库出了错）：不抛出；已登记的下次不会重复，没登记的补上`() = runTest {
        val library = InMemoryRecordLibrary()
        val adapter = FakeTranscriptionAdapter()
        val pipeline = RecordPipeline(library, adapter, FakeClock(testScheduler), backgroundScope)
        var failNext = true
        val flaky = object : app.auri.capture.coordinator.CaptureOutput {
            override fun recordingSealed(sealed: app.auri.capture.coordinator.SealedRecording) {
                if (sealed.result.recordId == Samples.uuid(2) && failNext) {
                    failNext = false
                    throw IllegalStateException("库出了错（测试注入）")
                }
                pipeline.recordingSealed(sealed)
            }

            override fun recordingLost(recordId: String, reason: app.auri.capture.contract.ErrorCode) = pipeline.recordingLost(recordId, reason)
        }
        val source = Source({ OrphanScan(listOf(orphan(1), orphan(2)), emptyList()) }, { OrphanScan(listOf(orphan(1), orphan(2)), emptyList()) })
        val logs = mutableListOf<String>()
        val startup = StartupRecovery(OrphanRecovery(source, library, flaky)) { logs += it }

        assertNull(startup.run())
        testScheduler.runCurrent()
        startup.run()
        testScheduler.runCurrent()

        assertEquals("两个录音各登记一次", listOf(Samples.uuid(1), Samples.uuid(2)), library.observe().first().map { it.recordId }.sorted())
    }

    @Test
    fun `协程被取消时取消照常传出去，不被当成恢复失败吞掉`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val blocking = object : OrphanRecordingSource {
            override suspend fun recoverOrphans(isRegistered: suspend (recordId: String) -> Boolean): OrphanScan {
                gate.await()
                return OrphanScan(emptyList(), emptyList())
            }
        }
        val library = InMemoryRecordLibrary()
        val pipeline = RecordPipeline(library, FakeTranscriptionAdapter(), FakeClock(testScheduler), backgroundScope)
        val logs = mutableListOf<String>()
        val startup = StartupRecovery(OrphanRecovery(blocking, library, pipeline)) { logs += it }
        var cancelled = false

        val job = launch {
            try {
                startup.run()
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        runCurrent()
        job.cancel()
        runCurrent()

        assertTrue("取消必须传出去", cancelled)
        assertTrue("取消不是恢复失败，不记失败日志", logs.isEmpty())
    }
}
