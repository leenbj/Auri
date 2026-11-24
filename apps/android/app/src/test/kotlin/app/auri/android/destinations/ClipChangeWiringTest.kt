package app.auri.android.destinations

import app.auri.android.netguard.SourceText
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第四轮 F3/F10 的接线守卫：真机端口要 Android 的 ClipboardManager，JVM 跑不了；它的协议（放：记摘要 → 放 → 登记监听；读：整份剪贴板的形状；清：清了就忘并摘监听）
 * 在 ClipChangeProofTest / ClipShapeTest 里用**同形**的替身摆过每种情形。这里钉住生产源码确实是那个形状：
 * - 先登记、后放（或旧监听没摘）：系统在放入那次调用里把变化排给当时已登记的监听者，自己放的那一下回调回来，被当成“别人动过”，失焦时就永远认不出自己的；
 * - 读剪贴板只看第 0 项、不看类型与 HTML/URI/Intent：别的应用复制的多项内容会被当成自己的清掉；
 * - 监听句柄不摘：每复制一次多漏一个监听。
 * 做法同 ClipboardWiringTest：读生产源码（去掉注释与字符串字面量）。设备上的效果 `NOT_RUN`（验收 README）。
 */
class ClipChangeWiringTest {
    private val raw = File("../app/src/main/kotlin/app/auri/android/ui/SecretWindow.kt").readText()
    private val source = SourceText.withoutCommentsAndStrings(raw).replace(Regex("""\s+"""), " ")

    private val put = source.substringAfter("override fun put(").substringBefore("override fun peek")
    private val peek = source.substringAfter("override fun peek").substringBefore("override fun clear")
    private val clear = source.substringAfter("override fun clear").substringBefore("private class AndroidClipChanges")

    @Test
    fun `放进去：先记摘要并摘旧监听，再放，放进去之后才登记新监听——自己放的那一下回调不回来`() {
        val remember = put.indexOf("ownership.remember(secret, at)")
        val set = put.indexOf("clipboard?.setPrimaryClip(clip)")
        val watch = put.indexOf("ownership.startWatching()")

        assertTrue("put 里要有这三步：$put", remember >= 0 && set >= 0 && watch >= 0)
        assertTrue("顺序必须是 记摘要 < 放 < 登记监听：$put", remember < set && set < watch)
    }

    @Test
    fun `端口的归属证明带着变化监听：ClipOwnership 拿到的是 ClipboardManager 的监听适配器`() {
        assertTrue(
            "ClipOwnership 要接上监听适配器：$source",
            "private val ownership = ClipOwnership(clipboard?.let(::AndroidClipChanges))" in source,
        )
    }

    @Test
    fun `监听适配器：向 ClipboardManager 登记一个监听，句柄关掉就摘同一个监听`() {
        val adapter = source.substringAfter("private class AndroidClipChanges").substringBefore("private fun ClipData.snapshot")

        assertTrue("登记：$adapter", "clipboard.addPrimaryClipChangedListener(listener)" in adapter)
        assertTrue("句柄关掉就摘同一个：$adapter", "AutoCloseable { clipboard.removePrimaryClipChangedListener(listener) }" in adapter)
        assertTrue("回调接到 onChanged：$adapter", "OnPrimaryClipChangedListener { onChanged() }" in adapter)
    }

    @Test
    fun `读：整份剪贴板交给 classifyClip 核对形状——类型、每一项、HTML、URI、Intent，而不是只看第 0 项的文字`() {
        val snapshot = source.substringAfter("private fun ClipData.snapshot()")

        assertTrue("peek 走整份核对：$peek", "ownership.classifyClip { clipboard?.primaryClip?.snapshot() }" in peek)
        assertTrue("读类型：$snapshot", "description.mimeTypeCount" in snapshot && "description.getMimeType(it)" in snapshot)
        assertTrue("每一项都读：$snapshot", "List(itemCount) { index -> val item = getItemAt(index)" in snapshot)
        assertTrue("记下每一项有没有 HTML、URI、Intent：$snapshot", listOf("item.htmlText != null", "item.uri != null", "item.intent != null").all { it in snapshot })
        assertTrue("不再用只看第 0 项的写法：$peek", "getItemAt(0)" !in peek)
    }

    @Test
    fun `清：先清系统剪贴板，再忘掉（摘要与监听一起）`() {
        val cleared = clear.indexOf("clipboard?.clearPrimaryClip()")
        val forgotten = clear.indexOf("ownership.forget()")

        assertTrue("clear 里要有这两步：$clear", cleared >= 0 && forgotten >= 0)
        assertTrue("先清、后忘：$clear", cleared < forgotten)
    }
}
