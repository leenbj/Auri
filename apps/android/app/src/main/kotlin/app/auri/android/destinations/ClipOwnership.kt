package app.auri.android.destinations

import java.security.MessageDigest

/**
 * 平台把“剪贴板变了”的回调接进来的缝（Android：`ClipboardManager.OnPrimaryClipChangedListener`；回调在主线程）。
 * 只有拿着焦点的应用才收得到这个回调（Android 10 起，系统只把它发给“允许读剪贴板”的监听者）——所以“一直没收到”只在我们拿着焦点的那段时间里说明什么，
 * 失去焦点之后的沉默什么也证明不了（[ClipboardPeek.Untouched] 因此只在失焦的那一刻有效）。
 */
fun interface ClipChangeEvents {
    /** 开始听；返回的句柄 close 一次就不再听（再关无妨）。平台拒绝登记时可以抛异常，调用方当作“没有这条证明”。 */
    fun listen(onChanged: () -> Unit): AutoCloseable
}

/** 读到的剪贴板的完整形状：不带 Android 类型，JVM 里也能测。 */
data class ClipSnapshot(val mimeTypes: List<String>, val items: List<ClipItemSnapshot>)

/** 剪贴板里的一项：文字，以及它是否还带着别的载荷（只记有没有，不去解析内容）。没有文字（图片、文件）时 [text] 为 null。 */
data class ClipItemSnapshot(val text: String?, val hasHtml: Boolean = false, val hasUri: Boolean = false, val hasIntent: Boolean = false)

/**
 * “剪贴板里现在是不是我们放的那一份”的证明（再评 S11 起；第四轮 F3/F10 补全）。两种证明，都不看任何别的应用也读得到、抄得走、伪造得出的东西：
 *
 * 1. **读得到时按内容与形状**（[classifyClip]）：放进去时只在**内存里**记下这段文字的 SHA-256 与放入时刻（不记文字本身，更不落盘）；
 *    读到的剪贴板必须是我们放进去时的**原样**——恰好一项、类型只有 `text/plain`、这一项只有文字（没有 HTML、URI、Intent），整段文字的摘要相同——才是我们的。
 *    只比第 0 项会把别的应用复制的多项内容（第 0 项恰好与凭证相同）当成自己的清掉，连带抹掉后面那些别人的东西（第四轮 F10）。
 * 2. **读不了时看监听**（[startWatching]）：Android 10 起窗口一失去焦点就读不了剪贴板，可清是不受限的。复制之后立刻登记变化监听：
 *    从复制到现在没收到过任何“变了”，而我们这段时间一直拿着焦点（别人动剪贴板我们一定收得到），那里就还是我们放的那一份（[ClipboardPeek.Untouched]）。
 *    收到过任何一次变化，就不是我们的了：忘掉，绝不清（第四轮 F3）。**这条证明只在失去焦点的那一刻有效**，用不用由 [SecretClipboardGuard] 按调用点决定。
 *
 * 监听的生命周期：放进去之前先摘旧的、放进去之后才登记新的（系统在“放进去”那次调用里就把变化排给了当时已登记的监听者，回调稍后才到，
 * 先登记或旧监听没摘，自己放的那一下就会回调回来，被当成别人动过）；忘掉（清了、被顶掉、认不出）就摘；不会同时有两个。进程被杀，监听与摘要一起没了。
 *
 * - 手里没有东西要核实（从没放过、已经清了、已经发现被别人顶掉了）就一次也不读剪贴板：别人复制的内容，能不读就不读
 *   （Android 12 起，应用读到别的应用复制的内容，系统会向用户弹一条“某某应用读取了你复制的内容”）。
 * - 读不了又没有监听可凭（[events] 是 null、平台拒绝登记）：摘要保留，等读得到的时候再核对。
 * - 进程重启后摘要随内存一起没了：认不出归属，一律当别人的、不清。这是为“绝不误清别人的东西”付的代价：
 *   复制之后 60 秒内进程被杀，凭证会留在剪贴板里，直到被覆盖或系统自己清（验收 README 的残余风险）。
 *
 * 只在一个线程上用（主线程，同 [SecretClipboardGuard]；监听回调也在主线程）。
 *
 * @param events 变化回调；null 就是没有监听这条证明（测试替身、没有剪贴板服务）。
 */
