package app.auri.android.sync

import app.auri.android.netguard.SourceText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 再评 S3 的接线守卫：行为（问放行与请求交出去之间授权被收回、请求不发）在 :sync 的 PolicyGateRaceTest 里用确定的交错摆过；
 * 世代的性质在 PolicyGenerationTest 里钉过。这里钉住两件 JVM 里看不到、又一旦忘了就没有任何测试会红的接线：
 * 1. 引擎里**每一个会发往网关的调用**（定位、合同版本探测、状态查询、PUT、追加）都包在 `permit.gated { … }` 里——新增一条请求路径忘了套，这里红；
 * 2. SQLite 的授权设置（要 Android 的 SQLiteDatabase，JVM 跑不了）每次写都经 `write`：开始前 changing()、写完在数据库线程上的 finally 里 changed()。
 * 做法同 PairingFenceWiringTest：读生产源码（去掉注释与字符串字面量）。
 */
class PolicyGateWiringTest {
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

    private val outboundCalls = Regex("""client\.(putRevision|recordStatus|addDeliveries)\(|location\.get\(|contract\.check\(""")

    @Test
    fun `引擎里每一个会发往网关的调用都包在 permit gated 里：交出请求的前一刻比过世代`() {
        val engine = code("sync/src/main/kotlin/app/auri/sync/engine/SyncEngine.kt")
        val (outside, gated) = withoutBlocks(engine, "permit.gated {")

        assertTrue("gated 之外还有直接发往网关的调用：${outboundCalls.find(outside)?.value}", outboundCalls.find(outside) == null)
        // attempt：定位、合同版本、状态查询、PUT 各 1；healContract：定位、合同版本；probe：定位 1；addDeliveries：定位、合同版本、追加各 1；
        // revalidate（请求被当成“不合规”拒绝后重新问版本，再评 S6）：合同版本 1——attempt 与 addDeliveries 共用
        assertEquals("gated 块的数量不对（新增请求路径要套 gated，并更新这里的数）", 11, gated)
        assertTrue("Permit 的比对必须是世代", "policy.generation == generation" in engine)
        assertTrue("世代必须在问放行之前记下：先记再问，问的过程中有人改了才看得出", "val generation = policy.generation return if (mayTransmit(gateway)) Permit(generation) else null" in engine)
    }

    @Test
    fun `SqliteDataFlowPolicy 每次写授权设置都经 write：开始前加一，写完在数据库线程上的 finally 里再加一，世代读出来是同一个计数`() {
        val policy = code("syncplatform/src/main/kotlin/app/auri/syncplatform/policy/SqliteDataFlowPolicy.kt")

        assertTrue("世代必须取自 PolicyGeneration：$policy", "override val generation: Long get() = generations.value" in policy)
        val write = policy.substringAfter("private suspend fun write(").substringBefore("changes.value += 1")
        assertTrue(
            "write 必须先 changing()，再在 withDatabase 里 try 写、finally changed()（落定要在数据库线程上、紧接着写）：$write",
            "generations.changing() library.withDatabase { db -> try { DataFlowPolicySql(AndroidSqlDb(db)).change() } finally { generations.changed() } }" in write,
        )
        // 四种改动入口都只走 write，没有绕开它直接写库的
        val mutators = listOf("grantTo", "revokeGrant", "forgetGrantExcept", "setStrictLocal")
        mutators.forEach { name -> assertTrue("$name 必须经 write：$policy", Regex("""override suspend fun $name\([^)]*\) = write \{""").containsMatchIn(policy)) }
        val outsideWrite = policy.replace(write, "")
        assertTrue("write 之外不许再直接调 DataFlowPolicySql 的写：$outsideWrite", Regex("""\)\.(grantTo|revokeGrant|forgetGrantExcept|setStrictLocal)\(""").find(outsideWrite) == null)
    }
}
