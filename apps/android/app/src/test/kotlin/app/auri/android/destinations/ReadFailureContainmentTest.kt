@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.android.destinations

import app.auri.android.home.CoordinatorControls
import app.auri.android.netguard.SourceText
import app.auri.capture.contract.CaptureState
import app.auri.capture.coordinator.CaptureCoordinator
import app.auri.capture.pipeline.RecordPipeline
import app.auri.capture.testing.CollectingOutput
import app.auri.capture.testing.FakeCaptureAdapter
import app.auri.capture.testing.FakeClock
import app.auri.capture.testing.FakeRecordingStore
import app.auri.capture.testing.FakeTranscriptionAdapter
import app.auri.capture.testing.InMemoryRecordLibrary
import app.auri.capture.testing.SequentialIds
import app.auri.platform.control.ScreenControlAdapter
import app.auri.sync.destinations.DestinationService
import app.auri.sync.destinations.DispatchItem
import app.auri.sync.destinations.DispatchStore
import app.auri.sync.discovery.GatewayLocator
import app.auri.sync.gateway.Destination
import app.auri.sync.gateway.Intent
import app.auri.sync.gateway.SendMode
import app.auri.sync.testing.CollectingLog
import app.auri.sync.testing.DispatchRig
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 评审 H4：发送账（SQLite）读失败不能杀掉进程。视图模型的作用域是 SupervisorJob 加线程池、没有异常处理器：急切的 stateIn 里逃出来的异常
 * 会走到线程的默认处理器，在 Android 上就是整个进程退出——包括正在录音的前台服务。这里的作用域与线上一样的形状，只是多挂了一个处理器，
 * 把“逃出来的异常”记下来断言一个都没有；发送账是真的，只在读的几个入口按开关抛异常；录音协调器是真的（假的只有系统边缘）。
 */
class ReadFailureContainmentTest {
    /** 读发送账的入口按开关抛异常；异常信息故意带着路径与正文，日志里不许出现。其余原样交给真的发送账。 */
    private class FlakyDispatch(private val inner: DispatchStore) : DispatchStore by inner {
        @Volatile
        var failing = false

        private fun check() {
            if (failing) throw IllegalStateException("database disk image is malformed: /data/user/0/app.auri.android/files/secret-draft.db")
        }

        override suspend fun destinations(): List<Destination> {
            check()
            return inner.destinations()
        }

        override suspend fun items(limit: Int): List<DispatchItem> {
            check()
            return inner.items(limit)
        }

        override suspend fun itemsFor(destinationId: String, limit: Int): List<DispatchItem> {
            check()
            return inner.itemsFor(destinationId, limit)
        }

        override suspend fun defaultIntent(): Intent {
            check()
            return inner.defaultIntent()
        }
    }

    private class Escapes {
        val failures = CopyOnWriteArrayList<Throwable>()
    }

    /**
     * 线上的形状：SupervisorJob 加调度器；逃出作用域的异常交给处理器（线上没有处理器，那就是进程退出）。
     * 挂在 backgroundScope 底下：不管测试是通过还是断言失败，结束时它都被取消——否则一个还在重试的视图模型会让 runTest 收尾时永远等不到“空闲”，红灯变成卡死。
     */
    private fun TestScope.viewModelScope(escapes: Escapes) =
        CoroutineScope(backgroundScope.coroutineContext + CoroutineExceptionHandler { _, failure -> escapes.failures += failure })

    /** 真的协调器与管线，系统边缘是假的（做法同 HomeFlowTest）。 */
    private class Capture(test: TestScope) {
        val clock = FakeClock(test.testScheduler)
        val screen = ScreenControlAdapter(clock, SequentialIds(1))
        val adapter = FakeCaptureAdapter()
        val library = InMemoryRecordLibrary()
        val pipeline = RecordPipeline(library, FakeTranscriptionAdapter(), clock, test.backgroundScope)
        val output = CollectingOutput().also { it.forwardTo = pipeline }
        val coordinator = CaptureCoordinator(
            control = screen,
            capture = adapter,
            store = FakeRecordingStore(),
            output = output,
            clock = clock,
            ids = SequentialIds(2),
            scope = test.backgroundScope,
            adapterDispatcher = StandardTestDispatcher(test.testScheduler),
        )
        val controls = CoordinatorControls(coordinator, screen)
        val state: CaptureState get() = coordinator.snapshot.value.state

