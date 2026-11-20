package app.auri.capture.recovery

import app.auri.capture.adapter.OrphanFailure
import app.auri.capture.adapter.OrphanRecordingSource
import app.auri.capture.adapter.OrphanScan
import app.auri.capture.adapter.PcmFormat
import app.auri.capture.adapter.RecoveredRecording
import app.auri.capture.adapter.RecoveredRun
import app.auri.capture.adapter.SealedAudio
import app.auri.capture.contract.CaptureStatus
import app.auri.capture.contract.ContractRules
import app.auri.capture.contract.DiscardReason
import app.auri.capture.contract.DiscardedRange
import app.auri.capture.contract.EndReason
import app.auri.capture.contract.TimeRange
import app.auri.capture.library.TranscriptionStatus
import app.auri.capture.pipeline.RecordPipeline
import app.auri.capture.testing.FakeClock
import app.auri.capture.testing.FakeTranscriptionAdapter
import app.auri.capture.testing.InMemoryRecordLibrary
import app.auri.capture.testing.Samples
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 19 第 3 项（Record 这一侧）：从磁盘上找回的录音怎么变成 Record，以及它怎么走和别的 Record 一样的登记与转录。
 * 存储怎么验证字节、怎么截尾巴，在 :platform 的 RecordingRecoveryTest 里测。
 */
class OrphanRecoveryTest {
    private fun sha(n: Int) = "%064x".format(n)

    private fun audioFor(recordId: String, samples: Long) = SealedAudio("fake://$recordId.wav", sha(99), 44 + samples * 2, samples, Samples.format)

    private fun recovered(
        n: Int,
        runs: List<RecoveredRun>,
        format: PcmFormat = Samples.format,
        withAudio: Boolean = runs.isNotEmpty(),
    ): RecoveredRecording {
        val meta = Samples.recordingMeta(n)
        return RecoveredRecording(
            recordId = Samples.uuid(n),
            captureSessionId = Samples.uuid(1000 + n),
            startedAt = meta.startedAt,
            evidence = meta.evidence,
            routeEvidenceId = meta.routeEvidenceId,
            format = format,
            runs = runs,
            audio = if (withAudio) audioFor(Samples.uuid(n), runs.sumOf { it.endSample - it.startSample }) else null,
            droppedBytes = 17,
        )
    }

    // ---- 重建 Record ----

    @Test
    fun `恢复出来的录音一律是 partial 加 process_terminated，时间线只来自验证过的分段，采集时长是下限`() {
        val sealed = recovered(7, listOf(RecoveredRun(0, 1600, sha(1)), RecoveredRun(4800, 6400, sha(2)))).toSealedRecording()

        val result = sealed.result
        assertEquals(CaptureStatus.PARTIAL, result.status)
        assertEquals(EndReason.PROCESS_TERMINATED, result.endReason)
        assertEquals(listOf(TimeRange(0, 100), TimeRange(300, 400)), result.verifiedRanges)
        assertEquals(200L, result.speechDurationMs)
        assertEquals("最后一段验证过的语音的结束时刻，不编造被杀之后的时间", 400L, result.captureDurationMs)
        assertEquals("空隙是当初没有保留的静音", listOf(DiscardedRange(100, 300, DiscardReason.SILENCE)), result.discardedRanges)
        assertEquals(listOf(sha(1), sha(2)), result.chunks.map { it.sha256 })
        assertEquals(listOf(0L, 1L), result.chunks.map { it.sequence })
        assertEquals("chunk 的 route_epoch 是确认路由时的 epoch", listOf(1L, 1L), result.chunks.map { it.routeEpoch })
        assertEquals(listOf(0L to 100L, 300L to 400L), result.chunks.map { it.startMs to it.endMs })
        assertEquals(emptyList<String>(), ContractRules.violations(result))
        assertEquals(Samples.uuid(7), result.recordId)
        assertEquals(Samples.uuid(1007), result.captureSessionId)
        assertEquals(Samples.uuid(2007), sealed.routeEvidenceId)
        assertEquals(Samples.recordingMeta(7).startedAt, sealed.startedAt)
        assertEquals(3200L, sealed.audio?.sampleCount)
    }

    @Test
    fun `第一段语音之前的静音也记成没有保留的静音`() {
        val result = recovered(3, listOf(RecoveredRun(1600, 3200, sha(1)))).toSealedRecording().result

        assertEquals(listOf(TimeRange(100, 200)), result.verifiedRanges)
        assertEquals(listOf(DiscardedRange(0, 100, DiscardReason.SILENCE)), result.discardedRanges)
        assertEquals(200L, result.captureDurationMs)
    }

    @Test
    fun `一段验证过的音频也没有：仍然是一个 Record，没有音频、没有分段，采集时长为 0`() {
        val sealed = recovered(4, emptyList()).toSealedRecording()

        assertNull(sealed.audio)
        assertEquals(emptyList<Any>(), sealed.result.chunks)
        assertEquals(emptyList<Any>(), sealed.result.verifiedRanges)
        assertEquals(0L, sealed.result.captureDurationMs)
        assertEquals(CaptureStatus.PARTIAL, sealed.result.status)
        assertEquals(EndReason.PROCESS_TERMINATED, sealed.result.endReason)
    }

