package app.auri.android.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.ContextWrapper
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

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
 * 把凭证放进系统剪贴板，并标成敏感内容（系统的剪贴板预览里不显示出来）。
 * 已知限制：剪贴板里的内容会留到被别的复制覆盖——这里没办法保证清掉（Android 10 起后台应用读不到、也清不了剪贴板）。
 * 所以复制之后凭证从界面抹掉，用户应当马上粘贴给 Agent，再复制一段别的东西。
 */
fun copySecretToClipboard(context: Context, secret: String) {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText("Auri", secret)
    clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
    clipboard.setPrimaryClip(clip)
}

// ClipDescription.EXTRA_IS_SENSITIVE 是 API 33 才有的常量；旧系统会忽略这个额外字段，字符串是同一个
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
