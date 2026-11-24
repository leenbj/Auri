package app.auri.android.sync

import app.auri.android.netguard.SourceText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复评 R5 的接线守卫：行为（旧配对的残余一轮不得碰新配对的账）在 :sync 的 StalePassFenceTest 与配对存储的 whileCurrent 测试里摆过，
 * 这里钉住“每一处改缓存/目标的调用都真的在围栏里”——只靠行为测试，新增的一处改账调用忘了套围栏时，没有任何测试会红。
 * 做法同 SyncStatusWiringTest：读生产源码（去掉注释与字符串字面量），把围栏块整块剪掉，剩下的文本里不许再有改账调用。
 */
class PairingFenceWiringTest {
    private val androidDir = File("..")

    private fun code(path: String) = SourceText.withoutCommentsAndStrings(File(androidDir, path).readText()).replace(Regex("""\s+"""), " ")

    /** 剪掉所有以 [opener]（以左花括号结尾）开头的块（含花括号配平），返回剩下的文本与剪掉的块数。 */
    private fun withoutBlocks(text: String, opener: String): Pair<String, Int> {
        require(opener.endsWith("{"))
        var rest = text
        var removed = 0
        while (true) {
            val start = rest.indexOf(opener)
            if (start < 0) return rest to removed
            var depth = 0
            var end = -1
            for (i in start + opener.length - 1 until rest.length) {
                when (rest[i]) {
                    '{' -> depth++
                    '}' -> if (--depth == 0) { end = i + 1; break }
                }
            }
            check(end > 0) { "花括号没有配平：$opener" }
            rest = rest.removeRange(start, end)
            removed++
        }
    }

    private val ledgerWrites = Regex("""additions\.(useGateway|gatewayHas|refused)\(|deletions\.markSynced\(""")

    /** 结果驱动的任务转移（再评 S2）：这四个事件都是“网关（或连接）给了结论”才产生的，只许在 settle 的围栏里发。 */
    private val resultDrivenTransitions = Regex("""deliver\(key, (event|SyncEvent\.(STORED_CONFIRMED|VERSION_CONFLICT|KEY_MISMATCH|AUTH_REVOKED))\)""")

    @Test
    fun `引擎里每一处改缓存与目标的调用（换账本、记账、拒收）都在围栏里`() {
        val engine = code("sync/src/main/kotlin/app/auri/sync/engine/SyncEngine.kt")
        val (outsideFenced, fences) = withoutBlocks(engine, "fenced(gateway) {")
        val (outside, settles) = withoutBlocks(outsideFenced, "settle(gateway, key) {")

        assertTrue("围栏之外还有改账调用：${ledgerWrites.find(outside)?.value}", ledgerWrites.find(outside) == null)
        assertEquals("fenced 围栏块数量不对（attempt 里换账本 1 处、追加里 3 处、删除同步里 3 处）", 7, fences)
        assertEquals("settle 围栏块数量不对（入库、等待、排重试、永久拒绝、连接问题各 1 处）", 5, settles)
        assertTrue("围栏必须是配对存储的 whileCurrent，不能是先判断后动作的两步", "pairing.whileCurrent(gateway, change) != null" in engine)
        assertTrue("settle 的围栏也必须是配对存储的 whileCurrent", "pairing.whileCurrent(gateway, outcome) ?: stale(key)" in engine)
    }

    @Test
    fun `引擎里结果驱动的任务转移（已入库、被拒绝、连接问题）都在 settle 的围栏里，不是先判断后转移的两步`() {
        val engine = code("sync/src/main/kotlin/app/auri/sync/engine/SyncEngine.kt")
        val (outside, _) = withoutBlocks(engine, "settle(gateway, key) {")

        assertTrue("settle 之外还有结果驱动的转移：${resultDrivenTransitions.find(outside)?.value}", resultDrivenTransitions.find(outside) == null)
        assertTrue("stillPaired 之后再转移的两步写法不许回来", Regex("""if \(!stillPaired\(gateway\)\) return stale\(key\)""").find(outside) == null)
    }

    @Test
    fun `引擎里构造提交（冻结意图标签的写）在围栏里，旧配对的残余尝试不能在改配之后去冻结`() {
        val engine = code("sync/src/main/kotlin/app/auri/sync/engine/SyncEngine.kt")
        val (outside, fences) = withoutBlocks(engine, "pairing.whileCurrent(gateway) {")

        assertEquals("构造提交的围栏只有一处", 1, fences)
        assertTrue("围栏之外不许再有构造提交的调用", "source.submissionFor(" !in outside)
    }

    @Test
    fun `目的地服务里写缓存的两处都在配对存储的 whileCurrent 里，不是先判断后写的两步`() {
        val service = code("sync/src/main/kotlin/app/auri/sync/destinations/DestinationService.kt")

        assertTrue("管理操作的缓存写入在围栏里", "pairing.whileCurrent(session.gateway) { apply(session, result.value) }" in service)
        assertTrue("签发凭证后的缓存写入在围栏里", Regex("""pairing\.whileCurrent\(session\.gateway\) \{ dispatch\.applyDestinations\(""").containsMatchIn(service))
        // 这个文件里写缓存的地方一共四处：三处是交给 gatewayCall 的 apply 回调（由它的围栏执行），一处是签发凭证（上一条）
        val (outside, _) = withoutBlocks(service, "pairing.whileCurrent(session.gateway) {")
        assertEquals("围栏之外只许是交给 gatewayCall 的 apply 回调", 3, Regex("""dispatch\.applyDestinations\(""").findAll(outside).count())
        assertEquals(3, Regex("""apply = \{ [^}]*dispatch\.applyDestinations\(""").findAll(outside).count())
    }
}
