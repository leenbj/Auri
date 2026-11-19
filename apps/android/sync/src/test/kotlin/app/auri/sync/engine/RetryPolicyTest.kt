package app.auri.sync.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {
    @Test
    fun `退避时间逐次翻倍并且有上限`() {
        val policy = RetryPolicy(baseMs = 5_000, maxMs = 60_000)

        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L), (1..6).map { policy.delayMs(it) })
    }

    @Test
    fun `次数再大也不会溢出，永远不超过上限`() {
        val policy = RetryPolicy()

        assertEquals(15 * 60_000L, policy.delayMs(Int.MAX_VALUE))
        assertEquals(15 * 60_000L, policy.delayMs(1_000))
    }

    @Test
    fun `不合法的参数与次数被拒绝`() {
        assertTrue(runCatching { RetryPolicy(baseMs = 0) }.isFailure)
        assertTrue(runCatching { RetryPolicy(baseMs = 10, maxMs = 5) }.isFailure)
        assertTrue(runCatching { RetryPolicy().delayMs(0) }.isFailure)
    }
}
