@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.android.destinations

import app.auri.sync.SyncLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 22 第四轮 F3（再评 S10 的缺口）：窗口一失去焦点，Android 就不让应用读剪贴板（读到的是“空”），所以“失焦时当场清”这一招
 * 在它本来要管的场景里（分屏里点了别的窗口、系统对话框、下拉通知栏）读不到、认不出归属、什么也不清——凭证就留在剪贴板里。
 * 产品决定：没有证明是自己的就绝不清；证明不一定要靠读：复制时登记 OnPrimaryClipChangedListener，
 * 从我们复制到现在没有收到过任何“剪贴板变了”，说明那里还是我们放的那一份（我们拿着焦点的时候，别人动它我们一定收得到）。
 *
 * 这里的假系统剪贴板照 Android 10+ 的几条规矩摆（只摆与归属证明有关的）：
 * 1. 只有拿着焦点的应用读得到剪贴板，也只有它收得到变化回调（AOSP ClipboardService：回调只发给“允许读”的监听者）——**失焦之后的沉默什么也证明不了**，
 *    所以事件证明只在失焦的那一刻（[SecretClipboardGuard.onLeaving]）用，到点的定时器在后台时不用；
 * 2. 写与清不受焦点限制；
 * 3. 回调是异步的：放进剪贴板那一次调用里，系统给“当时已登记的监听者”排一条回调，主线程稍后才处理（[AndroidLikeClipboard.drain]），
 *    处理时叫的是**那时**登记着的监听者——所以先登记、后放，或者放之前旧的监听还没摘，自己放的那一下都会回调回来，被当成“别人动过”。
 * 真机端口只是把 ClipboardManager 接到这几个调用上（接线由 ClipChangeWiringTest 读源码钉住），设备上的效果 `NOT_RUN`（验收 README）。
 */
class ClipChangeProofTest {
    private companion object {
        const val SECRET = "dc_live_not-a-real-credential-0123456789"
        const val FOREIGN = "会议室门牌 4F-12"
    }

    private fun text(value: String) = ClipSnapshot(listOf("text/plain"), listOf(ClipItemSnapshot(value)))

    private class AndroidLikeClipboard {
        var focused = true
        var clip: ClipSnapshot? = null
        var clears = 0
        private val listeners = mutableListOf<() -> Unit>()
        private var pending = 0
        var peakListeners = 0
            private set

        val activeListeners get() = listeners.size

        val events = ClipChangeEvents { onChanged ->
            listeners += onChanged
            peakListeners = maxOf(peakListeners, listeners.size)
            AutoCloseable { listeners.remove(onChanged) }
        }

        fun read(): ClipSnapshot? = if (focused) clip else null

        fun set(snapshot: ClipSnapshot) {
            clip = snapshot
            announce()
        }

        fun clear() {
            clip = null
            clears++
            announce()
        }

        /** 别的应用复制（或默认输入法）：系统照常通知；拿着焦点的我们才收得到。 */
        fun otherAppCopies(value: String) = set(ClipSnapshot(listOf("text/plain"), listOf(ClipItemSnapshot(value))))

        /** 系统在这一次调用里把变化发给“当时已登记的”监听者（没有监听者就没有这条回调）；焦点不在我们这里就不发。 */
        private fun announce() {
            if (focused && listeners.isNotEmpty()) pending++
        }

        /** 主线程把排着的回调处理掉：叫的是此刻登记着的监听者。 */
        fun drain() {
            while (pending > 0) {
                pending--
                listeners.toList().forEach { it() }
            }
        }
    }

    /** 与 AndroidClipboardPort 同一套调用（放：记摘要 → 放 → 登记监听；读：按形状与内容认；清：清了就忘并摘掉监听）。 */
    private class Port(private val system: AndroidLikeClipboard) : ClipboardPort {
        private val ownership = ClipOwnership(system.events)

        override fun put(secret: String, at: Long) {
            ownership.remember(secret, at)
            system.set(ClipSnapshot(listOf("text/plain"), listOf(ClipItemSnapshot(secret))))
            ownership.startWatching()
        }

