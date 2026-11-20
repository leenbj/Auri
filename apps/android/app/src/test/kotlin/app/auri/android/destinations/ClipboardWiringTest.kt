package app.auri.android.destinations

import app.auri.android.netguard.SourceText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 22 评审 B5 的接线：守卫本身在 SecretClipboardGuardTest 里用假剪贴板逐个摆过；这里钉住那几处只能在设备上跑、JVM 里碰不到的连接，
 * 免得有人绕开守卫直接写剪贴板（凭证就又没人清了），或把守卫的“回到前台补查”接丢（做法同 CredentialSurfaceGuardTest：读生产源码）。
 */
class ClipboardWiringTest {
    private val androidDir = File("..")

    private fun main(path: String) = File(androidDir, "app/src/main/kotlin/app/auri/android/$path").readText()

    @Test
    fun `整个应用里只有一处把东西写进系统剪贴板：真机适配器；凭证复制只经过守卫`() {
        val writers = File(androidDir, "app/src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""\bsetPrimaryClip\(""").containsMatchIn(SourceText.withoutCommentsAndStrings(it.readText())) }
            .map { it.name }.toList()
        assertEquals("setPrimaryClip 只许出现在适配器里", listOf("SecretWindow.kt"), writers)

        val page = main("ui/DestinationsPage.kt")
        assertTrue("复制凭证走守卫", page.contains("clipboard.copy(credential.reveal())"))
        assertTrue("没有绕开守卫的复制函数", !page.contains("copySecretToClipboard"))
    }

    @Test
    fun `回到前台补查到期：窗口拿到焦点时调守卫（此前读不了剪贴板），守卫由运行时持有，定时器挂在主线程上`() {
        val activity = SourceText.withoutCommentsAndStrings(main("MainActivity.kt"))
        val onFocus = activity.substringAfter("override fun onWindowFocusChanged(hasFocus: Boolean)").substringBefore("private fun onStartPressed")
        assertTrue("拿到焦点时要调 onForeground：$onFocus", onFocus.contains("if (hasFocus) runtime.secretClipboard.onForeground()"))
        assertTrue("onStart 里不调：那时窗口没有焦点，读到的是空，补查等于没查", !activity.substringAfter("override fun onStart()").substringBefore("override fun onResume()").contains("secretClipboard"))

        val runtime = SourceText.withoutCommentsAndStrings(main("AuriRuntime.kt"))
        val guard = runtime.substringAfter("val secretClipboard = SecretClipboardGuard(").substringBefore("val destinationsViewModelFactory")
        assertTrue("守卫用真机适配器：$guard", guard.contains("AndroidClipboardPort(application)"))
        assertTrue("定时器挂在主线程（守卫的状态只在一个线程上用）：$guard", guard.contains("Dispatchers.Main.immediate"))
        assertTrue("时刻用开机后的单调时钟（标记要跨进程、跨睡眠比较）：$guard", guard.contains("SystemClock.elapsedRealtime()"))
    }

    @Test
    fun `clearPrimaryClip 是 API 28 才有的：应用的 minSdk 不低于它，所以不需要降级写法；谁把 minSdk 调低，这里先红`() {
        val gradle = File(androidDir, "app/build.gradle.kts").readText()
        val minSdk = Regex("""minSdk\s*=\s*(\d+)""").find(gradle)?.groupValues?.get(1)?.toInt() ?: error("没找到 minSdk")

        assertTrue("minSdk=$minSdk 低于 28：AndroidClipboardPort.clear 要先补上没有 clearPrimaryClip 时的做法（放一段空文字顶掉）", minSdk >= 28)
    }
}
