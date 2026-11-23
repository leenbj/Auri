@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.sync.policy

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第四轮 1：SQLite 一侧的写入协议（`SqliteDataFlowPolicy.write`：取凭据、开窗口、写块换到单线程的数据库线程上、两处 finally 关窗口）。
 * 真实现要 Android 的 SQLiteDatabase，JVM 跑不了；这里在**同形**的协议上摆出每种“写块没按常理走完”的情形，
 * 接线（生产源码确实是这个形状）由 :app 的 PolicyWriteWiringTest 钉住。
 *
 * 两个方向的错都要拦：窗口**关得太早**（落定在写提交之前——请求带着旧授权出去）与窗口**永远关不上**（写块没跑起来、没人关——之后每个请求都被拦，应用从此什么都发不出去）。
 */
class PolicyWriteProtocolTest {
    private val database = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "test-database").apply { isDaemon = true } }
    private val io: CoroutineDispatcher = database.asCoroutineDispatcher()

    @After
    fun stopDatabaseThread() {
        database.shutdownNow()
    }

    /** 与 SqliteDataFlowPolicy.write 同形。 */
    private suspend fun write(generation: PolicyGeneration, change: () -> Unit) {
        val generations = generation.write()
        try {
            generations.changing()
            withContext(io) {
                try {
                    change()
                } finally {
                    generations.changed()
                }
            }
        } finally {
            generations.changed()
        }
    }

    private fun assertWindowClosed(generation: PolicyGeneration, message: String) {
        val first = generation.value
        assertTrue("$message：窗口关上之后世代是非负的稳定值，不是窗口里的章：$first", first >= 0)
        assertEquals("$message：关上之后读世代不是改动", first, generation.value)
    }

    @Test
    fun `正常的写：窗口只在写块提交之后才关上，关上之后世代是稳定的非负值`() = runBlocking {
        val generation = PolicyGeneration()
        val idle = generation.value
        val insideBlock = mutableListOf<Long>()

        write(generation) { insideBlock += generation.value }

        assertTrue("写块执行时窗口开着：读到的是章（负数）：$insideBlock", insideBlock.single() < 0)
        assertWindowClosed(generation, "写完")
        assertEquals("前后各加一", idle + 2, generation.value)
    }

    @Test
    fun `写块抛了：窗口照样关上（写了一半的设置不能让窗口永远开着）`() = runBlocking {
        val generation = PolicyGeneration()

        runCatching { write(generation) { error("写到一半断电") } }

        assertWindowClosed(generation, "写块抛了")
    }

    @Test
    fun `调用方在写块开始之前被取消：写块根本没跑，窗口由外层 finally 关上，不会永远开着`() = runBlocking {
        val generation = PolicyGeneration()
        val busy = CountDownLatch(1)
        val release = CountDownLatch(1)
        database.execute { busy.countDown(); release.await(10, TimeUnit.SECONDS) } // 数据库线程正忙：写块只能排队
        assertTrue(busy.await(10, TimeUnit.SECONDS))
        val ran = AtomicBoolean(false)

        val caller = launch(start = CoroutineStart.UNDISPATCHED) { write(generation) { ran.set(true) } }
        assertTrue("窗口在写开始时就开了（写块还排在队里）", generation.value < 0)
        caller.cancel()
        release.countDown()
        caller.join()

        assertFalse("被取消的写块不能在取消之后跑", ran.get())
        assertWindowClosed(generation, "写块没跑")
    }

    @Test
    fun `写块已经开始之后调用方被取消：写块照样做完（数据库线程不可中断），窗口在它提交之后才关，只关一次`() = runBlocking {
        val generation = PolicyGeneration()
        val idle = generation.value
        val inside = CompletableDeferred<Unit>()
        val go = CountDownLatch(1)
        val committed = AtomicBoolean(false)
        val windowAtCommit = mutableListOf<Long>()

        val caller = launch {
            write(generation) {
                inside.complete(Unit)
                go.await(10, TimeUnit.SECONDS)
                committed.set(true)
                windowAtCommit += generation.value
            }
        }
        inside.await()
        caller.cancel()
        assertTrue("取消了、写块还没提交：窗口必须仍然开着", generation.value < 0)
        go.countDown()
        caller.join()

        assertTrue("写块做完了", committed.get())
        assertTrue("提交的那一刻窗口开着：$windowAtCommit", windowAtCommit.single() < 0)
        assertWindowClosed(generation, "取消之后")
        assertEquals("前后各加一，没有因为两处 finally 加了第三次", idle + 2, generation.value)
    }

    @Test
    fun `凭据的落定是幂等的：重复落定不会替别人的窗口关门，也不会让世代多动`() {
        val generation = PolicyGeneration()
        val first = generation.write()
        val second = generation.write()
        first.changing()
        second.changing()

        first.changed()
        first.changed()
        first.changed()

        val whileSecondIsOpen = generation.value
        assertTrue("第二次写还开着：读到的仍是章", whileSecondIsOpen < 0)
        assertNull(runBlocking { PolicyGeneration.handoff({ generation.value == whileSecondIsOpen }) { "sent" } })

        second.changed()
        assertWindowClosed(generation, "两次都落定")
        assertEquals("两次写各加两次", 4L, generation.value)
    }

    @Test
    fun `没开过窗口的凭据直接落定：之后也开不了窗口（不会留下一个没人关的窗口）`() {
        val generation = PolicyGeneration()
        val ticket = generation.write()

        ticket.changed()
        ticket.changing()

        assertWindowClosed(generation, "已经落定的凭据")
        assertEquals("什么都没发生过", 0L, generation.value)
    }

    @Test
    fun `落定之后的新凭据又能开窗口：拦下不是永久的`() {
        val generation = PolicyGeneration()
        generation.write().apply { changing(); changed() }
        val settled = generation.value
        assertNotEquals(0L, settled)

        val again = generation.write()
        again.changing()
        assertTrue(generation.value < 0)
        again.changed()
        assertWindowClosed(generation, "第二次写")
        assertEquals(settled + 2, generation.value)
    }
}