    @Test
    fun `同一个录音每次恢复得到同样的结果，chunk 标识不随机：恢复可以重复`() {
        val first = recovered(5, listOf(RecoveredRun(0, 1600, sha(1)), RecoveredRun(3200, 4800, sha(2)))).toSealedRecording()
        val second = recovered(5, listOf(RecoveredRun(0, 1600, sha(1)), RecoveredRun(3200, 4800, sha(2)))).toSealedRecording()
        val other = recovered(6, listOf(RecoveredRun(0, 1600, sha(1)), RecoveredRun(3200, 4800, sha(2)))).toSealedRecording()

        assertEquals(first.result, second.result)
        assertEquals(2, first.result.chunks.map { it.chunkId }.toSet().size)
        assertNotEquals("不同的录音不共用 chunk 标识", first.result.chunks.map { it.chunkId }, other.result.chunks.map { it.chunkId })
    }

    @Test
    fun `立体声格式按交错采样数换算时间`() {
        val stereo = PcmFormat(16_000, 2)
        val result = recovered(8, listOf(RecoveredRun(0, 32_000, sha(1))), format = stereo).toSealedRecording().result

        assertEquals(listOf(TimeRange(0, 1000)), result.verifiedRanges)
    }

    @Test
    fun `存储给出的分段与音频对不上：直接拒收，不会登记一个说谎的 Record`() {
        val meta = Samples.recordingMeta(9)
        val mismatch = { RecoveredRecording(Samples.uuid(9), Samples.uuid(1009), meta.startedAt, meta.evidence, meta.routeEvidenceId, Samples.format, listOf(RecoveredRun(0, 1600, sha(1))), audioFor(Samples.uuid(9), 1599), 0) }
        assertThrows(IllegalArgumentException::class.java) { mismatch() }

        val overlapping = {
            RecoveredRecording(Samples.uuid(9), Samples.uuid(1009), meta.startedAt, meta.evidence, meta.routeEvidenceId, Samples.format, listOf(RecoveredRun(0, 1600, sha(1)), RecoveredRun(1000, 2000, sha(2))), audioFor(Samples.uuid(9), 2600), 0)
        }
        assertThrows(IllegalArgumentException::class.java) { overlapping() }
    }

    // ---- 登记与转录 ----

    private class Source(var scan: OrphanScan = OrphanScan(emptyList(), emptyList())) : OrphanRecordingSource {
        var probe: List<String> = emptyList()
        val answers = mutableMapOf<String, Boolean>()
        var scans = 0

        override suspend fun recoverOrphans(isRegistered: suspend (recordId: String) -> Boolean): OrphanScan {
            scans += 1
            probe.forEach { answers[it] = isRegistered(it) }
            return scan
        }
    }

    private class Setup(val scope: TestScope) {
        val library = InMemoryRecordLibrary()
        val adapter = FakeTranscriptionAdapter()
        val pipeline = RecordPipeline(library, adapter, FakeClock(scope.testScheduler), scope.backgroundScope)
        val source = Source()
        val recovery = OrphanRecovery(source, library, pipeline)

        fun settle() = scope.testScheduler.runCurrent()
    }

    @Test
    fun `找回的录音和别的录音走同一条路：登记成 partial Record，有音频的交给转录，没有音频的记为没有语音`() = runTest {
        val setup = Setup(this)
        setup.source.scan = OrphanScan(
            listOf(recovered(1, listOf(RecoveredRun(0, 16_000, sha(1)))), recovered(2, emptyList())),
            listOf(OrphanFailure(Samples.uuid(3), "journal_unreadable")),
        )

        val report = setup.recovery.run()
        setup.settle()

        assertEquals(RecoveryReport(recovered = 2, withoutAudio = 1, failed = 1), report)
        val records = setup.library.observe().first().associateBy { it.recordId }
        val withAudio = records.getValue(Samples.uuid(1))
        assertEquals(CaptureStatus.PARTIAL, withAudio.status)
        assertEquals(EndReason.PROCESS_TERMINATED, withAudio.endReason)
        assertEquals(TranscriptionStatus.DONE, withAudio.transcription)
        assertEquals("像任何别的 Record 一样被转录", listOf(Samples.uuid(1)), setup.adapter.requests.map { it.recordId })
        assertEquals(listOf(TimeRange(0, 1000)), withAudio.speechSegments)
        val empty = records.getValue(Samples.uuid(2))
        assertEquals(TranscriptionStatus.NO_SPEECH, empty.transcription)
        assertTrue(empty.revisions.isEmpty())
    }

    @Test
    fun `问存储的“已登记”由 Record 库说了算：库里有的说有，没有的说没有`() = runTest {
        val setup = Setup(this)
        setup.library.addRecord(Samples.entry(1))
        setup.source.probe = listOf(Samples.uuid(1), Samples.uuid(2))

        setup.recovery.run()

        assertEquals(mapOf(Samples.uuid(1) to true, Samples.uuid(2) to false), setup.source.answers)
    }

    @Test
    fun `重复运行不会产生重复的 Record，也不会重复转录`() = runTest {
        val setup = Setup(this)
        setup.source.scan = OrphanScan(listOf(recovered(1, listOf(RecoveredRun(0, 16_000, sha(1))))), emptyList())

        setup.recovery.run()
        setup.settle()
        setup.recovery.run() // 上一次登记完成之前被杀、下次启动再来一遍的样子
        setup.settle()

        assertEquals(2, setup.source.scans)
        assertEquals(1, setup.library.observe().first().size)
        assertEquals(1, setup.adapter.requests.size)
    }
}
