package app.auri.android.destinations

import app.auri.sync.SyncLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 剪贴板里现在是什么。只分得出归属；凭证本身不经过这里。 */
sealed interface ClipboardPeek {
    /** 我们放的那一份；[at] 是放进去时记在剪贴板描述里的时刻（毫秒，开机后的单调时钟）。 */
    data class Ours(val at: Long) : ClipboardPeek

    /** 别的应用复制的东西。 */
    data object Other : ClipboardPeek

    /** 读不了：Android 10 起不在前台的应用读到的剪贴板一律是“空”；真的空了也落在这里。 */
    data object Unreadable : ClipboardPeek
}

/** 系统剪贴板的三个动作。真机上是 ClipboardManager（`ui/SecretWindow.kt` 的 AndroidClipboardPort），测试里是假的。 */
interface ClipboardPort {
    /** 放进去（标成敏感内容），并在剪贴板描述里记下 [at]（不含凭证），之后凭它认出这一份还是不是我们放的。 */
    fun put(secret: String, at: Long)

    fun peek(): ClipboardPeek

    /** 清空当前剪贴板；是谁放的由调用方先用 [peek] 判断。 */
    fun clear()
}

/**
 * 凭证复制进剪贴板之后的清理（Ticket 22 评审 B5）：到 [TIMEOUT_MS] 就清，且只清自己放的那一份。
 *
 * 为什么是“到点清”，而不是评审提的“面板关闭、退到后台就清”：凭证复制出去只有一个用处——粘贴到别处，而那几乎总要退出本应用
 * （复制的那一下面板本来就关了）。一退后台就清，等于把刚复制的东西当场删掉，用户拿不到凭证，只能再轮换一次。
 * 所以 60 秒的期限在任何状态下都管用（定时器；回到前台、进程重启后也会补查），而不是挑这两个时刻。这是一处有意的偏离，已写进评审修复说明，由产品拍板。
 *
 * “只清自己放的”：放进去时在剪贴板描述里记一个标记和时刻（不含凭证）。读得到时按标记认——别的应用后来复制的东西不动。
 * 读不到时（Android 10 起不在前台的应用读到的是空，**写和清不受限**）认不出归属：到点照样清（宁可多清一次，也不让凭证留着）。
 * 这是评审“绝不清新的无关剪贴板”在后台时无法保证的一处：Android 不让后台应用看，没有别的办法。
 *
 * 进程在 60 秒内被系统杀掉时定时器随之消失；下次回到前台 [onForeground] 认剪贴板里自己的标记，到期就清（标记里自带放进去的时刻）。
 * 在那之前剪贴板里的凭证还在——Android 13 起系统自己约一小时后清，更早的版本只能等被覆盖（真机 `NOT_RUN`）。
 *
 * 只在一个线程上用（主线程；[scope] 的调度器必须是单线程）。API：`clearPrimaryClip` 是 API 28 才有的，应用 minSdk 31，没有缺它的系统（见 ClipboardWiringTest）。
 */
class SecretClipboardGuard(
    private val port: ClipboardPort,
    private val scope: CoroutineScope,
    /** 开机后的单调时钟（毫秒）：写进剪贴板标记的时刻就是它，设备重启后会变小。 */
    private val elapsedMs: () -> Long,
    private val log: SyncLog = SyncLog.None,
) {
    private var timer: Job? = null

    /** 把凭证放进剪贴板并开始计时；再复制一次就重新计时（旧的定时器作废，不能提前清掉新的）。 */
    fun copy(secret: String) {
        port.put(secret, elapsedMs())
        timer?.cancel()
        timer = scope.launch {
            delay(TIMEOUT_MS)
            expire()
        }
        log.event("clipboard.copied")
    }

    /** 回到前台（窗口拿到焦点之后才读得了剪贴板）：剪贴板里是自己放的、且已到期（定时器随被杀的进程丢了，或设备重启过）就清；没到期不动（用户可能正带着它回来看一眼）。 */
    fun onForeground() {
        val ours = port.peek() as? ClipboardPeek.Ours ?: return
        val age = elapsedMs() - ours.at
        if (age < 0 || age >= TIMEOUT_MS) {
            timer?.cancel()
            clear()
        }
    }

    private fun expire() {
        when (port.peek()) {
            ClipboardPeek.Other -> log.event("clipboard.kept-other")
            is ClipboardPeek.Ours, ClipboardPeek.Unreadable -> clear()
        }
    }

    private fun clear() {
        port.clear()
        log.event("clipboard.cleared")
    }

    companion object {
        const val TIMEOUT_MS = 60_000L
    }
}
