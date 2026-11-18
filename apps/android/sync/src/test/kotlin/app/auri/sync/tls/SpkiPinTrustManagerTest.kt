package app.auri.sync.tls

import app.auri.sync.testing.TlsFixtures
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 信任管理器本身的行为：只认叶证书的公钥指纹，三个入口（含 JSSE 实际调用的 socket / engine 版本）一视同仁。 */
class SpkiPinTrustManagerTest {
    private val genuine = TlsFixtures.gatewayA.certificate
    private val stranger = TlsFixtures.gatewayB.certificate
    private val pin = TlsFixtures.gatewayA.pin

    private fun expectMismatch(block: () -> Unit) {
        try {
            block()
            fail("应当拒绝")
        } catch (_: GatewayKeyMismatchException) {
            // 期望的拒绝
        }
    }

    @Test
    fun `叶证书指纹相符就通过，三个入口都一样`() {
        val tm = SpkiPinTrustManager(pin)
        tm.checkServerTrusted(arrayOf(genuine), "ECDHE_ECDSA")
        tm.checkServerTrusted(arrayOf(genuine), "ECDHE_ECDSA", null as java.net.Socket?)
        tm.checkServerTrusted(arrayOf(genuine), "ECDHE_ECDSA", null as javax.net.ssl.SSLEngine?)
        assertFalse(tm.mismatchObserved)
    }

    @Test
    fun `叶证书指纹不符就拒绝，三个入口都一样，并记下这次连接遇到过不符`() {
        for (call in listOf<(SpkiPinTrustManager) -> Unit>(
            { it.checkServerTrusted(arrayOf(stranger), "x") },
            { it.checkServerTrusted(arrayOf(stranger), "x", null as java.net.Socket?) },
            { it.checkServerTrusted(arrayOf(stranger), "x", null as javax.net.ssl.SSLEngine?) },
        )) {
            val tm = SpkiPinTrustManager(pin)
            expectMismatch { call(tm) }
            assertTrue(tm.mismatchObserved)
        }
    }

    @Test
    fun `只认叶证书：真证书藏在链的后面没有用，空链与 null 链都拒绝`() {
        val buried: Array<X509Certificate> = arrayOf(stranger, genuine)
        expectMismatch { SpkiPinTrustManager(pin).checkServerTrusted(buried, "x") }
        expectMismatch { SpkiPinTrustManager(pin).checkServerTrusted(emptyArray(), "x") }
        expectMismatch { SpkiPinTrustManager(pin).checkServerTrusted(null, "x") }
    }

    @Test
    fun `证书续签后指纹不变，所以旧指纹仍然通过；换了密钥就不通过`() {
        val renewed = TlsFixtures.renewed(TlsFixtures.gatewayA).certificate
        SpkiPinTrustManager(pin).checkServerTrusted(arrayOf(renewed), "x")
        expectMismatch { SpkiPinTrustManager(TlsFixtures.gatewayB.pin).checkServerTrusted(arrayOf(renewed), "x") }
    }

    @Test
    fun `客户端证书一律不接受，也没有任何可信的颁发者`() {
        val tm = SpkiPinTrustManager(pin)
        for (call in listOf<() -> Unit>(
            { tm.checkClientTrusted(arrayOf(genuine), "x") },
            { tm.checkClientTrusted(arrayOf(genuine), "x", null as java.net.Socket?) },
            { tm.checkClientTrusted(arrayOf(genuine), "x", null as javax.net.ssl.SSLEngine?) },
        )) {
            try {
                call()
                fail("不该接受客户端证书")
            } catch (_: CertificateException) {
                // 期望
            }
        }
        assertEquals(0, tm.acceptedIssuers.size)
    }

    @Test
    fun `指纹的解析与比对：十六进制必须是 64 位小写，比对的是公钥而不是整张证书`() {
        assertEquals(TlsFixtures.gatewayA.pin, SpkiPin.parse(pin.hex))
        assertNull(SpkiPin.parse(pin.hex.uppercase()))
        assertNull(SpkiPin.parse(pin.hex.dropLast(1)))
        assertNull(SpkiPin.parse(pin.hex + "0"))
        assertNull(SpkiPin.parse(""))
        assertTrue(pin.matches(genuine))
        assertFalse(pin.matches(stranger))
        assertNotNull(pin.toString())
        assertFalse("toString 不应泄露完整指纹", pin.toString().contains(pin.hex))
        assertArrayEquals(pin.hex.toByteArray(), SpkiPin.of(genuine).hex.toByteArray())
    }
}
