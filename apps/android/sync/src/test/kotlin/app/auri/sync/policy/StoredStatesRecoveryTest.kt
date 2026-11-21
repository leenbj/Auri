@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.sync.policy

import app.auri.sync.pairing.GatewayIdentity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 复评 R9：存档读到一半数据库出了暂时的故障。以前：这一次读回答 Closed（对），但之后数据库好了、没有人再改设置，
 * 就没有任何东西触发下一次读——引擎与界面永远停在“不许发”，而用户的授权其实好好地存着（接口文档写的是“恢复后发出真实值”，实现没做到）。
 * 现在：读不出来先**立刻**回答 Closed，再按退避重试，读到了就发出读到的值；新的变动打断重试、从头读。
 * 读用脚本，时间用虚拟时钟：退避的每一步都能精确断言，不睡真的觉。
 */
class StoredStatesRecoveryTest {
    private val granted = StoredDataFlow(grantedTo = GatewayIdentity("fa1b98ec-1bfb-53b3-85a3-4d36b680fe6e", "a".repeat(64)), strictLocal = false)
    private val strict = granted.copy(strictLocal = true)

    /** 按脚本回答的读：每次被读时记下虚拟时钟；null = 这一次读不出来。脚本用完还被读就是测试有错。 */
    private class ScriptedReads(private val scope: TestScope, vararg answers: StoredDataFlow?) {
        private val script = answers.toMutableList()
        val readAt = mutableListOf<Long>()

        suspend fun read(): StoredDataFlow? {
            readAt += scope.testScheduler.currentTime
            check(script.isNotEmpty()) { "读得比脚本多：第 ${readAt.size} 次" }
            return script.removeAt(0)
        }
    }

    @Test
    fun `读不出来：先立刻回答 Closed，再按 2、4、8、16、30、30 秒退避重试，读到了就发出读到的值，之后不再多读`() = runTest {
        val reads = ScriptedReads(this, null, null, null, null, null, null, granted)
        val seen = mutableListOf<StoredDataFlow>()

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { MutableStateFlow(0L).recoveringStoredStates(reads::read).toList(seen) }
        runCurrent()
        assertEquals("读不出来的当下就必须是最保守的回答，不能等", listOf(StoredDataFlow.Closed), seen)

        advanceTimeBy(100_000)
        runCurrent()

        assertEquals("读不出来期间只发一次 Closed；读到之后发出真实值（没有人再改设置也要发）", listOf(StoredDataFlow.Closed, granted), seen)
        assertEquals("退避：2 秒起翻倍到 30 秒封顶", listOf(0L, 2_000L, 6_000L, 14_000L, 30_000L, 60_000L, 90_000L), reads.readAt)
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals("读到之后不再重试", 7, reads.readAt.size)
    }

    @Test
    fun `健康的读不会凭空发出 Closed：订阅时读一次，之后每次变动读一次，发的都是读到的值`() = runTest {
        val reads = ScriptedReads(this, StoredDataFlow.Unset, granted, strict)
        val changes = MutableStateFlow(0L)
        val seen = mutableListOf<StoredDataFlow>()

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { changes.recoveringStoredStates(reads::read).toList(seen) }
        runCurrent()
        changes.value = 1
        runCurrent()
        changes.value = 2
        runCurrent()

        assertEquals(listOf(StoredDataFlow.Unset, granted, strict), seen)
        assertEquals(3, reads.readAt.size)
    }

    @Test
    fun `评审说的那个缺口：已经读到过真值，之后某次变动时读失败，数据库好了以后没有新变动也要回到真值`() = runTest {
        val reads = ScriptedReads(this, granted, null, null, granted)
        val changes = MutableStateFlow(0L)
        val seen = mutableListOf<StoredDataFlow>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { changes.recoveringStoredStates(reads::read).toList(seen) }
        runCurrent()
        assertEquals(listOf(granted), seen)

        changes.value = 1 // 这一次读碰上暂时的故障
        runCurrent()
        assertEquals("故障的当下：关门", listOf(granted, StoredDataFlow.Closed), seen)

        advanceTimeBy(10_000) // 之后再没有任何人改设置；数据库在第三次读时好了
        runCurrent()

        assertEquals("恢复后回到真实值：不能停在 Closed 上等一个不会来的变动", listOf(granted, StoredDataFlow.Closed, granted), seen)
    }

    @Test
    fun `故障期间又来了新的变动：旧的重试作废、立刻按新的变动重读，旧的重试不会再醒来多读一次`() = runTest {
        val reads = ScriptedReads(this, null, strict)
        val changes = MutableStateFlow(0L)
        val seen = mutableListOf<StoredDataFlow>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { changes.recoveringStoredStates(reads::read).toList(seen) }
        runCurrent()
        advanceTimeBy(1_000)

        changes.value = 1 // 第一次读失败后的重试还有 1 秒才醒
        runCurrent()

        assertEquals(listOf(StoredDataFlow.Closed, strict), seen)
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals("旧的重试（本该在 2 秒时醒）已被新的变动取代，不能再读", listOf(0L, 1_000L), reads.readAt)
    }

    @Test
    fun `收集者取消之后重试也停：不会在没人听的时候一直读数据库`() = runTest {
        val reads = ScriptedReads(this, null, null, null, null, null, null, null, null)
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { MutableStateFlow(0L).recoveringStoredStates(reads::read).toList(mutableListOf()) }
        runCurrent()
        advanceTimeBy(7_000) // 读了 0、2、6 秒三次

        job.cancel()
        advanceTimeBy(600_000)
        runCurrent()

        assertEquals(listOf(0L, 2_000L, 6_000L), reads.readAt)
    }
}
