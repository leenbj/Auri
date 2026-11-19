package app.auri.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 把一个网址交给系统浏览器看：本应用自己不联网取它（模型许可全文就是这样打开的）。
 * 整个应用里只有这一个类可以发“查看网址”的意图（网络卫士的 view-intent 规则，评审 F1）；没有浏览器时打不开，地址仍显示在页面上，可以抄下来。
 */
internal class BrowserLink(private val context: Context) {
    fun open(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            // 见类注释
        }
    }
}