        override fun peek(): ClipboardPeek = ownership.classifyClip { system.read() }

        override fun clear() {
            system.clear()
            ownership.forget()
        }
    }

    private val lines = mutableListOf<String>()

    private fun TestScope.passTime(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun proven(body: suspend TestScope.(AndroidLikeClipboard, SecretClipboardGuard) -> Unit) = runTest {
        val system = AndroidLikeClipboard()
        val guard = SecretClipboardGuard(Port(system), backgroundScope, { testScheduler.currentTime }, SyncLog { lines += it })
        body(system, guard)
    }

    /** 复制那一下：我们拿着焦点（用户点了“复制”），随后主线程把排着的回调处理掉。 */
    private fun AndroidLikeClipboard.copied(guard: SecretClipboardGuard) {
        guard.copy(SECRET)
        drain()
    }

    @Test
    fun `窗口失去焦点（读不了剪贴板）、复制之后系统没报过任何变化：离开时当场清——证明来自监听，不来自读`() = proven { system, guard ->
        system.copied(guard)

        system.focused = false // 分屏里点了另一个窗口 / 系统对话框 / 下拉通知栏：读不了了
        guard.onLeaving()

        assertNull("没有变化事件：那里还是我们放的，当场清", system.clip)
        assertEquals(1, system.clears)
        assertEquals("清了就摘掉监听", 0, system.activeListeners)
    }

    @Test
    fun `复制之后别的应用换了内容（我们拿着焦点，收到了回调）：失焦后离开，不清，别人的东西原样还在，监听也摘掉`() = proven { system, guard ->
        system.copied(guard)
        system.otherAppCopies(FOREIGN)
        system.drain()

        system.focused = false
        guard.onLeaving()

        assertEquals("别人的东西原样还在", text(FOREIGN), system.clip)
        assertEquals("一次都没清过", 0, system.clears)
        assertEquals("证明已经不成立：监听摘掉", 0, system.activeListeners)
    }

    @Test
    fun `失焦那一刻已经把自己的清了：之后别的应用才复制（我们收不到回调），那份东西不被碰，到点也不碰`() = proven { system, guard ->
        system.copied(guard)
        system.focused = false
        guard.onLeaving()

        system.otherAppCopies(FOREIGN) // 没有焦点：没有回调，也读不了
        passTime(SecretClipboardGuard.TIMEOUT_MS)

        assertEquals("之后别人复制的原样还在", text(FOREIGN), system.clip)
        assertEquals("总共只清了一次（失焦那一刻）", 1, system.clears)
    }

    @Test
    fun `在后台到点：失焦之后系统不发回调，“没有变化事件”什么也证明不了——不清（所以事件证明只在失焦的那一刻用）`() = proven { system, guard ->
        system.copied(guard)
        system.focused = false // 失焦了但守卫没被叫（例如没走到 onLeaving）：之后别的应用复制了东西，我们不知道
        system.otherAppCopies(FOREIGN)

        passTime(SecretClipboardGuard.TIMEOUT_MS)

        assertEquals("别人复制的东西原样还在", text(FOREIGN), system.clip)
        assertEquals("盲清会抹掉别人的东西", 0, system.clears)
        assertTrue("记一条“确认不了”：$lines", lines.contains("clipboard.unverifiable"))
    }

    @Test
    fun `回到前台：能读了，按内容认——在后台时的沉默不算数；到期并且是自己的才清`() = proven { system, guard ->
        system.copied(guard)
        system.focused = false
        system.otherAppCopies(FOREIGN) // 后台里被换掉，没有任何回调
        passTime(SecretClipboardGuard.TIMEOUT_MS)
        system.focused = true

        guard.onForeground()

        assertEquals("读得到了：是别人的，不动", text(FOREIGN), system.clip)
        assertEquals(0, system.clears)
    }

    @Test
    fun `前台里到点：读得到、按内容认出是自己的就清，同时摘掉监听`() = proven { system, guard ->
        system.copied(guard)
        assertEquals("复制之后登记着一个监听", 1, system.activeListeners)

        passTime(SecretClipboardGuard.TIMEOUT_MS)

        assertNull(system.clip)
        assertEquals(0, system.activeListeners)
    }

    @Test
    fun `面板关闭、页面离开时（还有焦点，读得到）：按内容认，清，摘监听——与事件无关`() = proven { system, guard ->
        system.copied(guard)

        guard.onLeaving()

        assertNull(system.clip)
        assertEquals(0, system.activeListeners)
    }

    @Test
    fun `读得到的时候以内容为准：别人把同一段凭证文字原样复制了一遍，那还是凭证本身，照样认，哪怕收到过回调`() = proven { system, guard ->
        system.copied(guard)
        system.otherAppCopies(SECRET)
        system.drain()

        guard.onLeaving()

        assertNull("内容就是证明", system.clip)
    }

    @Test
    fun `再复制一次：旧监听在放进去之前就摘了，自己放的那一下不会回调回来被当成“别人动过”——失焦后照样清第二次放的`() = proven { system, guard ->
        system.copied(guard)
        system.copied(guard) // 重新轮换后又复制

        assertEquals("任何时候都只有一个监听", 1, system.peakListeners)
        assertEquals(1, system.activeListeners)
        system.focused = false
        guard.onLeaving()

        assertNull("第二次放的也认得出是自己的", system.clip)
        assertEquals(0, system.activeListeners)
    }

    @Test
    fun `登记在放进去之前：系统在放入那次调用里就把变化排给了它，自己放的被当成别人改的——所以真机端口先放、后登记（接线守卫钉住顺序）`() {
        val system = AndroidLikeClipboard()
        val ownership = ClipOwnership(system.events)
        ownership.remember(SECRET, at = 0)
        ownership.startWatching() // 反了：先登记
        system.set(text(SECRET))
        system.drain()

        system.focused = false

        assertEquals("自己的回调把证明弄丢了（宁可多留不误清）", ClipboardPeek.Other, ownership.classifyClip { system.read() })
    }

    @Test
    fun `没有任何监听可用（平台没给事件）：失焦读不了就不清，和以前一样——没有证明就不动`() = runTest {
        val system = AndroidLikeClipboard()
        val noEvents = object : ClipboardPort {
            private val ownership = ClipOwnership() // 没接事件
            override fun put(secret: String, at: Long) {
                ownership.remember(secret, at)
                system.set(text(secret))
                ownership.startWatching()
            }

            override fun peek() = ownership.classifyClip { system.read() }

            override fun clear() {
                system.clear()
                ownership.forget()
            }
        }
        val guard = SecretClipboardGuard(noEvents, backgroundScope, { testScheduler.currentTime })
        guard.copy(SECRET)

        system.focused = false
        guard.onLeaving()

        assertEquals("没有证明：原样留着", text(SECRET), system.clip)
        assertEquals(0, system.clears)
    }

    @Test
    fun `startWatching 连叫两次也只留一个监听；forget 之后一个都不剩`() {
        val system = AndroidLikeClipboard()
        val ownership = ClipOwnership(system.events)
        ownership.remember(SECRET, at = 0)

        ownership.startWatching()
        ownership.startWatching()
        assertEquals(1, system.activeListeners)

        ownership.forget()
        assertEquals(0, system.activeListeners)
    }

    @Test
    fun `还没有东西要核实（从没放过）时 startWatching 什么也不登记`() {
        val system = AndroidLikeClipboard()

        ClipOwnership(system.events).startWatching()

        assertEquals(0, system.activeListeners)
    }

    @Test
    fun `登记监听时平台抛了异常：当作没有证明（不清），也不带倒复制`() {
        val broken = ClipChangeEvents { error("listener registration refused") }
        val system = AndroidLikeClipboard()
        val ownership = ClipOwnership(broken)
        ownership.remember(SECRET, at = 0)
        system.set(text(SECRET))

        ownership.startWatching()
        system.focused = false

        assertEquals(ClipboardPeek.Unreadable, ownership.classifyClip { system.read() })
    }
}
