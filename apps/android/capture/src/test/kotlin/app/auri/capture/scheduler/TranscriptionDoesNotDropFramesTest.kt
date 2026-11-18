package app.auri.capture.scheduler

import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.CaptureState
import app.auri.capture.contract.CaptureStatus
import app.auri.capture.contract.CaptureHandle
import app.auri.capture.adapter.PcmFormat
import app.auri.capture.adapter.RecordingStore
import app.auri.capture.adapter.RecordingWriter
import app.auri.capture.coordinator.CaptureConfig
import app.auri.capture.coordinator.CaptureCoordinator
import app.auri.capture.pipeline.RecordPipeline
import app.auri.capture.testing.BoundedSchedulerTest
import app.auri.capture.testing.CaptureRig
import app.auri.capture.testing.CollectingOutput
import app.auri.capture.testing.FakeCaptureAdapter
import app.auri.capture.testing.FakeClock
import app.auri.capture.testing.FakeControlAdapter
import app.auri.capture.testing.FakeRecordingStore
import app.auri.capture.testing.FakeTranscriptionAdapter
import app.auri.capture.testing.InMemoryRecordLibrary
import app.auri.capture.testing.SequentialIds
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Ticket 20 第 5 项：转录正在进行时开始新的录音，新录音的每一帧都送达，一帧都不丢。
 * “丢”的判据是协调器自己的计数（[app.auri.capture.coordinator.FrameCounters]）：旧会话丢弃、闸门丢弃、Quarantine 丢弃都是 0，
 * 写入的采样数正好等于送出的采样数。两种做法互补：
 *  - 虚拟时间：精确，证明转录占着名额时第二段录音、第二个任务的排队与顺序都对；
 *  - 真线程：引擎调用是**阻塞**的（像真引擎那样占住线程），而协调器、流水线、调度器邮箱全挤在**同一条**线程上——
 *    这是最坏情形。只要有人把引擎调用放回这条线程，协调器就处理不了帧，这个测试就会失败。
 *    这里用的是线上的接法（[TranscriptionRuntime]），不是手工拼的另一套。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionDoesNotDropFramesTest : BoundedSchedulerTest() {
    private val frameSamples = 320 // 20 ms @ 16 kHz

    @Test
    fun `真实低优先级 CPU 推理并发时，实时生产的录音帧零丢失且延迟有界`() {
        val appExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "capture-mailbox").apply { isDaemon = true } }
        val appScope = CoroutineScope(SupervisorJob() + appExecutor.asCoroutineDispatcher())
        val clock = FakeClock(TestCoroutineScheduler())
        val control = FakeControlAdapter(clock)
        val capture = FakeCaptureAdapter()
        val stored = FakeRecordingStore()
        val measuring = AtomicBoolean(false)
        val sent = ConcurrentLinkedQueue<Long>()
        val latencies = CopyOnWriteArrayList<Long>()
        val store = object : RecordingStore {
            override fun begin(recordId: String, handle: CaptureHandle, format: PcmFormat): RecordingWriter {
                val writer = stored.begin(recordId, handle, format)
                return object : RecordingWriter by writer {
                    override fun append(handle: CaptureHandle, samples: ShortArray) {
                        if (measuring.get()) latencies += System.nanoTime() - checkNotNull(sent.poll())
                        writer.append(handle, samples)
                    }
                }
            }
        }
        val output = CollectingOutput()
        val coordinator = CaptureCoordinator(control, capture, store, output, clock, SequentialIds(), CaptureConfig(), appScope, Dispatchers.IO)
        val library = InMemoryRecordLibrary()
        val engineStarted = CountDownLatch(1)
        val burning = AtomicBoolean(true)
        val result = AtomicLong()
        val engineThreads = CopyOnWriteArrayList<Thread>()
        val adapter = FakeTranscriptionAdapter().apply {
            hook = {
                engineThreads += Thread.currentThread()
                engineStarted.countDown()
                var value = 1L
                while (burning.get()) value = value * 1_664_525 + 1_013_904_223
                result.set(value)
            }
        }
        val runtime = TranscriptionRuntime(library, adapter, clock, appScope)
        output.forwardTo = RecordPipeline(library, runtime.scheduler, appScope)
        var producer: Thread? = null
        try {
            coordinator.enableControlMode()
            awaitUntil("进入待命") { coordinator.snapshot.value.state == CaptureState.ARMED }
            recordSegmentLive(control, coordinator, capture, output, frames = 5, expectedRecordings = 1)
            assertTrue("第一段的推理已开始", engineStarted.await(5, TimeUnit.SECONDS))

            control.press(BusinessCommand.START)
            awaitUntil("竞争推理时开始录音", timeoutMs = 1_000) {
                coordinator.snapshot.value.state == CaptureState.ROUTING && runCatching { capture.sink(1) }.isSuccess
            }
            capture.verifiedRoute(epoch = 1)
            awaitUntil("竞争推理时确认路由", timeoutMs = 1_000) { coordinator.snapshot.value.state == CaptureState.RECORDING }
            measuring.set(true)
            val productionStarted = System.nanoTime()
            producer = Thread({
                var due = System.nanoTime()
                repeat(150) {
                    while (System.nanoTime() < due) LockSupport.parkNanos(due - System.nanoTime())
                    sent.add(due)
                    capture.frames(samples = frameSamples, epoch = 1)
                    due += TimeUnit.MILLISECONDS.toNanos(20)
                }
            }, "real-time-frame-producer").apply { isDaemon = true; start() }
            producer.join(5_000)
            assertFalse("生产线程按实时节奏结束", producer.isAlive)
            val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - productionStarted)
            assertTrue("150 帧按 20 ms 间隔生产至少需 2.9 秒", durationMs >= 2_900)
            awaitUntil("所有帧已送达", timeoutMs = 2_000) { latencies.size == 150 }
            val maxLatencyMs = TimeUnit.NANOSECONDS.toMillis(latencies.max())
            println("contention_frames=150 producer_duration_ms=$durationMs max_delivery_latency_ms=$maxLatencyMs")
            assertTrue("每帧到达写入的最大延迟低于 500 ms", maxLatencyMs < 500)
            assertTrue("推理在整个录音期间持续占用 CPU", engineThreads.single().isAlive && burning.get())
            control.press(BusinessCommand.STOP)
            awaitUntil("第二段封存") { coordinator.snapshot.value.state == CaptureState.ARMED && output.recordings.size == 2 }

            val counters = coordinator.snapshot.value.counters
            assertEquals(0L, counters.droppedStale)
            assertEquals(0L, counters.droppedGate)
            assertEquals(0L, counters.discardedQuarantineSamples)
            assertEquals(155L * frameSamples, counters.writtenSamples)
            assertEquals(155 * frameSamples, stored.written.size)
            assertEquals(Thread.MIN_PRIORITY, engineThreads.single().priority)
        } finally {
            burning.set(false)
            producer?.join(5_000)
            appScope.cancel()
            runtime.close()
            appExecutor.shutdownNow()
            appExecutor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun recordSegment(rig: CaptureRig, frames: Int) {
        rig.control.press(BusinessCommand.START)
        rig.settle()
        rig.capture.verifiedRoute(epoch = 1)
        rig.settle()
        repeat(frames) {
            rig.capture.frames(samples = frameSamples, epoch = 1)
            rig.advance(20)
        }
        rig.control.press(BusinessCommand.STOP)
        rig.settle()
    }

    @Test
    fun `虚拟时间：第一段正在转录时录第二段，每一帧都写入、计数里没有任何丢弃，两段随后按先后转完`() = runTest {
        val rig = CaptureRig(this)
        val library = InMemoryRecordLibrary()
        val adapter = FakeTranscriptionAdapter()
        val engineGate = CompletableDeferred<Unit>()
        adapter.hook = { engineGate.await() } // 引擎“正在算”：占着唯一的转录名额
        rig.output.forwardTo = RecordPipeline(library, adapter, rig.clock, backgroundScope)
        rig.coordinator.enableControlMode()
        rig.settle()

        recordSegment(rig, frames = 50)
        assertEquals("第一段已封存，转录开始并卡在引擎里", 1, adapter.requests.size)

        recordSegment(rig, frames = 200)

        val counters = rig.coordinator.snapshot.value.counters
        assertEquals("旧会话丢弃", 0L, counters.droppedStale)
        assertEquals("闸门丢弃", 0L, counters.droppedGate)
        assertEquals("Quarantine 里没有被丢的采样", 0L, counters.discardedQuarantineSamples)
        assertEquals(0L, counters.quarantinedSamples)
        assertEquals("送出多少写入多少", 250L * frameSamples, counters.writtenSamples)
        val (first, second) = rig.output.recordings
        assertEquals(CaptureStatus.COMPLETE, second.result.status)
        assertEquals("存储端也确实收到了每一个采样", 250 * frameSamples, rig.store.written.size)
        assertEquals("转录卡着的时候，第二段只排队，不抢名额", 1, adapter.requests.size)
        assertEquals(1, adapter.maxConcurrent)

        engineGate.complete(Unit)
        rig.settle()
        assertEquals("先录的先转", listOf(first.result.recordId, second.result.recordId), adapter.requests.map { it.recordId })
        for (sealed in listOf(first, second)) {
            assertEquals("${sealed.result.recordId} 的第一条 Revision", 1, library.get(sealed.result.recordId)!!.revisions.size)
        }
    }

    @Test
    fun `真线程：转录引擎把线程占住，协调器、流水线与调度器邮箱同在一条线程上，新录音的每一帧仍然写入，一帧不丢`() {
        var appThread: Thread? = null
        val appExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "app-single-thread").apply { isDaemon = true }.also { appThread = it }
        }
        val appScope = CoroutineScope(SupervisorJob() + appExecutor.asCoroutineDispatcher())
        val clock = FakeClock(TestCoroutineScheduler())
        val control = FakeControlAdapter(clock)
        val capture = FakeCaptureAdapter()
        val store = FakeRecordingStore()
        val output = CollectingOutput()
        val coordinator = CaptureCoordinator(
            control = control,
            capture = capture,
            store = store,
            output = output,
            clock = clock,
            ids = SequentialIds(),
            config = CaptureConfig(),
            scope = appScope,
            adapterDispatcher = Dispatchers.IO,
        )
        val library = InMemoryRecordLibrary()
        val engineStarted = CopyOnWriteArrayList<Thread>()
        val releaseEngine = CountDownLatch(1)
        val adapter = FakeTranscriptionAdapter().apply {
            // 阻塞，不是挂起：占住调用它的那条线程，就像原生推理
            hook = {
                engineStarted += Thread.currentThread()
                releaseEngine.await()
            }
        }
        val runtime = TranscriptionRuntime(library, adapter, clock, appScope)
        output.forwardTo = RecordPipeline(library, runtime.scheduler, appScope)
        try {
            coordinator.enableControlMode()
            awaitUntil("进入待命") { coordinator.snapshot.value.state == CaptureState.ARMED }

            recordSegmentLive(control, coordinator, capture, output, frames = 25, expectedRecordings = 1)
            awaitUntil("第一段的转录开始，卡在引擎里") { engineStarted.isNotEmpty() }
            val engineThread = engineStarted.single()
            assertEquals("引擎调用在最低优先级的线程上", Thread.MIN_PRIORITY, engineThread.priority)
            assertNotEquals("引擎调用不在协调器那条线程上", appThread, engineThread)

            // 引擎仍然卡着：录第二段，300 帧（6 秒的音频，加速送入）
            recordSegmentLive(control, coordinator, capture, output, frames = 300, expectedRecordings = 2)
            assertEquals("第二段录完时引擎仍然卡着：录音没有等转录", 1L, releaseEngine.count)

            val counters = coordinator.snapshot.value.counters
            assertEquals("旧会话丢弃", 0L, counters.droppedStale)
            assertEquals("闸门丢弃", 0L, counters.droppedGate)
            assertEquals(0L, counters.discardedQuarantineSamples)
            assertEquals("送出多少写入多少", 325L * frameSamples, counters.writtenSamples)
            assertEquals(CaptureStatus.COMPLETE, output.recordings[1].result.status)
            assertEquals("存储端也确实收到了每一个采样", 325 * frameSamples, store.written.size)

            releaseEngine.countDown()
            awaitUntil("两段都转完") {
                output.recordings.all { sealed -> runBlocking { library.get(sealed.result.recordId) }?.revisions?.size == 1 }
            }
            assertEquals("一直只有一个引擎调用在跑", 1, adapter.maxConcurrent)
        } finally {
            releaseEngine.countDown()
            appScope.cancel()
            runtime.close()
            appExecutor.shutdownNow()
            appExecutor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun recordSegmentLive(
        control: FakeControlAdapter,
        coordinator: CaptureCoordinator,
        capture: FakeCaptureAdapter,
        output: CollectingOutput,
        frames: Int,
        expectedRecordings: Int,
    ) {
        control.press(BusinessCommand.START)
        awaitUntil("开始后等路由确认") {
            coordinator.snapshot.value.state == CaptureState.ROUTING && runCatching { capture.sink(expectedRecordings - 1) }.isSuccess
        }
        capture.verifiedRoute(epoch = 1)
        awaitUntil("路由确认后录音中") { coordinator.snapshot.value.state == CaptureState.RECORDING }
        repeat(frames) {
            capture.frames(samples = frameSamples, epoch = 1)
            Thread.sleep(1)
        }
        control.press(BusinessCommand.STOP)
        awaitUntil("封存完成，回到待命") {
            coordinator.snapshot.value.state == CaptureState.ARMED && output.recordings.size == expectedRecordings
        }
    }

    private fun awaitUntil(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("等不到：$what（${timeoutMs} ms）")
            Thread.sleep(5)
        }
    }
}
