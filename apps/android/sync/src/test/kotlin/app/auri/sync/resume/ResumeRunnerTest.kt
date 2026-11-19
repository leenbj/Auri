package app.auri.sync.resume

import app.auri.sync.testing.CollectingLog
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统把应用叫醒之后跑的那一轮：等启动完成、跑一轮（有时限，不能一直占着系统给后台任务的时间）、告诉系统“还有没有活”。
 * 返回值就是 JobService.jobFinished 的“请稍后再叫醒我”。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResumeRunnerTest {
    private val steps = CopyOnWriteArrayList<String>()
    private val log = CollectingLog()

    private fun runner(
        ready: suspend () -> Unit = { steps += "ready" },
        pass: suspend () -> Unit = { steps += "pass" },
        needed: suspend () -> Boolean = { steps += "needed"; false },
        limitMs: Long = 5 * 60_000L,
    ) = ResumeRunner(ready, pass, needed, limitMs, log)

    @Test
    fun `先等启动做完，再跑一轮，最后问还需不需要叫醒`() = runTest {
        val started = CompletableDeferred<Unit>()
        val result = async { runner(ready = { started.await(); steps += "ready" }).run() }
        runCurrent()
        assertEquals("启动没做完：这一轮还不能跑", emptyList<String>(), steps.toList())

        started.complete(Unit)
        result.await()

        assertEquals(listOf("ready", "pass", "needed"), steps.toList())
    }

    @Test
    fun `返回的是“还需要叫醒”的判断：还有活就请系统稍后再叫醒，没活了就不请`() = runTest {
        assertTrue(runner(needed = { true }).run())
        assertFalse(runner(needed = { false }).run())
    }

    @Test
    fun `这一轮卡住了：到时限就取消它（不一直占着系统的执行时间），照常给出判断`() = runTest {
        var cancelled = false
        val result = async {
            runner(pass = { try { awaitCancellation() } finally { cancelled = true } }, needed = { true }, limitMs = 60_000).run()
        }
        advanceTimeBy(59_999)
        runCurrent()
        assertTrue("时限没到：还在跑", result.isActive)

        advanceTimeBy(1)
        runCurrent()

        assertTrue(result.await())
        assertTrue("卡住的那一轮被取消了", cancelled)
        assertEquals(60_000, currentTime)
    }

    @Test
    fun `这一轮出了意外：记一条不含内容的日志，请系统稍后再叫醒`() = runTest {
        val result = runner(pass = { throw IllegalStateException("secret-looking-detail") }, needed = { false }).run()

        assertTrue("意外之后不能当作没活了", result)
        assertEquals(listOf("resume.failed IllegalStateException"), log.lines.toList())
    }

    @Test
    fun `被系统叫停（外部取消）：取消照常向上传，不再做收尾判断`() = runTest {
        val job = async { runner(pass = { awaitCancellation() }, needed = { steps += "needed"; true }).run() }
        runCurrent()

        job.cancel()
        runCurrent()

        assertTrue(job.isCancelled)
        assertEquals("只有启动那一步，没有收尾判断", listOf("ready"), steps.toList())
        assertEquals("取消不是故障：不记日志，也不能当作“出了意外”请系统再来", emptyList<String>(), log.lines.toList())
    }

    @Test
    fun `被系统叫停时正在做收尾判断：同样把取消向上传，不记日志`() = runTest {
        val job = async { runner(needed = { steps += "needed"; awaitCancellation() }).run() }
        runCurrent()
        assertEquals(listOf("ready", "pass", "needed"), steps.toList())

        job.cancel()
        runCurrent()

        assertTrue(job.isCancelled)
        assertEquals(emptyList<String>(), log.lines.toList())
    }
}
