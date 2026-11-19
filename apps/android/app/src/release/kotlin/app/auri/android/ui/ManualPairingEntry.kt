package app.auri.android.ui

import androidx.compose.runtime.Composable

/**
 * release 版没有“粘贴配对载荷”的入口（评审 F14）：真实现只在 src/debug 里，这里什么都不画、也不接收任何文字。
 * 签名与 debug 版一致，同步页（src/main）两个变体都能调用。
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun ManualPairingEntry(onSubmit: (String) -> Unit, enabled: Boolean) = Unit
