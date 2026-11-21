@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.android.destinations

import java.util.IdentityHashMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ticket 22 再评 S11：“剪贴板里现在是不是我们放的那一份”必须**按内容证明**。
 * 旧做法是在剪贴板描述里放一个额外字段当标记——描述连同内容一起，任何应用都读得到、抄得走、伪造得出：别的应用复制了会议室门牌，
 * 再把我们的标记整份抄上去，我们就会把它当成自己的清掉。现在放进去时只在内存里记下这段文字的 SHA-256（不记文字本身，更不落盘），
 * 之后只有当前剪贴板里的文字摘要相同才算我们的；描述里的任何字段都不看。
 * 这里的假剪贴板把描述里的额外字段也摆了出来（旧标记、敏感标记、任意伪造的），并用与真机端口同一套归属判断；
 * 真机端口只是把 ClipboardManager 接到这三个调用上（接线由 ClipboardWiringTest 读源码钉住），设备上的效果 `NOT_RUN`。
 */
class ClipOwnershipTest {
    private companion object {
        const val SECRET = "dc_live_not-a-real-credential-0123456789"
        const val FOREIGN = "会议室门牌 4F-12"
        const val OLD_MARKER = "app.auri.android.extra.COPIED_AT" // 旧做法的标记：现在没有任何地方看它
        const val IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }

    private class SystemClipboard : ClipboardPort {
        var text: String? = null
        var extras: Map<String, Any> = emptyMap() // 描述里的额外字段：任何应用都读得到、抄得走、伪造得出
        var readable = true
        var clears = 0
        var reads = 0 // 内容被读了几次（读别人复制的东西本身就该能少则少）
        private var ownership = ClipOwnership()

        override fun put(secret: String, at: Long) {
            ownership.remember(secret, at)
            text = secret
            extras = mapOf(IS_SENSITIVE to true, OLD_MARKER to at)
        }

        override fun peek(): ClipboardPeek = ownership.classify {
            if (!readable || text == null) null else text.also { reads++ }
        }

        override fun clear() {
            text = null
            extras = emptyMap()
            ownership.forget()
            clears++
        }

        fun otherAppCopies(value: String, forging: Map<String, Any> = emptyMap()) {
            text = value
            extras = forging
        }

        /** 进程被杀后重新起来：内存里的东西都没了，系统剪贴板还在。 */
        fun processRestarts() {
            ownership = ClipOwnership()
        }
    }