class ClipOwnership(private val events: ClipChangeEvents? = null) {
    private class Mine(val digest: ByteArray, val at: Long)

    private var mine: Mine? = null
    private var watch: AutoCloseable? = null

    @Volatile private var changed = false

    /** 记下刚放进剪贴板的这一份（只留摘要）；再放一次就换成新的。**必须在放进去之前调**：它先摘掉旧的监听，免得放入的回调叫到旧监听。 */
    fun remember(secret: String, at: Long) {
        stopWatching()
        mine = Mine(sha256(secret), at)
        changed = false
    }

    /** 放进剪贴板**之后**调：开始听变化。没有要核实的东西、或者平台不给事件、拒绝登记，就没有监听可凭（读不了时认不出）。 */
    fun startWatching() {
        stopWatching()
        if (mine == null) return
        changed = false
        watch = events?.let { source -> runCatching { source.listen { changed = true } }.getOrNull() }
    }

    /** 我们自己清掉了、或者认出已经不是我们的：不再有什么要核实的，监听也摘掉。 */
    fun forget() {
        mine = null
        stopWatching()
    }

    /**
     * 单项文字的简写：[currentText] 是此刻剪贴板里的文字，读不了（不在前台）或是空的返回 null，不是文字的内容返回 ""。
     * 等价于只有一项纯文字的 [classifyClip]（测试替身用；真机端口读整份剪贴板，走 [classifyClip]）。
     */
    fun classify(currentText: () -> String?): ClipboardPeek =
        classifyClip { currentText()?.let { ClipSnapshot(ORIGINAL_MIME_TYPES, listOf(ClipItemSnapshot(it))) } }

    /**
     * [read]：此刻整份剪贴板；读不了（不在前台）或是空的返回 null。只在手里有摘要要核实时才会被调用。
     * 读得到：形状与内容都和放进去时一样才是 [ClipboardPeek.Ours]，否则忘掉、是别人的。
     * 读不了：收到过变化就是别人的（忘掉）；没收到过且监听着，是 [ClipboardPeek.Untouched]（只在失焦那一刻有效）；没有监听可凭就是 [ClipboardPeek.Unreadable]。
     */
    fun classifyClip(read: () -> ClipSnapshot?): ClipboardPeek {
        val ours = mine ?: return ClipboardPeek.Other
        val clip = try {
            read()
        } catch (_: Exception) {
            return ClipboardPeek.Unreadable
        }
        if (clip == null) {
            if (changed) {
                forget() // 系统报过剪贴板变了：不是我们放的那一份了
                return ClipboardPeek.Other
            }
            return if (watch != null) ClipboardPeek.Untouched else ClipboardPeek.Unreadable
        }
        val item = clip.items.singleOrNull()
        val text = item?.text
        val original = clip.mimeTypes == ORIGINAL_MIME_TYPES && item != null && text != null && !item.hasHtml && !item.hasUri && !item.hasIntent
        if (original && MessageDigest.isEqual(sha256(text), ours.digest)) return ClipboardPeek.Ours(ours.at)
        forget() // 我们放的那一份已经被顶掉了：没什么可清的，也不用再去读别人复制的东西
        return ClipboardPeek.Other
    }

    private fun stopWatching() {
        watch?.close()
        watch = null
    }

    private fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

    private companion object {
        /** 我们放进去时的类型：`ClipData.newPlainText` 就是只有这一种。 */
        val ORIGINAL_MIME_TYPES = listOf("text/plain")
    }
}
