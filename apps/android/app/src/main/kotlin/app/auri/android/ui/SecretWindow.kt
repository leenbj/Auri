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
import app.auri.android.destinations.ClipOwnership
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
 * 之后 [app.auri.android.destinations.SecretClipboardGuard] 靠 [ClipOwnership] 认出“剪贴板里还是不是我们放的”——按内容（内存里的摘要）认，
 * 不看描述里的额外字段：那是别的应用抄得走、伪造得出的（再评 S11）。清理的规则与取舍见那个类。
 * 这一层只在设备上验证（`execution/acceptance/android-destinations/README.md` 第 4 节，本机没有设备，`NOT_RUN`）。
 */
class AndroidClipboardPort(context: Context) : ClipboardPort {
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val ownership = ClipOwnership()

    override fun put(secret: String, at: Long) {
        val clip = ClipData.newPlainText("Auri", secret)
        clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        ownership.remember(secret, at)
        clipboard?.setPrimaryClip(clip)
    }

    // 读不了（不在前台）与剪贴板是空的，系统都返回 null：都算读不了；不是文字的内容（图片、文件）读成空串，摘要必然对不上
    override fun peek(): ClipboardPeek = ownership.classify {
        clipboard?.primaryClip?.let { clip -> if (clip.itemCount > 0) clip.getItemAt(0).text?.toString().orEmpty() else "" }
    }

    override fun clear() {
        clipboard?.clearPrimaryClip() // API 28 起有；应用 minSdk 31
        ownership.forget()
    }
}

// ClipDescription.EXTRA_IS_SENSITIVE 是 API 33 才有的常量；旧系统会忽略这个额外字段，字符串是同一个
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
