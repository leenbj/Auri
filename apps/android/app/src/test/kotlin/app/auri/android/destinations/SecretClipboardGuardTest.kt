@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.android.destinations

import app.auri.sync.SyncLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 22 评审 B5 / 复评 R6：复制出去的凭证不能无限期留在系统剪贴板里（任何拿到焦点的应用、输入法的剪贴板历史、跨设备同步都读得到它），
 * 但清理只能在**证明剪贴板里现在还是我们放的那一份**时做：Android 10 起不在前台的应用读到的剪贴板是“空”，而清却不受限——后台里清是盲的，
 * 盲清会把用户后来复制的别的东西（会议室门牌、验证码、另一份凭证）一并抹掉。所以：读得到并认得出是自己的才清（面板关闭 / 页面离开 / 应用即将失去焦点 /
 * 前台里满 60 秒 / 回到前台时发现到期）；读不到一律不动，凭证会留着直到被覆盖或系统自己清，这是平台限制，README 如实写明。
 * 时间是虚拟的、剪贴板是假的（按 Android 的规矩：不在前台读到的是“空”，写和清照常），所以每个先后顺序都可以逐一摆出来。
 */
class SecretClipboardGuardTest {
    private companion object {
        const val SECRET = "dc_live_not-a-real-credential-0123456789"
    }

    /** 假剪贴板：放进去会顶掉旧的；不在前台时 peek 读不到（Unreadable），写与清照常。 */
    private class FakeClipboard : ClipboardPort {
        var text: String? = null // 剪贴板里实际的文字：别的应用读得到它，测试要看的就是它被清掉没有
        private var at: Long? = null // 我们放的那一份记下的时刻；别的应用放的没有它
        var readable = true
        var clears = 0

        override fun put(secret: String, at: Long) {
            text = secret
            this.at = at
        }

        override fun peek(): ClipboardPeek = when {
            !readable || text == null -> ClipboardPeek.Unreadable
            else -> at?.let { ClipboardPeek.Ours(it) } ?: ClipboardPeek.Other
        }

        override fun clear() {
            text = null
            at = null
            clears++
        }

        fun otherAppCopies(value: String) {
            text = value
            at = null
        }
    }

    private val lines = mutableListOf<String>()

    private fun TestScope.guard(clipboard: ClipboardPort) =
        SecretClipboardGuard(clipboard, backgroundScope, { testScheduler.currentTime }, SyncLog { lines += it })

    private fun guarded(body: suspend TestScope.(FakeClipboard, SecretClipboardGuard) -> Unit) = runTest {
        val clipboard = FakeClipboard()
        body(clipboard, guard(clipboard))
    }

