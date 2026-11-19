package app.auri.syncplatform.discovery

import app.auri.sync.pairing.GatewayAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * mDNS 里谁都能发广告：这一层只把“同一个网关的、合格的”广告变成候选地址，别的一律丢掉。
 * 就算漏过了一个冒充者也不会被信任（握手时按公钥指纹拒绝），但不合格的地址不该进入连接与 Host 头。
 */
class GatewayAdvertisementTest {
    private val gatewayId = "fa1b98ec-1bfb-53b3-85a3-4d36b680fe6e"
    private val attributes = mapOf("gateway_id" to gatewayId)

    @Test
    fun `同一个网关的广告变成候选地址`() {
        assertEquals(GatewayAddress("192.168.1.20", 8443), gatewayAddressFrom("192.168.1.20", 8443, attributes, gatewayId))
    }

    @Test
    fun `别的网关的广告不是候选`() {
        assertNull(gatewayAddressFrom("192.168.1.99", 8443, mapOf("gateway_id" to "00000000-0000-5000-8000-000000000000"), gatewayId))
    }

    @Test
    fun `没有 gateway_id 的广告不是候选`() {
        assertNull(gatewayAddressFrom("192.168.1.20", 8443, emptyMap(), gatewayId))
        assertNull(gatewayAddressFrom("192.168.1.20", 8443, mapOf("gateway_id" to ""), gatewayId))
    }

    @Test
    fun `没有 IPv4 地址（解析不出主机）的广告不是候选`() {
        assertNull(gatewayAddressFrom(null, 8443, attributes, gatewayId))
        assertNull(gatewayAddressFrom("", 8443, attributes, gatewayId))
    }

    @Test
    fun `端口不合法的广告不是候选`() {
        assertNull(gatewayAddressFrom("192.168.1.20", 0, attributes, gatewayId))
        assertNull(gatewayAddressFrom("192.168.1.20", 65536, attributes, gatewayId))
        assertNull(gatewayAddressFrom("192.168.1.20", -1, attributes, gatewayId))
    }

    @Test
    fun `主机里带空白或换行的广告不是候选（它会被写进 Host 头）`() {
        assertNull(gatewayAddressFrom("192.168.1.20\r\nX-Evil: 1", 8443, attributes, gatewayId))
        assertNull(gatewayAddressFrom("192.168.1.20 ", 8443, attributes, gatewayId))
    }
}
