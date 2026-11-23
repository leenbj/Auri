package app.auri.android.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import app.auri.android.destinations.ClipChangeEvents
import app.auri.android.destinations.ClipItemSnapshot
import app.auri.android.destinations.ClipOwnership
import app.auri.android.destinations.ClipSnapshot
import app.auri.android.destinations.ClipboardPeek
import app.auri.android.destinations.ClipboardPort

/**
 * 凭证显示期间：窗口不许截图、录屏，最近任务里也不显示内容（FLAG_SECURE）。只在 [shown] 为 true 时设，离开（或不再显示）就还原。
 * 设备上的效果见 execution/acceptance/android-destinations/README.md 的真机步骤（本机没有设备，NOT_RUN）。
 */
@Composable
fun SecureWhileShown(shown: Boolean) {
    val window = LocalContext.current.findActivity()?.window
    DisposableEffect(shown, window) {
        if (shown) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (shown) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * [ClipboardPort] 在真机上的一侧：系统剪贴板。放进去时标成敏感内容（系统的剪贴板预览里不显示）；
 * 之后 [app.auri.android.destinations.SecretClipboardGuard] 靠 [ClipOwnership] 认出“剪贴板里还是不是我们放的”：
 * 读得到就按内容与形状认（整份剪贴板：一项、只有 text/plain、只有文字、摘要相同；不看描述里的额外字段——那是别的应用抄得走、伪造得出的，再评 S11、第四轮 F10），
 * 窗口失去焦点读不了时靠复制之后登记的变化监听认（第四轮 F3）。清理的规则与取舍见那个类。
 * 这一层只在设备上验证（`execution/acceptance/android-destinations/README.md` 第 4 节，本机没有设备，`NOT_RUN`）。
 */
class AndroidClipboardPort(context: Context) : ClipboardPort {
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val ownership = ClipOwnership(clipboard?.let(::AndroidClipChanges))

    override fun put(secret: String, at: Long) {
        val clip = ClipData.newPlainText("Auri", secret)
        clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        // 先摘旧的监听、记摘要
        ownership.remember(secret, at)
        clipboard?.setPrimaryClip(clip)
        // 放进去**之后**才登记：系统在放入那次调用里把变化排给当时已登记的监听者，先登记的话自己放的那一下会回调回来
        ownership.startWatching()
    }

    // 读不了（不在前台）与剪贴板是空的，系统都返回 null：都算读不了；整份剪贴板的形状与内容要与放进去时一样才认
    override fun peek(): ClipboardPeek = ownership.classifyClip { clipboard?.primaryClip?.snapshot() }

    override fun clear() {
        clipboard?.clearPrimaryClip() // API 28 起有；应用 minSdk 31
        // 摘监听、忘摘要
        ownership.forget()
    }
}

/**
 * [ClipChangeEvents] 的 Android 一侧：`ClipboardManager` 的变化监听（回调在主线程）。系统只把它发给拿着焦点（或默认输入法）的应用；
 * 句柄关掉就摘（摘到没有监听时，系统那边的登记也撤了），所以放进剪贴板之前摘掉旧的，放入的那一下就不会回调到任何东西。
 */
private class AndroidClipChanges(private val clipboard: ClipboardManager) : ClipChangeEvents {
    override fun listen(onChanged: () -> Unit): AutoCloseable {
        val listener = ClipboardManager.OnPrimaryClipChangedListener { onChanged() }
        clipboard.addPrimaryClipChangedListener(listener)
        return AutoCloseable { clipboard.removePrimaryClipChangedListener(listener) }
    }
}

/** 整份剪贴板的形状：类型、每一项的文字，以及这一项是否还带着 HTML、URI、Intent。只记有没有，不解析 URI、不读别的内容。 */
private fun ClipData.snapshot() = ClipSnapshot(
    mimeTypes = List(description.mimeTypeCount) { description.getMimeType(it) },
    items = List(itemCount) { index ->
        val item = getItemAt(index)
        ClipItemSnapshot(text = item.text?.toString(), hasHtml = item.htmlText != null, hasUri = item.uri != null, hasIntent = item.intent != null)
    },
)

// ClipDescription.EXTRA_IS_SENSITIVE 是 API 33 才有的常量；旧系统会忽略这个额外字段，字符串是同一个
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
