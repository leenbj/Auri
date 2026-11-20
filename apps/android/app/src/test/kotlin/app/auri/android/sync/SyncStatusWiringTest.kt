package app.auri.android.sync

import app.auri.android.netguard.SourceText
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评审 M2 的接线：行为在 :sync 的 SyncStatusAwaitingTest（状态流数出等用户确认的条数）与 SyncScreenTextTest（那句话）里摆过；
 * 这里钉住只能在设备上跑、JVM 里碰不到的两处——装配 SyncRuntime 的状态流是不是真的经 SyncStatus.watch、并把发送账交给它，
 * 同步页是不是真的把那句话摆出来。少了任何一处，页面都会退回“只剩一句已同步”，而上面两个测试照样全绿。做法同 RuntimeScopeGuardTest：读生产源码。
 */
class SyncStatusWiringTest {
    private val androidDir = File("..")

    private fun code(path: String) = SourceText.withoutCommentsAndStrings(File(androidDir, path).readText()).replace(Regex("""\s+"""), " ")

    @Test
    fun `同步状态流经 SyncStatus 的 watch、并把发送账交给它：自己拼 combine 就会漏掉等用户确认的数`() {
        val runtime = code("syncplatform/src/main/kotlin/app/auri/syncplatform/SyncRuntime.kt")

        assertTrue(runtime, Regex("""override val status: Flow<SyncStatus> = SyncStatus\.watch\(\s*pairing\.states,\s*jobs\.observe\(\),\s*policy\.states\(\),\s*dispatch\s*\)""").containsMatchIn(runtime))
        assertFalse("不许绕过它自己汇总", "SyncStatus.of(" in runtime)
    }

    @Test
    fun `同步页把等用户确认的那句话摆出来`() {
        val screen = code("app/src/main/kotlin/app/auri/android/ui/SyncScreen.kt")

        assertTrue(Regex("""describeAwaitingYou\(status\.awaitingYou\)""").containsMatchIn(screen))
    }
}
