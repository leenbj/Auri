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
    fun `复评 R6：应用即将失去焦点（onPause，那一刻还读得到剪贴板）与离开详情页时都叫守卫清；复制之后提示一句话；放进去时标成敏感内容`() {
        val activity = SourceText.withoutCommentsAndStrings(main("MainActivity.kt"))
        val onPause = activity.substringAfter("override fun onPause()", "").substringBefore("override fun onWindowFocusChanged")
        assertTrue("MainActivity 要在 onPause 里叫守卫：$onPause", onPause.contains("runtime.secretClipboard.onLeaving()"))
        assertTrue("要在 super.onPause() 之前叫：越早越可能还读得到", onPause.indexOf("onLeaving()") < onPause.indexOf("super.onPause()"))

        val page = SourceText.withoutCommentsAndStrings(main("ui/DestinationsPage.kt"))
        val dispose = page.substringAfter("onDispose {").substringBefore("LaunchedEffect(viewModel)")
        assertTrue("离开详情页（返回、切走、被销毁）要叫守卫：$dispose", dispose.contains("clipboard.onLeaving()"))
        val copy = page.substringAfter("onCopyCredential = {").substringBefore("onDismissCredential")
        assertTrue("复制：先交给守卫放进去，再抹面板：$copy", copy.indexOf("clipboard.copy(credential.reveal())") in 0 until copy.indexOf("viewModel.credentialCopied()"))
        assertTrue("复制那一下本身不许清（清会让刚复制的凭证当场没了）：$copy", !copy.contains("onLeaving"))
        assertTrue("复制之后提示一句话：$copy", copy.contains("Toast.makeText(context, describeClipboardNotice(), Toast.LENGTH_LONG).show()"))

        val portSource = main("ui/SecretWindow.kt")
        val port = SourceText.withoutCommentsAndStrings(portSource)
        assertTrue("放进剪贴板时标成敏感内容：${port.substringAfter("override fun put(")}", port.substringAfter("override fun put(").substringBefore("override fun peek").contains("putBoolean(EXTRA_IS_SENSITIVE, true)"))
        assertTrue("敏感标记的键就是系统的那个：旧系统忽略它、新系统据它不展示内容", portSource.contains("\"android.content.extra.IS_SENSITIVE\""))
    }

    @Test
    fun `clearPrimaryClip 是 API 28 才有的：应用的 minSdk 不低于它，所以不需要降级写法；谁把 minSdk 调低，这里先红`() {
        val gradle = File(androidDir, "app/build.gradle.kts").readText()
        val minSdk = Regex("""minSdk\s*=\s*(\d+)""").find(gradle)?.groupValues?.get(1)?.toInt() ?: error("没找到 minSdk")

        assertTrue("minSdk=$minSdk 低于 28：AndroidClipboardPort.clear 要先补上没有 clearPrimaryClip 时的做法（放一段空文字顶掉）", minSdk >= 28)
    }
}