        fun startRecording(test: TestScope) {
            controls.start()
            test.runCurrent()
            adapter.verifiedRoute()
            test.runCurrent()
        }
    }

    private fun DispatchRig.serviceOver(store: DispatchStore) =
        DestinationService(pairing, lan.factory, GatewayLocator(lan.factory, discovery, log), store, jobs, authenticator, clock, log)

    private fun TestScope.waitForRetry() {
        advanceTimeBy(READ_RETRY_BASE_MS + 1)
        runCurrent()
    }

    // ---- 三个读发送账的页面：读失败不逃出作用域，页面如实说读不到，读得到之后自动恢复 ----

    @Test
    fun `待发送页读不到发送账：错误不逃出作用域，页面如实说读不到，录音照常进行，读得到之后自动读回来`() = runTest {
        val rig = DispatchRig(this)
        rig.createDestination("写作助手", SendMode.AUTO_SEND)
        rig.transcribed("等这条文字读得出来")
        val flaky = FlakyDispatch(rig.dispatch).also { it.failing = true }
        val capture = Capture(this).also { it.startRecording(this) }
        assertEquals("先在录音", CaptureState.RECORDING, capture.state)
        val escapes = Escapes()
        val scope = viewModelScope(escapes)

        val viewModel = SendReviewViewModel(flaky, LibraryItemTexts(rig.library), rig.clock, {}, scope, rig.policy.states(), rig.log)
        runCurrent()

        assertTrue("读失败逃出了作用域（线上就是进程退出）：${escapes.failures}", escapes.failures.isEmpty())
        assertTrue("页面要如实说读不到，不能一直空白装作没事", viewModel.state.value.unreadable)
        assertTrue("没读到过东西：items 是空的，但页面已经说了读不到，空不是“没有文字”", viewModel.state.value.items.isEmpty())
        assertEquals("录音没受影响", CaptureState.RECORDING, capture.state)
        assertTrue("日志有一行事件名加异常类名：${rig.log.lines}", "send.review.read.failed IllegalStateException" in rig.log.lines)
        assertFalse("日志里没有异常信息（路径、文字）：${rig.log.lines}", rig.log.lines.any { "secret-draft" in it || "malformed" in it })

        flaky.failing = false
        waitForRetry()

        assertFalse("读到了：恢复", viewModel.state.value.unreadable)
        assertEquals("等这条文字读得出来", viewModel.state.value.items.single().text)
        assertTrue(escapes.failures.isEmpty())
        capture.controls.stop()
        runCurrent()
        assertEquals("录音能照常停下", CaptureState.DISARMED, capture.state)
    }

    @Test
    fun `待发送页读到过又读不到了：留着最近读到的那些，同时说读不到；恢复之后换成新的`() = runTest {
        val rig = DispatchRig(this)
        rig.createDestination("写作助手", SendMode.AUTO_SEND)
        rig.transcribed("第一条")
        val flaky = FlakyDispatch(rig.dispatch)
        val escapes = Escapes()
        val scope = viewModelScope(escapes)
        val viewModel = SendReviewViewModel(flaky, LibraryItemTexts(rig.library), rig.clock, {}, scope, rig.policy.states(), rig.log)
        runCurrent()
        assertEquals(listOf<String?>("第一条"), viewModel.state.value.items.map { it.text })

        flaky.failing = true
        rig.transcribed("第二条") // 发送账变了，页面去重读，读失败
        runCurrent()

        assertTrue(escapes.failures.isEmpty())
        assertTrue(viewModel.state.value.unreadable)
        assertEquals("读不到时留着最近读到的", listOf<String?>("第一条"), viewModel.state.value.items.map { it.text })
        flaky.failing = false
        waitForRetry()
        assertFalse(viewModel.state.value.unreadable)
        assertEquals(setOf<String?>("第一条", "第二条"), viewModel.state.value.items.map { it.text }.toSet())
    }

