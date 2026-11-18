package app.auri.platform.service

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingPromotionTest {
    @Test
    fun `refused stop is deferred until foreground promotion`() {
        val calls = mutableListOf<String>()
        val gate = ForegroundStopGate(
            requestStop = { calls += "stop request"; throw IllegalStateException("background") },
            stopPromoted = { calls += "stop service" },
        )
        gate.started()
        gate.stop()
        assertEquals(listOf("stop request"), calls)
        gate.promoted()
        gate.promoted()
        assertEquals(listOf("stop request", "stop service"), calls)
    }

    @Test
    fun `a refused stop request must not tear down a service awaiting promotion`() {
        val source = File("src/main/kotlin/app/auri/platform/service/AndroidForegroundServiceController.kt").readText()
        val fallback = source.substringAfter("override fun stop()").substringAfter("catch (e: IllegalStateException)")
        assertFalse("refused stop must wait for foreground acknowledgement", fallback.contains("context.stopService("))
    }
}
