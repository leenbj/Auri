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
 * Ticket 22 评审 B5：复制出去的凭证不能无限期留在系统剪贴板里（任何拿到焦点的应用、输入法的剪贴板历史、跨设备同步都读得到它）。
 * 凭证复制出去之后唯一的用处是粘贴给别处——通常要退出本应用——所以“清”不能挑退后台的那一刻（那会把刚复制的东西当场删掉），
 * 而是到点（60 秒）清，并且只清自己放的那一份：别的应用后来复制的东西不动。
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
    fun `应用不在前台（系统不让读剪贴板）：认不出归属也到点清，宁可多清一次，不让凭证留着`() = guarded { clipboard, guard ->
        guard.copy(SECRET)
        clipboard.readable = false // 用户退出去粘贴了：Android 10 起后台应用读到的是空

        passTime(SecretClipboardGuard.TIMEOUT_MS)

        assertNull("读不了也清", clipboard.text)
        assertEquals(1, clipboard.clears)
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
