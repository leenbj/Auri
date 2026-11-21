package app.auri.android.destinations

import java.security.MessageDigest

/**
 * “剪贴板里现在是不是我们放的那一份”的证明（再评 S11）：**按内容**，不看任何别的应用也读得到、抄得走、伪造得出的东西。
 *
 * 旧做法在剪贴板描述里放一个额外字段当标记。描述连同内容一起对所有读剪贴板的应用可见：别的应用复制了会议室门牌，
 * 再把这个字段整份抄上去，我们就会把它当成自己放的清掉。现在放进去时只在**内存里**记下这段文字的 SHA-256 与放入时刻——
 * 不记文字本身，更不落盘；之后读到的当前文字与它摘要相同才是我们的，描述里的任何字段一概不看。
 *
 * - 手里没有东西要核实（从没放过、已经清了、已经发现被别人顶掉了）就一次也不读剪贴板：别人复制的内容，能不读就不读
 *   （Android 12 起，应用读到别的应用复制的内容，系统会向用户弹一条“某某应用读取了你复制的内容”）。
 * - 读不了（[classify] 的 `currentText` 返回 null：不在前台，或剪贴板是空的）：摘要保留，等读得到的时候再核对。
 * - 进程重启后摘要随内存一起没了：认不出归属，一律当别人的、不清。这是为“绝不误清别人的东西”付的代价：
 *   复制之后 60 秒内进程被杀，凭证会留在剪贴板里，直到被覆盖或系统自己清（验收 README 的残余风险）。
 *
 * 只在一个线程上用（主线程，同 [SecretClipboardGuard]）。
 */
class ClipOwnership {
    private class Mine(val digest: ByteArray, val at: Long)

    private var mine: Mine? = null

    /** 记下刚放进剪贴板的这一份（只留摘要）；再放一次就换成新的。 */
    fun remember(secret: String, at: Long) {
        mine = Mine(sha256(secret), at)
    }

    /** 我们自己清掉了：不再有什么要核实的。 */
    fun forget() {
        mine = null
    }

    /**
     * [currentText]：此刻剪贴板里的文字；读不了（不在前台）或是空的返回 null，不是文字的内容（图片、文件）返回 ""。
     * 只在手里有摘要要核实时才会被调用。
     */
    fun classify(currentText: () -> String?): ClipboardPeek {
        val ours = mine ?: return ClipboardPeek.Other
        val text = currentText() ?: return ClipboardPeek.Unreadable
        if (MessageDigest.isEqual(sha256(text), ours.digest)) return ClipboardPeek.Ours(ours.at)
        mine = null // 我们放的那一份已经被顶掉了：没什么可清的，也不用再去读别人复制的东西
        return ClipboardPeek.Other
    }

    private fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
}