    private fun TestScope.passTime(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun `复制之后 60 秒，剪贴板里还是我们放的那一份就清空；没到点不动（用户还要带去粘贴）`() = guarded { clipboard, guard ->
        guard.copy(SECRET)
        assertEquals("复制成功", SECRET, clipboard.text)

        passTime(SecretClipboardGuard.TIMEOUT_MS - 1)
        assertEquals("还差 1 毫秒：不动", SECRET, clipboard.text)

        passTime(1)
        assertNull("到点：清空", clipboard.text)
        assertEquals(60_000L, SecretClipboardGuard.TIMEOUT_MS)
    }

    @Test
    fun `别的应用后来复制了别的东西（读得到）：到点不动它，更不能当成自己的清掉`() = guarded { clipboard, guard ->
        guard.copy(SECRET)
        passTime(30_000)
        clipboard.otherAppCopies("会议室门牌 4F-12")

        passTime(30_000)

        assertEquals("别人的东西原样还在", "会议室门牌 4F-12", clipboard.text)
        assertEquals("一次都没清过", 0, clipboard.clears)
    }

    @Test
    fun `复评 R6：应用不在前台（系统不让读剪贴板）时到点——认不出归属就绝不清，凭证留着也比误清强`() = guarded { clipboard, guard ->
        guard.copy(SECRET)
        clipboard.readable = false // 用户退出去粘贴了：Android 10 起后台应用读到的是空

        passTime(SecretClipboardGuard.TIMEOUT_MS)

        assertEquals("没有归属证明：一次都不清", 0, clipboard.clears)
        assertEquals("凭证原样留着（平台限制，README 如实写明）", SECRET, clipboard.text)
        assertTrue("记一条“确认不了”，没有凭证：$lines", lines.contains("clipboard.unverifiable") && lines.none { it.contains(SECRET) })
    }

    @Test
    fun `复评 R6：读不了的时候剪贴板其实已被别的应用换成了别的东西——到点绝不能把它清掉（盲清的真实后果）`() = guarded { clipboard, guard ->
        guard.copy(SECRET)
        clipboard.readable = false
        passTime(30_000)
        clipboard.otherAppCopies("会议室门牌 4F-12") // 后台读不了，所以守卫并不知道

        passTime(30_000)

        assertEquals("别人后来复制的东西原样还在", "会议室门牌 4F-12", clipboard.text)
        assertEquals(0, clipboard.clears)
    }

    @Test
    fun `复评 R6：到点时读不了、之后回到前台读得到了——确认还是我们放的、且已到期，才清`() = guarded { clipboard, guard ->
        guard.copy(SECRET)
        clipboard.readable = false
        passTime(SecretClipboardGuard.TIMEOUT_MS) // 到点时在后台：没清
        clipboard.readable = true // 用户回来了，窗口拿到了焦点

        guard.onForeground()

        assertNull("此刻读得到、认得出是自己的、已到期：清", clipboard.text)
        assertEquals(1, clipboard.clears)
    }

    @Test
    fun `复评 R6：到点时读不了、回到前台时那里已是别人的东西——不动`() = guarded { clipboard, guard ->
        guard.copy(SECRET)
        clipboard.readable = false
        passTime(SecretClipboardGuard.TIMEOUT_MS)
        clipboard.otherAppCopies("别人复制的")
        clipboard.readable = true

        guard.onForeground()

        assertEquals("别人复制的", clipboard.text)
        assertEquals(0, clipboard.clears)
    }

    @Test
    fun `复评 R6：面板关闭、页面离开或应用即将失去焦点时（读得到、认得出是自己的）当场清，并作废定时器`() = guarded { clipboard, guard ->
        guard.copy(SECRET)
        passTime(5_000)

        guard.onLeaving()

        assertNull("当场清，不等 60 秒", clipboard.text)
        assertEquals(1, clipboard.clears)
        clipboard.otherAppCopies("之后别人复制的")
        passTime(SecretClipboardGuard.TIMEOUT_MS)
        assertEquals("定时器已作废：到点不会再去动别人的东西", "之后别人复制的", clipboard.text)
        assertEquals("总共只清了一次", 1, clipboard.clears)
    }

    @Test
    fun `复评 R6：离开时读不了——没有归属证明，不清；之后在前台到点还是会清`() = guarded { clipboard, guard ->
        guard.copy(SECRET)
        clipboard.readable = false

        guard.onLeaving()

        assertEquals("读不了就不动", SECRET, clipboard.text)
        assertEquals(0, clipboard.clears)
        clipboard.readable = true // 用户在前台里又回来了（比如只是下拉了通知栏）
        passTime(SecretClipboardGuard.TIMEOUT_MS)
        assertNull("定时器还在：前台里到点照清", clipboard.text)
    }

    @Test
    fun `复评 R6：离开时剪贴板里是别的应用放的东西——不动，也不记成清过`() = guarded { clipboard, guard ->
        guard.copy(SECRET)
        clipboard.otherAppCopies("别人复制的")

        guard.onLeaving()

        assertEquals("别人复制的", clipboard.text)
        assertEquals(0, clipboard.clears)
    }

    @Test
    fun `复评 R6：这个进程没有复制过也离开了，但剪贴板里有上一个进程放的、带我们标记的凭证——那也是我们的，清`() = guarded { clipboard, guard ->
        clipboard.put(SECRET, at = 0)

        guard.onLeaving()

        assertNull(clipboard.text)
    }

    @Test
    fun `再复制一次就重新计时：第一次的定时器不能提前清掉第二次`() = guarded { clipboard, guard ->
        guard.copy("第一个凭证")
        passTime(50_000)
        guard.copy("第二个凭证") // 重新轮换了一次

        passTime(10_000) // 第一次的 60 秒到了
        assertEquals("第二次才复制了 10 秒", "第二个凭证", clipboard.text)

        passTime(49_999)
        assertEquals("第二次的 60 秒还差 1 毫秒", "第二个凭证", clipboard.text)
        passTime(1)
        assertNull(clipboard.text)
        assertEquals("只清了一次", 1, clipboard.clears)
    }

    @Test
    fun `回到前台时发现已经到期（定时器随被杀的进程丢了）：认出剪贴板里自己的标记，到期就清，没到期不动`() = guarded { clipboard, guard ->
        clipboard.put(SECRET, at = 0) // 上一个进程放的；它的定时器随进程一起没了，这个守卫对它一无所知
        passTime(SecretClipboardGuard.TIMEOUT_MS - 1)

        guard.onForeground()
        assertEquals("没到期：用户可能刚回来看一眼，还要去粘贴", SECRET, clipboard.text)

        passTime(1)
        guard.onForeground()
        assertNull("到期了就清", clipboard.text)
    }

    @Test
    fun `设备重启过（标记里的时刻比现在的开机计时还大）：算到期`() = guarded { clipboard, guard ->
        clipboard.put(SECRET, at = 10_000_000)

        guard.onForeground()

        assertNull(clipboard.text)
    }

    @Test
    fun `回到前台时剪贴板里是别人的东西：不动，哪怕过了很久`() = guarded { clipboard, guard ->
        clipboard.otherAppCopies("别人的")
        passTime(10 * SecretClipboardGuard.TIMEOUT_MS)

        guard.onForeground()

        assertEquals("别人的", clipboard.text)
        assertEquals(0, clipboard.clears)
    }

    @Test
    fun `日志只有事件名，没有凭证`() = guarded { clipboard, guard ->
        guard.copy(SECRET)
        passTime(SecretClipboardGuard.TIMEOUT_MS)

        assertTrue("记了复制与清空：$lines", lines.containsAll(listOf("clipboard.copied", "clipboard.cleared")))
        assertFalse(lines.any { it.contains(SECRET) })
        assertNull(clipboard.text)
    }
}
