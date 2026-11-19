package app.auri.sync.pairing

import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 令牌的“平台安全存储”要满足：带认证的加密（改一个字节就打不开）、每次封装的随机数不同、换密钥或换用途就打不开。
 * 这里用的是与 Android 上完全相同的封装代码，只是密钥来自内存而不是 Keystore（Keystore 一侧另有仪器测试）。
 */
class AesGcmSecretBoxTest {
    private fun key(seed: Int) = SecretKeySpec(ByteArray(32) { (seed + it).toByte() }, "AES")

    private val box = AesGcmSecretBox({ key(1) }, "auri.pairing.v1")
    private val plain = "令牌 fixture-device-token-not-a-real-secret-0001".toByteArray()

    @Test
    fun `封装再打开得到原文`() {
        assertArrayEquals(plain, box.open(box.seal(plain)))
        assertArrayEquals(ByteArray(0), box.open(box.seal(ByteArray(0))))
    }

    @Test
    fun `同一段明文每次封装的结果都不同，密文里找不到明文`() {
        val first = box.seal(plain)
        val second = box.seal(plain)

        assertNotEquals("每次的随机数必须不同，否则相同明文会产生相同密文", first.toList(), second.toList())
        assertFalse("密文里不能出现明文", String(first, Charsets.ISO_8859_1).contains("fixture-device-token"))
    }

    @Test
    fun `改动任意一个字节都打不开（带认证），截断也打不开`() {
        val sealed = box.seal(plain)

        for (i in sealed.indices) {
            val tampered = sealed.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertNull("第 $i 个字节被改动后不该能打开", box.open(tampered))
        }
        for (length in 0 until sealed.size) {
            assertNull("截断到 $length 字节不该能打开", box.open(sealed.copyOf(length)))
        }
        assertNull("尾部多出内容也不行", box.open(sealed + byteArrayOf(0)))
    }

    @Test
    fun `换密钥或换用途都打不开`() {
        val sealed = box.seal(plain)

        assertNull(AesGcmSecretBox({ key(2) }, "auri.pairing.v1").open(sealed))
        assertNull(AesGcmSecretBox({ key(1) }, "auri.other.v1").open(sealed))
        assertEquals("同密钥同用途才打得开", plain.toList(), AesGcmSecretBox({ key(1) }, "auri.pairing.v1").open(sealed)?.toList())
    }

    @Test
    fun `密钥取不到（Keystore 里的密钥丢了）时打开返回 null，不抛异常`() {
        val sealed = box.seal(plain)
        val lost = AesGcmSecretBox({ throw java.security.UnrecoverableKeyException("key permanently invalidated") }, "auri.pairing.v1")

        assertNull(lost.open(sealed))
    }
}