    @Test
    fun `目的地页读不到本机缓存：同样不逃出作用域，页面如实说读不到，恢复后读回来`() = runTest {
        val rig = DispatchRig(this)
        rig.createDestination("写作助手", SendMode.AUTO_SEND)
        val flaky = FlakyDispatch(rig.dispatch).also { it.failing = true }
        val escapes = Escapes()
        val scope = viewModelScope(escapes)

        val viewModel = DestinationsViewModel(rig.serviceOver(flaky), scope, rig.log)
        runCurrent()

        assertTrue("读失败逃出了作用域：${escapes.failures}", escapes.failures.isEmpty())
        assertTrue(viewModel.state.value.unreadable)
        assertTrue("destinations.read.failed IllegalStateException" in rig.log.lines)
        assertFalse("日志里没有异常信息：${rig.log.lines}", rig.log.lines.any { "secret-draft" in it || "malformed" in it })

        flaky.failing = false
        waitForRetry()

        assertFalse(viewModel.state.value.unreadable)
        assertEquals(listOf("写作助手"), viewModel.state.value.destinations.map { it.name })
    }

    @Test
    fun `详情页读不到本机缓存：不说“找不到这个目的地”，说读不到；恢复后读回来`() = runTest {
        val rig = DispatchRig(this)
        val destination = rig.createDestination("写作助手", SendMode.PREVIEW_CONFIRM)
        val flaky = FlakyDispatch(rig.dispatch).also { it.failing = true }
        val escapes = Escapes()
        val scope = viewModelScope(escapes)

        val viewModel = DestinationDetailViewModel(destination.destinationId, rig.serviceOver(flaky), LibraryItemTexts(rig.library), scope, rig.log)
        runCurrent()

        assertTrue("读失败逃出了作用域：${escapes.failures}", escapes.failures.isEmpty())
        assertTrue(viewModel.state.value.unreadable)
        assertFalse("还没读到过：loaded 必须是 false，界面才不会说“找不到这个目的地（可能已被删除）”", viewModel.state.value.loaded)

        flaky.failing = false
        waitForRetry()

        assertFalse(viewModel.state.value.unreadable)
        assertTrue(viewModel.state.value.loaded)
        assertEquals("写作助手", viewModel.state.value.destination?.name)
    }

    // ---- 包装本身：退避、不放弃、只包上游 ----

    @Test
    fun `一直读不到：重试间隔翻倍增长到上限，从不放弃；读到一次就从头算`() = runTest {
        val reads = AtomicInteger()
        var script = "fail"
        val log = CollectingLog()
        val source = flow<Int> {
            reads.incrementAndGet()
            when (script) {
                "fail" -> throw IllegalStateException("boom")
                else -> {
                    emit(1)
                    throw IllegalStateException("boom again") // 读到了一次，随后又坏了
                }
            }
        }
        val seen = CopyOnWriteArrayList<Int>()
        backgroundScope.launch { source.containReadFailures("t", log) { emit(-1) }.collect { seen += it } }
        runCurrent()
        assertEquals("立刻读第一次", 1, reads.get())
        assertEquals("读不到时发出了“读不到”的值", listOf(-1), seen.toList())

        // 间隔 2s、4s、8s、16s、30s（32s 被上限压到 30s）、30s……
        for ((n, gap) in listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L).withIndex()) {
            advanceTimeBy(gap - 1)
            runCurrent()
            assertEquals("第 ${n + 2} 次读要等满 ${gap}ms，现在才过了 ${gap - 1}", n + 1, reads.get())
            advanceTimeBy(1)
            runCurrent()
            assertEquals("到点就读", n + 2, reads.get())
        }
        assertEquals(7, reads.get())

