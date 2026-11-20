package app.auri.platform.storage

import app.auri.capture.contract.CaptureStatus
import app.auri.capture.contract.EndReason
import app.auri.capture.coordinator.CaptureOutput
import app.auri.capture.coordinator.SealedRecording
import app.auri.capture.library.RecordEntry
import app.auri.capture.library.RecordLibrary
import app.auri.capture.library.TranscriptionStatus
import app.auri.capture.pipeline.PipelineNotice
import app.auri.capture.pipeline.RecordPipeline
import app.auri.capture.recovery.OrphanRecovery
import app.auri.capture.testing.FakeClock
import app.auri.capture.testing.FakeTranscriptionAdapter
import app.auri.capture.testing.InMemoryRecordLibrary
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Ticket 19 评审 M1：一个采样都没收到的录音（路由确认了、什么也没录到）仍然要有一个“没有语音”的 Record（FR-REC-003）。
 *
 * 登记是异步的：协调器把封存结果交给 Record 管线，管线在自己的协程里写库。旧实现在这种录音收场时 `abort()`，
 * 连恢复记录一起删了——管线还没登记就被杀，或者库写失败，这个 Record 就永远丢了，而用户并不知道。
 * 这里用真的协调器 + 真的存储 + 真的 Record 管线 + 真的恢复流程（系统边缘是假的），把“登记之前的窗口”逐个撑开。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoSpeechRecordDurabilityTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val directory get() = File(folder.root, "records")

    /** 下一个进程：全新的存储对象与管线，只共享磁盘目录；[library] 代表持久化的库（上一个进程登记成功的东西还在）。 */
    private class NextProcess(test: TestScope, directory: File, private val library: RecordLibrary) {
        private val pipeline = RecordPipeline(library, FakeTranscriptionAdapter(), FakeClock(test.testScheduler), test.backgroundScope)
        private val store = WavRecordingStore(directory)

        suspend fun recoverOnStart() = OrphanRecovery(store, library, pipeline).run()
    }

    /** 路由确认了、一个采样也没收到、按停止：协调器收场，结果转交 [forwardTo]（null：谁也没有登记它）。 */
    private fun TestScope.noSpeechRecording(forwardTo: CaptureOutput? = null): SealedRecording {
        val rig = RealStoreRig(this, directory)
        rig.output.forwardTo = forwardTo
        rig.startRecording()
        rig.stop()
        assertTrue("不该有没人处理的异常：${rig.deaths}", rig.deaths.isEmpty())
        val sealed = rig.output.recordings.single()
        assertNull("没有任何音频", sealed.audio)
        rig.close()
        return sealed
    }

    private fun onDisk() = directory.list().orEmpty().sorted()

    @Test
    fun `登记之前进程被杀：下次启动恰好找回一个没有语音的 Record；再启动不重复；登记之后磁盘清理干净`() = runTest {
        val sealed = noSpeechRecording() // 没有任何东西登记它：相当于结果还在内存消息里、进程就被杀了
        val id = sealed.result.recordId
        assertEquals("磁盘上留下的是恢复记录，没有音频文件", listOf("$id.journal"), onDisk())

        val library = InMemoryRecordLibrary()
        val report = NextProcess(this, directory, library).recoverOnStart()
        runCurrent()

        assertEquals(1, report.recovered)
        assertEquals("它是没有音频的", 1, report.withoutAudio)
        assertEquals(0, report.failed)
        val record = library.observe().first().single()
        assertEquals(id, record.recordId)
        assertEquals(TranscriptionStatus.NO_SPEECH, record.transcription)
        assertNull(record.audio)
        assertEquals("恢复出来的一律是被中断的（不知道它本来是怎么结束的）", CaptureStatus.PARTIAL, record.status)
        assertEquals(EndReason.PROCESS_TERMINATED, record.endReason)

        // 再启动一次（Record 已经在库里）：不重复登记，恢复记录被清掉
        val again = NextProcess(this, directory, library).recoverOnStart()
        runCurrent()
        assertEquals(0, again.recovered)
        assertEquals(listOf(id), library.observe().first().map { it.recordId })
        assertEquals("登记之后清理干净", emptyList<String>(), onDisk())
    }

    @Test
    fun `库写失败：通知 Record 没能保存，恢复记录仍在；下次启动登记恰好一个`() = runTest {
        val inner = InMemoryRecordLibrary()
        val failing = object : RecordLibrary by inner {
            override suspend fun addRecord(entry: RecordEntry): Boolean = throw IllegalStateException("库写入失败（测试注入）")
        }
        val pipeline = RecordPipeline(failing, FakeTranscriptionAdapter(), FakeClock(testScheduler), backgroundScope)
        val notices = mutableListOf<PipelineNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { pipeline.notices.toCollection(notices) }

        val sealed = noSpeechRecording(forwardTo = pipeline)
        runCurrent()
        val id = sealed.result.recordId

        assertEquals(listOf<PipelineNotice>(PipelineNotice.RecordNotSaved(id)), notices)
        assertTrue("库里没有它", inner.observe().first().isEmpty())
        assertEquals("恢复记录还在：这是这个 Record 唯一的凭据", listOf("$id.journal"), onDisk())

        val report = NextProcess(this, directory, inner).recoverOnStart() // 下次启动，库已经好了
        runCurrent()
        assertEquals(1, report.recovered)
        assertEquals(listOf(id), inner.observe().first().map { it.recordId })
        val third = NextProcess(this, directory, inner).recoverOnStart()
        runCurrent()
        assertEquals("再启动不重复", 0, third.recovered)
        assertEquals(listOf(id), inner.observe().first().map { it.recordId })
        assertEquals(emptyList<String>(), onDisk())
    }

    @Test
    fun `正常登记：库里一个 Record 原样保留，下次启动不重复登记也不改写它，只清理恢复记录`() = runTest {
        val library = InMemoryRecordLibrary()
        val pipeline = RecordPipeline(library, FakeTranscriptionAdapter(), FakeClock(testScheduler), backgroundScope)

        val sealed = noSpeechRecording(forwardTo = pipeline)
        runCurrent()
        val id = sealed.result.recordId
        val registered = library.observe().first().single()
        assertEquals(TranscriptionStatus.NO_SPEECH, registered.transcription)
        assertEquals(sealed.result.status, registered.status)
        assertEquals("登记了恢复记录也还在：由下次启动确认库里有了之后再清理", listOf("$id.journal"), onDisk())

        val report = NextProcess(this, directory, library).recoverOnStart()
        runCurrent()

        assertEquals("已经登记过的不是孤儿", 0, report.recovered)
        assertEquals("库里的 Record 没有被恢复改写", listOf(registered), library.observe().first())
        assertEquals(emptyList<String>(), onDisk())
    }
}
