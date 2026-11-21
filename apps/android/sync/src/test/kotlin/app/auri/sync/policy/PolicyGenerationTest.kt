package app.auri.sync.policy

import app.auri.sync.testing.InMemoryDataFlowPolicy
import app.auri.sync.testing.SyncSamples
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 再评 S3：授权设置的世代是“问放行”与“请求交出去”之间那条缝的标尺。这里钉住它的三个性质——
 * 开始改就变（改的途中比对必失败）、落定后再变（改的途中开始问放行的事后比对也失败）、抛了也算落定（写了一半的设置同样不放行在途的请求）。
 */
class PolicyGenerationTest {
    @Test
    fun `开始改设置的那一刻世代就变了：已经开始改、还没落定时，请求前的比对必失败`() {
        val generation = PolicyGeneration()
        val askedBefore = generation.value

        generation.changing()

        assertNotEquals(askedBefore, generation.value)
    }

    @Test
    fun `落定之后世代又变一次：改的途中开始问放行的（读到的可能还是旧值），事后比对同样失败`() {
        val generation = PolicyGeneration()
        generation.changing()
        val askedDuringTheChange = generation.value

        generation.changed()

        assertNotEquals("途中开始问的那张放行不能在设置落定之后还算数", askedDuringTheChange, generation.value)
    }

    @Test
    fun `改设置时抛了也算落定：写了一半的设置不能让在途的请求照发`() {
        val generation = PolicyGeneration()
        val before = generation.value

        runCatching { generation.around<Unit> { error("写到一半断电") } }

        assertEquals("前后各一次，抛了也不例外", before + 2, generation.value)
    }

    @Test
    fun `没有人改设置世代就不动：读它不是改动`() {
        val generation = PolicyGeneration()
        val first = generation.value

        assertEquals(first, generation.value)
        assertEquals(first, generation.value)
    }

    @Test
    fun `内存替身的每一种改动都动世代，读不动：测试里的交错与真实实现的语义一致`() = runBlocking {
        val policy = InMemoryDataFlowPolicy()
        val gateway = SyncSamples.pairedGateway().identity
        val changes = listOf<suspend () -> Unit>(
            { policy.revokeGrant() },
            { policy.grantTo(gateway) },
            { policy.setStrictLocal(true) },
            { policy.forgetGrantExcept(null) },
        )
        for (change in changes) {
            val before = policy.generation
            change()
            assertNotEquals("每次改设置世代都要变", before, policy.generation)
        }

        val settled = policy.generation
        policy.stored()
        policy.current()
        assertEquals("读设置不动世代", settled, policy.generation)
    }
}
