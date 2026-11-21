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
 * 凭证复制进剪贴板之后的清理（Ticket 22 评审 B5，复评 R6 收紧）。
 *
 * **铁律：没有证明“剪贴板里现在还是我们放的那一份”，就绝不清。** 证明只有一种：读得到剪贴板，并在描述里认出我们放进去时记的标记（不含凭证）。
 * Android 10 起，不在前台（没有输入焦点）的应用读剪贴板得到的是“空”，而**写与清却不受限**：后台里的“清”是盲的，
 * 盲清会把用户后来复制的别的东西（会议室门牌、验证码、另一份凭证）一并抹掉——这比凭证多留一会儿更糟，也无法向用户交代。读不了就不动。
 *
 * 清的时机，全部要求“读得到且认得出是自己的”：
 * 1. [onLeaving]：面板关闭 / 离开详情页 / 应用即将失去焦点（Activity.onPause：窗口通常还有焦点，是应用最后一次读得到剪贴板的时刻）——当场清；
 * 2. 定时器：复制之后满 [TIMEOUT_MS] 应用还在前台——清；
 * 3. [onForeground]：回到前台（窗口拿到焦点）发现自己放的已到期（定时器随被杀的进程丢了、设备重启过、或到点时人在后台没法确认）——清。
 * 读不到的时候（应用已在后台）凭证会留在剪贴板里，直到被覆盖或系统自己清（Android 13 起约一小时后）。这是 Android 平台的限制，补不了：
 * 能做的是放进去时标成敏感内容（EXTRA_IS_SENSITIVE：系统的剪贴板预览与输入法不展示内容）、复制之后明说（[describeClipboardNotice]）、
 * 在验收 README 里如实记录残余风险（真机行为 `NOT_RUN`）。
 *
 * 取舍（复评 R6）：“离开本应用就清”意味着复制之后切到别的应用再粘贴不再行得通——凭证只显示一次，用户要么在本应用还在前台时（分屏、悬浮窗）粘贴，
 * 要么照屏幕上显示的抄，要么轮换出新的。若产品要保留跨应用粘贴，那是另一个决定（放宽成“只在前台里到点才清”），这里不替它做，已写进 README 请产品拍板。
 *
 * 进程在 60 秒内被系统杀掉时定时器随之消失；下次回到前台 [onForeground] 认剪贴板里自己的标记，到期就清（标记里自带放进去的时刻）。
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

    /**
     * 面板关闭、离开详情页、应用即将失去焦点：读得到、并且认得出是自己放的，就当场清、作废定时器；读不到（没有归属证明）或是别人的，不动。
     * 复制那一下本身不算离开（复制之后面板是抹掉的，但刚复制的东西要留着给用户粘贴）。
     */
    fun onLeaving() {
        if (port.peek() is ClipboardPeek.Ours) {
            timer?.cancel()
            clear()
        }
    }

    private fun expire() {
        when (port.peek()) {
            is ClipboardPeek.Ours -> clear()
            ClipboardPeek.Other -> log.event("clipboard.kept-other")
            // 读不了就认不出归属：宁可让凭证多留一会儿，也不盲清（复评 R6）；回到前台时 [onForeground] 再认
            ClipboardPeek.Unreadable -> log.event("clipboard.unverifiable")
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
