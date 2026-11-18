package app.auri.platform.system

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ResourceChangesTest {
    @Test
    fun `注册未完成就失败时不发送提前到达的温度信号`() = runBlocking {
        val seen = CopyOnWriteArrayList<Unit>()
        val delivered = CountDownLatch(1)
        var thermalRemoved = 0
        val changes = resourceChanges(
            thermal = { signal -> signal(); AutoCloseable { thermalRemoved++ } },
            battery = { delivered.await(1, TimeUnit.SECONDS); error("injected receiver failure") },
        )

        async(Dispatchers.Default) {
            try {
                changes.collect { seen += it; delivered.countDown() }
            } catch (_: IllegalStateException) {
                // 预期的注册失败。
            }
        }.await()

        assertEquals(emptyList<Unit>(), seen)
        assertEquals(1, thermalRemoved)
    }

    @Test
    fun `第二项注册失败时释放已经注册的温度监听`() {
        var thermalRemoved = 0
        val changes = resourceChanges(
            thermal = { AutoCloseable { thermalRemoved++ } },
            battery = { error("injected registration failure") },
        )

        assertThrows(IllegalStateException::class.java) { runBlocking { changes.first() } }
        assertEquals(1, thermalRemoved)
    }

    @Test
    fun `停止收集时两个已经注册的监听都释放`() = runBlocking {
        val closed = mutableListOf<String>()
        val changes = resourceChanges(
            thermal = { signal -> signal(); AutoCloseable { closed += "thermal" } },
            battery = { AutoCloseable { closed += "battery" } },
        )

        changes.first()

        assertEquals(listOf("battery", "thermal"), closed)
    }
}
