package app.auri.platform.storage

import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.CaptureState
import app.auri.capture.coordinator.CaptureConfig
import app.auri.capture.coordinator.CaptureCoordinator
import app.auri.capture.coordinator.CaptureSnapshot
import app.auri.capture.testing.CollectingOutput
import app.auri.capture.testing.FakeCaptureAdapter
import app.auri.capture.testing.FakeClock
import app.auri.capture.testing.FakeControlAdapter
import app.auri.capture.testing.SequentialIds
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

/**
 * 真的协调器 + 真的存储（[WavRecordingStore]，真文件，只有 [FaultyDisk] 那一条缝是假的）+ 假的系统边缘（采集、控制）。
 * 用来证明“协调器与存储合在一起”的中断行为：存储在任意位置写满、进程在任意字节被杀。
 *
 * 进程被杀怎么模拟：[FaultyDisk.ProcessKilled] 是 Error，协调器与存储里的 catch (Exception) 拦不住，它会一路冒出邮箱协程把协调器带走，
 * 之后什么清理都不会发生，和真的被杀一样；只有 [deaths] 里留一笔，让测试能确认“被杀”确实发生了、并且没有别的意外。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class RealStoreRig(
    private val test: TestScope,
    directory: File,
    val disk: FaultyDisk = FaultyDisk(),
    config: CaptureConfig = CaptureConfig(),
) {
    val clock = FakeClock(test.testScheduler)
    val control = FakeControlAdapter(clock)
    val capture = FakeCaptureAdapter()
    val store = WavRecordingStore(directory, disk.io, commitIntervalMs = COMMIT_MS)
    val output = CollectingOutput()

    /** 协程里冒出来的、没人处理的 Throwable（被杀时只有一个 [FaultyDisk.ProcessKilled]）。 */
    val deaths = CopyOnWriteArrayList<Throwable>()

    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler) + CoroutineExceptionHandler { _, e -> deaths += e })

    val coordinator = CaptureCoordinator(
        control = control,
        capture = capture,
        store = store,
        output = output,
        clock = clock,
        ids = SequentialIds(),
        config = config,
        scope = scope,
        adapterDispatcher = StandardTestDispatcher(test.testScheduler),
    )

    private var epoch = 1L

    val snapshot: CaptureSnapshot get() = coordinator.snapshot.value
    val state: CaptureState get() = snapshot.state

    /** 已经送进协调器的语音采样数（含被杀时正在写的那一批）。 */
    var fedSamples = 0L
        private set

    /** 被杀的那一刻，已经送进去的采样数；没被杀时为 null。 */
    var fedAtDeath: Long? = null
        private set

    fun settle() = test.testScheduler.runCurrent()

    /** 屏幕上按“开始”、系统确认耳机路由：进入 RECORDING（磁盘写不下 begin 时会受阻）。 */
    fun startRecording() {
        control.press(BusinessCommand.START)
        settle()
        epoch = capture.verifiedRoute().epoch
        settle()
        if (fedAtDeath == null && deaths.isNotEmpty()) fedAtDeath = fedSamples
    }

    /** 送 [batches] 批连续的语音，每批 [samples] 个采样（默认 100 ms）。 */
    fun speak(batches: Int = 1, samples: Int = SAMPLES_PER_BATCH) {
        repeat(batches) {
            capture.frames(samples, epoch)
            fedSamples += samples
            settle()
            if (fedAtDeath == null && deaths.isNotEmpty()) fedAtDeath = fedSamples
        }
    }

    /** 屏幕上按“停止”。 */
    fun stop() {
        control.press(BusinessCommand.STOP)
        settle()
        if (fedAtDeath == null && deaths.isNotEmpty()) fedAtDeath = fedSamples
    }

    fun close() = scope.cancel()

    companion object {
        /** 分段提交间隔：测试里调成 100 ms（1600 个采样），一小段音频就跨过好几个提交边界；线上是 1 秒。 */
        const val COMMIT_MS = 100L
        const val SAMPLES_PER_BATCH = 1600

        /** [FakeCaptureAdapter.frames] 送出的采样值是确定的：每一批都是 1000 + (批内序号 % 100)。 */
        fun expectedPcm(batches: Int, samples: Int = SAMPLES_PER_BATCH): ShortArray =
            ShortArray(batches * samples) { (1_000 + (it % samples) % 100).toShort() }
    }
}
