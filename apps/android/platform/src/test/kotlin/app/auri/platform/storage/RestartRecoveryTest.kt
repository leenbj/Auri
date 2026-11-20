package app.auri.platform.storage

import app.auri.asr.audio.WavReader
import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.CaptureState
import app.auri.capture.contract.CaptureStatus
import app.auri.capture.contract.EndReason
import app.auri.capture.coordinator.CaptureCoordinator
import app.auri.capture.library.TranscriptionStatus
import app.auri.capture.pipeline.RecordPipeline
import app.auri.capture.recovery.OrphanRecovery
import app.auri.capture.recovery.StartupRecovery
import app.auri.capture.testing.FakeCaptureAdapter
import app.auri.capture.testing.FakeClock
import app.auri.capture.testing.FakeControlAdapter
import app.auri.capture.testing.FakeTranscriptionAdapter
import app.auri.capture.testing.InMemoryRecordLibrary
import app.auri.capture.testing.SequentialIds
import app.auri.platform.service.ForegroundServiceController
import app.auri.platform.service.bindForegroundService
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Ticket 19 第 4 项：进程重启之后永远落在 DISARMED。
 *
 * 上一个进程录到一半被杀，磁盘上留着孤儿；下一个进程按应用启动的顺序接起来（前台服务跟着采集状态、转录管线续跑、启动恢复），
 * 全部用真实的类：真的协调器、真的存储（读同一个目录）、真的前台服务绑定器、真的 [StartupRecovery]。
 * 只有系统边缘（采集适配器、控制适配器、前台服务本身、转录引擎）是假的，所以“没有开麦、没有起前台服务、没有自动待命”是可以数出来的。
 * 这里的启动顺序是照 AuriRuntime 的 init 写的；AuriRuntime 自己要 Application，只能在设备上看（见验收文档，NOT_RUN）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestartRecoveryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private class FakeForegroundService : ForegroundServiceController {
        val calls = mutableListOf<String>()

        override fun start(): Boolean {
            calls += "start"
            return true
        }

        override fun stop() {
            calls += "stop"
        }
    }

    /** 下一个进程：全新的对象，只共享磁盘目录。 */
    private class RestartedProcess(test: TestScope, directory: File) {
        val clock = FakeClock(test.testScheduler)
        val control = FakeControlAdapter(clock)
        val capture = FakeCaptureAdapter()
        val library = InMemoryRecordLibrary()
        val transcription = FakeTranscriptionAdapter()
        val store = WavRecordingStore(directory)
        val pipeline = RecordPipeline(library, transcription, clock, test.backgroundScope)
        val foreground = FakeForegroundService()
        val logs = mutableListOf<String>()
        val coordinator = CaptureCoordinator(
            control = control,
            capture = capture,
            store = store,
            output = pipeline,
            clock = clock,
            ids = SequentialIds(7),
            scope = test.backgroundScope,
            adapterDispatcher = StandardTestDispatcher(test.testScheduler),
        )

        init {
            // 与 AuriRuntime.init 同样的顺序：前台服务跟着采集状态 → 转录管线续跑 → 启动恢复
            test.backgroundScope.bindForegroundService(coordinator.snapshot, foreground) { coordinator.foregroundServiceFailed() }
            pipeline.resume()
        }

        suspend fun recoverOnStartup() = StartupRecovery(OrphanRecovery(store, library, pipeline)) { logs += it }.run()
    }

    /** 上一个进程：录了一会儿，在 [killAt] 字节处被杀；返回它已经送进去的采样数。 */
    private fun TestScope.killedProcess(directory: File, killAt: Long): Long {
        val rig = RealStoreRig(this, directory, FaultyDisk(killAfterBytes = killAt))
        rig.startRecording()
        rig.speak(12)
        rig.stop()
        assertEquals("上一个进程确实是被杀的：${rig.deaths}", 1, rig.deaths.size)
        assertTrue(rig.deaths.single() is FaultyDisk.ProcessKilled)
        rig.close()
        return rig.fedAtDeath ?: rig.fedSamples
    }

    /** 不出故障地跑一遍，量出总共写了多少字节，用来在录音中途选一个被杀的位置。 */
    private fun TestScope.totalBytes(): Long {
        val disk = FaultyDisk()
        val rig = RealStoreRig(this, folder.newFolder(), disk)
        rig.startRecording()
        rig.speak(12)
        rig.stop()
        rig.close()
        return disk.written
    }

    @Test
    fun `上一个进程被杀、磁盘上有孤儿：重启后录音被找回成被截断的 Record 并转录，采集仍是 DISARMED，没开麦、没起前台服务、没自动待命，之后一个小时也没有`() = runTest {
        val directory = File(folder.root, "records")
        killedProcess(directory, killAt = totalBytes() * 3 / 5)
        assertTrue("被杀之后磁盘上有未封存的录音", directory.list().orEmpty().any { it.endsWith(".wav.part") })

        val next = RestartedProcess(this, directory)
        val report = next.recoverOnStartup()
        runCurrent()

        // 找回了：像别的 Record 一样登记、转录
        assertEquals(1, report?.recovered)
        val record = next.library.observe().first().single()
        assertEquals(CaptureStatus.PARTIAL, record.status)
        assertEquals(EndReason.PROCESS_TERMINATED, record.endReason)
        assertEquals(TranscriptionStatus.DONE, record.transcription)
        assertEquals("恢复出来的录音被转录，只一次", 1, next.transcription.requests.size)
        val audio = record.audio ?: throw AssertionError("恢复出来的 Record 应当带着验证过的音频")
        assertTrue("磁盘上整理好的是一个能读的 WAV，里面有验证过的采样", WavReader.read(File(audio.location)).samples.isNotEmpty())

        // 重启之后采集的状态：DISARMED，什么都没发生
        assertEquals(CaptureState.DISARMED, next.coordinator.snapshot.value.state)
        assertFalse("没有自动待命（控制模式没开）", next.coordinator.snapshot.value.controlModeEnabled)
        assertEquals("没有请求过麦克风", 0, next.capture.requests.size)
        assertEquals("也没有释放过什么（没有什么可释放）", 0, next.capture.releases.size)
        assertEquals("没有开启耳机控制", 0, next.control.enableCalls)
        assertFalse("没有起前台服务（只有绑定时对 DISARMED 的那次 stop）：${next.foreground.calls}", "start" in next.foreground.calls)

        // 一个小时过去了：什么都不会自己发生
        testScheduler.advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(CaptureState.DISARMED, next.coordinator.snapshot.value.state)
        assertEquals(0, next.capture.requests.size)
        assertFalse("start" in next.foreground.calls)
        assertEquals("仍然只有这一个 Record", 1, next.library.observe().first().size)

        // 只有用户的操作才会开始录音：同一套接线此刻是活的（否则上面的“没起前台服务”什么也没证明）
        next.control.press(BusinessCommand.START)
        runCurrent()
        assertEquals(CaptureState.ROUTING, next.coordinator.snapshot.value.state)
        assertEquals(1, next.capture.requests.size)
        assertEquals("用户开始之后前台服务才会起", listOf("stop", "start"), next.foreground.calls)
    }

    @Test
    fun `磁盘上没有孤儿：重启后什么都没有，采集同样是 DISARMED`() = runTest {
        val next = RestartedProcess(this, File(folder.root, "records"))

        val report = next.recoverOnStartup()
        runCurrent()

        assertEquals(0, report?.recovered)
        assertTrue(next.library.observe().first().isEmpty())
        assertTrue(next.transcription.requests.isEmpty())
        assertEquals(CaptureState.DISARMED, next.coordinator.snapshot.value.state)
        assertEquals(0, next.capture.requests.size)
        assertFalse("start" in next.foreground.calls)
    }

    @Test
    fun `启动恢复重复跑（上一次登记到一半又被杀）：Record 不重复，转录不重复`() = runTest {
        val directory = File(folder.root, "records")
        killedProcess(directory, killAt = totalBytes() * 3 / 5)

        val first = RestartedProcess(this, directory)
        first.recoverOnStartup()
        runCurrent()
        // 登记完成了，但进程在清理恢复记录之前又被杀：下一个进程拿同一份磁盘、同一个库（这里用同一个库对象代表持久化的库）再来一遍
        val second = RestartedProcess(this, directory)
        first.library.observe().first().forEach { second.library.addRecord(it) }
        val report = second.recoverOnStartup()
        runCurrent()

        assertEquals("已经登记的不再恢复", 0, report?.recovered)
        assertEquals(1, second.library.observe().first().size)
        assertTrue("没有重复转录", second.transcription.requests.isEmpty())
    }
}
