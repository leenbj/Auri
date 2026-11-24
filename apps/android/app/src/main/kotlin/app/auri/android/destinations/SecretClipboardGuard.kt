package app.auri.android.destinations

import app.auri.sync.SyncLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 剪贴板里现在是什么。只分得出归属；凭证本身不经过这里。 */
sealed interface ClipboardPeek {
    /** 我们放的那一份（内容与放进去时一致，见 [ClipOwnership]）；[at] 是放进去的时刻（毫秒，开机后的单调时钟），端口在内存里记着。 */
    data class Ours(val at: Long) : ClipboardPeek

    /** 别的应用复制的东西；或者我们认不出归属的（进程重启过，内存里没有摘要可对）——都当别人的。 */
    data object Other : ClipboardPeek

    /** 读不了：Android 10 起不在前台的应用读到的剪贴板一律是“空”；真的空了也落在这里。 */
    data object Unreadable : ClipboardPeek

    /**
     * 读不了（没有焦点），但从我们复制到现在，系统没有报告过剪贴板变了（变化监听，见 [ClipOwnership.startWatching]）：
     * 在我们拿着焦点的那段时间里别人动不了它而不被我们知道，所以那里还是我们放的那一份。
     * **只在失去焦点的那一刻有效**：没有焦点时系统既不让读、也不发变化回调，之后的沉默什么也证明不了——所以只有 [SecretClipboardGuard.onLeaving] 认它。
     */
    data object Untouched : ClipboardPeek
}

/** 系统剪贴板的三个动作。真机上是 ClipboardManager（`ui/SecretWindow.kt` 的 AndroidClipboardPort），测试里是假的。 */
interface ClipboardPort {
    /** 放进去（标成敏感内容），并在内存里记下它的内容摘要与 [at]（不记凭证文字、不落盘）、在放进去**之后**登记变化监听，之后凭这两样认出这一份还是不是我们放的。 */
    fun put(secret: String, at: Long)

    fun peek(): ClipboardPeek

    /** 清空当前剪贴板；是谁放的由调用方先用 [peek] 判断。 */
    fun clear()
}

/**
 * 凭证复制进剪贴板之后的清理（Ticket 22 评审 B5，复评 R6 收紧）。
 *
 * **铁律：没有证明“剪贴板里现在还是我们放的那一份”，就绝不清。** 证明有两种（[ClipOwnership]），剪贴板描述里的额外字段都不算：别的应用读得到、抄得走、伪造得出（再评 S11）：
 * 1. 读得到剪贴板，并且读到的是我们放进去时的原样——一项、只有 `text/plain`、只有文字，整段文字的 SHA-256 与内存里记下的一致（[ClipboardPeek.Ours]；第四轮 F10 起不再只比第 0 项）；
 * 2. 读不了（窗口已失去焦点），但复制之后登记的变化监听从没收到过“剪贴板变了”（[ClipboardPeek.Untouched]；第四轮 F3）：我们拿着焦点的那段时间里别人动不了它而不被我们知道。
 *    **这一种只在失去焦点的那一刻有效**：没有焦点时系统既不让读、也不发变化回调，之后的沉默什么也证明不了——所以只有 [onLeaving] 认它，到点的定时器与回到前台都不认。
 * Android 10 起，不在前台（没有输入焦点）的应用读剪贴板得到的是“空”，而**写与清却不受限**：没有证明的“清”是盲的，
 * 盲清会把用户后来复制的别的东西（会议室门牌、验证码、另一份凭证）一并抹掉——这比凭证多留一会儿更糟，也无法向用户交代。没有证明就不动。
 *
 * 清的时机，全部要求有上面两种证明之一（第 2 种只限第 1 条）：
 * 1. [onLeaving]：面板关闭 / 离开详情页 / 应用即将失去焦点（Activity.onPause：窗口通常还有焦点，读得到，按内容认；
 *    窗口失去焦点而 Activity 不暂停——分屏里点了另一个窗口、系统对话框、下拉通知栏——同样走这里，再评 S10；那一刻已经读不了，靠变化监听认，第四轮 F3）——当场清；
 * 2. 定时器：复制之后满 [TIMEOUT_MS] 应用还在前台（读得到、按内容认）——清；
 * 3. [onForeground]：回到前台（窗口拿到焦点）发现自己放的已到期（读得到、按内容认；到点时人在后台没法确认、或进程被冻结过没能按时清）——清。
 * 两种证明都拿不到的时候（失焦那一刻之外的后台、变化监听登记不上）凭证会留在剪贴板里，直到被覆盖或系统自己清（Android 13 起约一小时后）。这是 Android 平台的限制，补不了：
 * 能做的是放进去时标成敏感内容（EXTRA_IS_SENSITIVE：系统的剪贴板预览与输入法不展示内容）、复制之后明说（[describeClipboardNotice]）、
 * 在验收 README 里如实记录残余风险（真机行为 `NOT_RUN`）。
 *
 * 取舍（复评 R6，再评 S10 收紧）：“离开本应用就清”意味着复制之后切到别的应用再粘贴不再行得通——分屏里点另一个窗口也一样（那一下窗口就失焦了）；
 * 凭证只显示一次，用户要么照屏幕上显示的抄，要么轮换出新的。若产品要保留跨应用粘贴，那是另一个决定（放宽成“只在前台里到点才清”），
 * 这里不替它做，已写进 README 请产品拍板。
 *
 * 进程在 60 秒内被系统杀掉（或设备重启）时，定时器与内存里的摘要一起消失：之后认不出归属，凭证留在剪贴板里直到被覆盖或系统自己清。
 * 这是为“绝不误清别人的东西”付的代价（再评 S11：摘要不落盘，凭证文字更不落盘；README 残余风险）。
 *
 * 只在一个线程上用（主线程；[scope] 的调度器必须是单线程）。API：`clearPrimaryClip` 是 API 28 才有的，应用 minSdk 31，没有缺它的系统（见 ClipboardWiringTest）。
 */
class SecretClipboardGuard(
    private val port: ClipboardPort,
    private val scope: CoroutineScope,
    /** 开机后的单调时钟（毫秒）：交给端口记下的放入时刻就是它。 */
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

    /** 回到前台（窗口拿到焦点之后才读得了剪贴板）：剪贴板里是自己放的、且已到期（到点时人在后台没清成，或进程被冻结过）就清；没到期不动（用户可能正带着它回来看一眼）。 */
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
        when (port.peek()) {
            // 读得到并按内容认出是自己的；或者失焦的这一刻读不了，但从复制到现在系统没报过变化（第四轮 F3）：当场清
            is ClipboardPeek.Ours, ClipboardPeek.Untouched -> {
                timer?.cancel()
                clear()
            }
            ClipboardPeek.Other, ClipboardPeek.Unreadable -> Unit
        }
    }

    private fun expire() {
        when (port.peek()) {
            is ClipboardPeek.Ours -> clear()
            ClipboardPeek.Other -> log.event("clipboard.kept-other")
            // 读不了就认不出归属：宁可让凭证多留一会儿，也不盲清（复评 R6）；回到前台时 [onForeground] 再认。
            // Untouched 也在这里：到点时多半在后台，没有焦点时系统不发变化回调，“没收到变化”什么也证明不了；这条证明只在失焦的那一刻用（[onLeaving]）
            ClipboardPeek.Unreadable, ClipboardPeek.Untouched -> log.event("clipboard.unverifiable")
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
