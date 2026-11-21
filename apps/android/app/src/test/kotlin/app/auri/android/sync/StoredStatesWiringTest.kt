package app.auri.android.sync

import app.auri.android.netguard.SourceText
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复评 R9 的接线：“读失败先 Closed、之后退避重试、读到了发出真实值”的行为在 :sync 的 StoredStatesRecoveryTest 里用脚本化的读摆过；
 * SQLite 的那一份（要 Android 的 SQLiteDatabase）在 JVM 里跑不了，这里钉住它真的用了这个算子，并且读失败对算子是看得见的（null），
 * 不是在读里就被吞成 Closed——否则流又会在一次暂时的故障之后永远停在“不许发”。做法同 PairingFenceWiringTest：读生产源码（去掉注释与字符串字面量）。
 */
class StoredStatesWiringTest {
    private val androidDir = File("..")

    private fun code(path: String) = SourceText.withoutCommentsAndStrings(File(androidDir, path).readText()).replace(Regex("""\s+"""), " ")

    @Test
    fun `SqliteDataFlowPolicy 的存档变化流走恢复算子，读失败对它是看得见的 null，取消不被当成读失败`() {
        val policy = code("syncplatform/src/main/kotlin/app/auri/syncplatform/policy/SqliteDataFlowPolicy.kt")

        assertTrue("storedStates 必须经过 recoveringStoredStates：$policy", "override fun storedStates(): Flow<StoredDataFlow> = changes.recoveringStoredStates(::readOrNull)" in policy)
        assertFalse("老写法：每次变动读一次、失败就停在 Closed", "changes.map { stored() }" in policy)
        val reader = policy.substringAfter("private suspend fun readOrNull()").substringBefore("override fun storedStates")
        assertTrue("读失败要让算子知道（null）：$reader", "} catch (_: Exception) { null }" in reader)
        assertFalse("读里不能先把失败吞成 Closed：$reader", "readOrClosed" in reader || "StoredDataFlow.Closed" in reader)
        assertTrue("取消照常往外传：$reader", "catch (cancelled: CancellationException) { throw cancelled }" in reader)
    }
}
