package app.auri.platform.system

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ResourceChangesTest {
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