    private fun TestScope.passTime(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    /** 我们复制了凭证；[reach] 里别的应用换成了 [FOREIGN]（连我们的额外字段一并抄了过去）；结束时它必须原样还在、一次都没被清。 */
    private fun forged(reach: suspend TestScope.(SystemClipboard, SecretClipboardGuard) -> Unit) = runTest {
        val clipboard = SystemClipboard()
        val guard = SecretClipboardGuard(clipboard, backgroundScope, { testScheduler.currentTime })
        guard.copy(SECRET)

        reach(clipboard, guard)

        assertEquals("别人的东西原样还在", FOREIGN, clipboard.text)
        assertEquals("一次都没清过", 0, clipboard.clears)
    }

    @Test
    fun `按内容认归属：读回来逐字相同的就是我们放的，带着放入时刻`() {
        val clipboard = SystemClipboard()

        clipboard.put(SECRET, at = 42)

        assertEquals(ClipboardPeek.Ours(42), clipboard.peek())
    }

    @Test
    fun `再评 S11：别的应用复制了别的东西，还把我们描述里的额外字段整份抄了过去（伪造）——离开时不清`() = forged { clipboard, guard ->
        clipboard.otherAppCopies(FOREIGN, forging = clipboard.extras)

        guard.onLeaving()
    }

    @Test
    fun `再评 S11：同样的伪造——在前台里到点，不清`() = forged { clipboard, _ ->
        passTime(30_000)
        clipboard.otherAppCopies(FOREIGN, forging = clipboard.extras)

        passTime(30_000)
    }

    @Test
    fun `再评 S11：同样的伪造——到点时人在后台没法确认，回到前台时认归属，也不清`() = forged { clipboard, guard ->
        clipboard.readable = false
        passTime(SecretClipboardGuard.TIMEOUT_MS)
        clipboard.otherAppCopies(FOREIGN, forging = clipboard.extras)
        clipboard.readable = true

        guard.onForeground()
    }

    @Test
    fun `内容就是证明：别的应用把同一段凭证文字原样复制了一遍，那还是凭证本身，照样认`() {
        val clipboard = SystemClipboard()
        clipboard.put(SECRET, at = 5)

        clipboard.otherAppCopies(SECRET)

        assertEquals(ClipboardPeek.Ours(5), clipboard.peek())
    }

    @Test
    fun `逐字比：多一个换行、大小写不同、少一个字符、前面多一个空格，都不是`() {
        for (variant in listOf("$SECRET\n", SECRET.uppercase(), SECRET.dropLast(1), " $SECRET", "")) {
            val clipboard = SystemClipboard()
            clipboard.put(SECRET, at = 5)
            clipboard.otherAppCopies(variant)

            assertEquals("“$variant”不是我们放的那一份", ClipboardPeek.Other, clipboard.peek())
        }
    }

    @Test
    fun `进程重启之后摘要没了：剪贴板里即使躺着带着旧标记的凭证也认不出，不清、也不去读它——认不出就不动，宁可多留不误清`() = runTest {
        val clipboard = SystemClipboard()
        val guard = SecretClipboardGuard(clipboard, backgroundScope, { testScheduler.currentTime })
        clipboard.put(SECRET, at = 0)
        clipboard.processRestarts()

        guard.onLeaving()
        passTime(10 * SecretClipboardGuard.TIMEOUT_MS)
        guard.onForeground()

        assertEquals("没有归属证明：原样留着（残余风险，README 写明）", SECRET, clipboard.text)
        assertEquals(0, clipboard.clears)
        assertEquals("手里没有东西要核实，就一次也不读剪贴板", 0, clipboard.reads)
    }

    @Test
    fun `读别人复制的东西能少读就少读：核实时发现已是别人的之后不再读，手里没有东西要核实时更不读`() {
        val nothingHeld = SystemClipboard()
        nothingHeld.otherAppCopies(FOREIGN)
        assertEquals(ClipboardPeek.Other, nothingHeld.peek())
        assertEquals("从来没放过：不读", 0, nothingHeld.reads)

        val overwritten = SystemClipboard()
        overwritten.put(SECRET, at = 1)
        overwritten.otherAppCopies(FOREIGN)
        assertEquals(ClipboardPeek.Other, overwritten.peek())
        assertEquals("核实一次，发现已被顶掉", 1, overwritten.reads)
        assertEquals(ClipboardPeek.Other, overwritten.peek())
        assertEquals("我们放的那份已经不在了：之后不再读别人的东西", 1, overwritten.reads)
    }

    @Test
    fun `读不了的时候摘要保留：之后读得到、还是同一份，就是我们的`() {
        val clipboard = SystemClipboard()
        clipboard.put(SECRET, at = 9)

        clipboard.readable = false
        assertEquals(ClipboardPeek.Unreadable, clipboard.peek())
        clipboard.readable = true

        assertEquals(ClipboardPeek.Ours(9), clipboard.peek())
    }

    @Test
    fun `自己清掉之后就忘了：之后剪贴板里即使又出现一模一样的文字，也不再认`() {
        val clipboard = SystemClipboard()
        clipboard.put(SECRET, at = 3)
        clipboard.clear()

        clipboard.otherAppCopies(SECRET)

        assertEquals(ClipboardPeek.Other, clipboard.peek())
    }

    @Test
    fun `摘要之外不留任何东西：对象里没有凭证文字（字符串、字符数组、字节数组都没有）`() {
        val ownership = ClipOwnership()
        ownership.remember(SECRET, at = 7)

        val leaked = mutableListOf<String>()
        val seen = IdentityHashMap<Any, Boolean>()
        fun visit(value: Any?, path: String) {
            if (value == null || seen.put(value, true) != null) return
            when (value) {
                is CharSequence -> if (value.contains(SECRET)) leaked += path
                is CharArray -> if (String(value).contains(SECRET)) leaked += path
                is ByteArray -> if (String(value, Charsets.UTF_8).contains(SECRET)) leaked += path
                is Array<*> -> value.forEachIndexed { index, item -> visit(item, "$path[$index]") }
                is Iterable<*> -> value.forEachIndexed { index, item -> visit(item, "$path[$index]") }
                else -> if (value.javaClass.name.startsWith("app.auri.")) {
                    value.javaClass.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.forEach { field ->
                        field.isAccessible = true
                        visit(field.get(value), "$path.${field.name}")
                    }
                }
            }
        }
        visit(ownership, "ownership")

        assertEquals("凭证文字不许出现在对象里：$leaked", emptyList<String>(), leaked)
    }
}