        // 下一次读到一次、随后又坏了：退避从头算，之后只等 2s，不是接着之前的 30s
        script = "ok-then-fail"
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals("读到的那一个值发出来了，随后又是“读不到”", listOf(1, -1), seen.takeLast(2))
        assertEquals(8, reads.get())
        advanceTimeBy(READ_RETRY_BASE_MS - 1)
        runCurrent()
        assertEquals(8, reads.get())
        advanceTimeBy(1)
        runCurrent()
        assertEquals("读到过一次：只等 ${READ_RETRY_BASE_MS}ms 就重试", 9, reads.get())
        assertEquals("每次读失败正好一行日志：${log.lines}", reads.get(), log.lines.size)
        assertTrue(log.lines.all { it == "t.read.failed IllegalStateException" })
    }

    @Test
    fun `上游正常结束就正常结束；Error 不当作读失败吞掉，照常抛出`() = runTest {
        val log = CollectingLog()
        val finished = mutableListOf<Int>()
        flow { emit(1) }.containReadFailures("t", log) { emit(-1) }.collect { finished += it }
        assertEquals("正常结束：不重试、不发“读不到”", listOf(1), finished)
        assertTrue(log.lines.isEmpty())

        try {
            flow<Int> { throw AssertionError("fatal") }.containReadFailures("t", log) { emit(-1) }.collect { }
            fail("Error 不该被当成读失败吞掉")
        } catch (expected: AssertionError) {
            assertEquals("fatal", expected.message)
        }
        assertTrue(log.lines.isEmpty())
    }

    @Test
    fun `上游自己抛出的取消异常照常往外传：不当成读失败去重试`() = runTest {
        val reads = AtomicInteger()
        val log = CollectingLog()
        var thrown: Throwable? = null
        backgroundScope.launch {
            try {
                flow<Int> {
                    reads.incrementAndGet()
                    throw CancellationException("上游自己取消了")
                }.containReadFailures("t", log) { emit(-1) }.collect { }
            } catch (cancelled: CancellationException) {
                thrown = cancelled
            }
        }
        runCurrent()
        advanceTimeBy(READ_RETRY_MAX_MS * 2)
        runCurrent()

        assertTrue("取消异常传给了收集者", thrown is CancellationException)
        assertEquals("没有重试", 1, reads.get())
        assertTrue("也没有当成读失败记日志：${log.lines}", log.lines.isEmpty())
    }

    @Test
    fun `收集者取消就停：重试等待中被取消，不再读`() = runTest {
        val reads = AtomicInteger()
        val job = backgroundScope.launch {
            flow<Int> {
                reads.incrementAndGet()
                if (reads.get() == 1) throw IllegalStateException("boom")
                awaitCancellation()
            }.containReadFailures("t", CollectingLog()) { emit(-1) }.collect { }
        }
        runCurrent()
        job.cancel()
        advanceTimeBy(READ_RETRY_MAX_MS * 2)
        runCurrent()

        assertEquals("取消之后没有再读", 1, reads.get())
    }

    // ---- 文案与接线 ----

    @Test
    fun `读不到的那句话：说清读不到、不一定是最新的、不代表没有、会自动重试；没有“已处理”一类的词`() {
        val note = describeUnreadable()

        for (needle in listOf("读不到", "可能不是最新的", "并不代表没有", "自动重试")) assertTrue("缺“$needle”：$note", needle in note)
        for (word in PROCESSED_WORDS) assertFalse("话里有“$word”：$note", word in note)
    }

    @Test
    fun `三个页面都把读不到的那句话摆出来；它们读的状态里都有 unreadable`() {
        val screens = File("..").resolve("app/src/main/kotlin/app/auri/android/ui")
        for (name in listOf("SendReviewScreen.kt", "DestinationsScreen.kt", "DestinationDetailScreen.kt")) {
            val source = SourceText.withoutCommentsAndStrings(File(screens, name).readText())
            assertTrue("$name 没有在 state.unreadable 时显示 describeUnreadable()", Regex("""state\.unreadable[^\n]*describeUnreadable\(\)""").containsMatchIn(source))
        }
    }
}
