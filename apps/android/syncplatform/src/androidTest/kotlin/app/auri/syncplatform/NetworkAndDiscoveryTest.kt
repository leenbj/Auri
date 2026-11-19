package app.auri.syncplatform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.auri.syncplatform.discovery.NsdGatewayDiscovery
import app.auri.syncplatform.network.AndroidNetworkSignal
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统网络回调与 NSD 的平台层。模拟器的网络是 NAT，多播过不去，所以“真的在局域网里发现网关”只有真机能证明（验收文档里是 NOT_RUN）；
 * 这里证明：网络信号跟着飞行模式变化、发现在没有任何广告时按时空手而归且不抛异常。
 */
class NetworkAndDiscoveryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun 网络信号跟着飞行模式走_开启后变成_false_关闭后变回_true() = runBlocking {
        val signal = AndroidNetworkSignal(context)
        val wasOn = shell("settings get global airplane_mode_on").trim() == "1"
        try {
            shell("cmd connectivity airplane-mode disable")
            assertEquals("没有开飞行模式时应有默认网络", true, withTimeout(30_000) { signal.available.first { it } })

            shell("cmd connectivity airplane-mode enable")
            assertEquals("开了飞行模式应报告没有网络", false, withTimeout(30_000) { signal.available.first { !it } })

            shell("cmd connectivity airplane-mode disable")
            assertEquals("关掉飞行模式应恢复", true, withTimeout(30_000) { signal.available.first { it } })
        } finally {
            shell("cmd connectivity airplane-mode ${if (wasOn) "enable" else "disable"}")
        }
    }

    @Test
    fun 发现在没有任何广告时按时返回空_不抛异常() = runBlocking {
        val discovery = NsdGatewayDiscovery(context, listenMs = 1_500)
        val started = System.nanoTime()

        val found = discovery.find("fa1b98ec-1bfb-53b3-85a3-4d36b680fe6e")

        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(emptyList<Any>(), found)
        assertTrue("发现应在限定时间内结束，实际 ${elapsedMs}ms", elapsedMs < 6_000)
    }
}
