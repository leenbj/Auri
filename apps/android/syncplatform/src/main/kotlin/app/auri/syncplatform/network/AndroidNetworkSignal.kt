package app.auri.syncplatform.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import app.auri.sync.loop.NetworkSignal
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * 系统“现在有没有默认网络”的信号（ConnectivityManager 的默认网络回调）：同步循环靠它在网络恢复时立刻重试、
 * 在断网时把没有请求在途的任务改成“等网关”。
 *
 * 只回答有没有网络，不回答网关连不连得上：网络在、网关不在是另一种情形，由循环里的探测处理。
 * 订阅时先发出当前的状态，再随回调更新；取消订阅一定注销回调。
 *
 * 已知的限制（真机项，见验收文档）：网关在没有外网的局域网 Wi-Fi 上、而手机同时开着移动数据时，系统可能把默认网络
 * 给蜂窝，此时发往局域网地址的连接会走蜂窝而连不上；要解决得把套接字绑到 Wi-Fi 网络上，这一版没有做。
 */
class AndroidNetworkSignal(context: Context) : NetworkSignal {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override val available: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }

            override fun onLost(network: Network) {
                trySend(false)
            }
        }
        trySend(manager.activeNetwork != null)
        manager.registerDefaultNetworkCallback(callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.conflate()
}
