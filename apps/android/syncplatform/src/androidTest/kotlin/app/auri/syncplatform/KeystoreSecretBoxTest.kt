package app.auri.syncplatform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.auri.sync.pairing.AesGcmSecretBox
import app.auri.sync.pairing.FilePairingStore
import app.auri.sync.pairing.PairingState
import app.auri.sync.testing.SyncSamples
import app.auri.sync.tls.SpkiPin
import app.auri.syncplatform.keystore.KeystoreKeys
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 21 第 1 项：设备令牌放在 Android Keystore 支撑的带认证加密里，不以明文出现在任何私有文件里。
 * 这里用真正的 AndroidKeyStore（模拟器上是软件实现，真机是 TEE/StrongBox；密钥是否在安全硬件里属于真机证据）。
 */
class KeystoreSecretBoxTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alias = "auri.test.${UUID.randomUUID()}"
    private val box = AesGcmSecretBox({ KeystoreKeys.aesKey(alias) }, "pairing")
    private val secret = "令牌-token-0123456789abcdef0123456789abcdef".toByteArray(Charsets.UTF_8)
    private val pairingFile = File(context.filesDir, "keystore-test-${UUID.randomUUID()}.sealed")

    // 不用 SyncSamples 的默认指纹：它来自 JDK 的 keytool 现场生成的证书，Android 上没有 keytool
    private val pin = SpkiPin.parse("1f".repeat(32))!!

    @After
    fun cleanUp() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        pairingFile.delete()
        File(pairingFile.path + ".tmp").delete()
    }

    @Test
    fun 封好的数据能原样打开() {
        assertArrayEquals(secret, box.open(box.seal(secret)))
    }

    @Test
    fun 密钥不可导出_应用拿不到密钥的字节() {
        assertNull(KeystoreKeys.aesKey(alias).encoded)
    }

    @Test
    fun 同一个别名取到的是同一把密钥_重启之后还能打开() {
        val sealed = box.seal(secret)

        val again = AesGcmSecretBox({ KeystoreKeys.aesKey(alias) }, "pairing")

        assertArrayEquals(secret, again.open(sealed))
    }

    @Test
    fun 每次封出来的密文都不同_IV_是随机的() {
        assertFalse(box.seal(secret).contentEquals(box.seal(secret)))
    }

    @Test
    fun 密文里没有明文() {
        val sealed = box.seal(secret)
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains(String(secret, Charsets.ISO_8859_1)))
    }

    @Test
    fun 改动任何一个字节都打不开_截断也打不开() {
        val sealed = box.seal(secret)
        for (index in sealed.indices) {
            val tampered = sealed.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            assertNull("改动第 $index 个字节仍然打开了", box.open(tampered))
        }
        assertNull(box.open(sealed.copyOf(sealed.size - 1)))
        assertNull(box.open(ByteArray(0)))
    }

    @Test
    fun 别的用途的密文冒充打不开() {
        val other = AesGcmSecretBox({ KeystoreKeys.aesKey(alias) }, "something-else").seal(secret)
        assertNull(box.open(other))
    }

    @Test
    fun 密钥丢失之后打不开_返回_null_而不是抛异常() {
        val sealed = box.seal(secret)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)

        assertNull(box.open(sealed)) // 之后取到的是新生成的另一把密钥：认证失败
    }

    @Test
    fun 配对记录落盘后_应用的私有目录里任何地方都没有令牌的明文() = runBlocking {
        val store = FilePairingStore(pairingFile, box)
        val gateway = SyncSamples.pairedGateway(pin = pin)

        store.save(gateway)

        assertTrue(pairingFile.length() > 0)
        val token = gateway.token.reveal().toByteArray(Charsets.UTF_8)
        val leaked = context.dataDir.walkTopDown().filter { it.isFile && it.canRead() }.filter { contains(it.readBytes(), token) }.map { it.path }.toList()
        assertEquals("令牌明文出现在这些文件里", emptyList<String>(), leaked)
        // 密文能还原整条记录
        assertEquals(PairingState.Paired(gateway), store.load())
        // 公钥指纹也是密文的一部分：文件里没有它的明文，改不了它
        val pinText = gateway.pin.hex.toByteArray(Charsets.UTF_8)
        assertEquals(64, pinText.size)
        assertFalse(contains(pairingFile.readBytes(), pinText))
    }

    @Test
    fun 密钥丢失之后配对记录读成_Lost_要求重新配对() = runBlocking {
        val store = FilePairingStore(pairingFile, box)
        store.save(SyncSamples.pairedGateway(pin = pin))
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)

        assertEquals(PairingState.Lost, store.load())
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        outer@ for (start in 0..haystack.size - needle.size) {
            for (offset in needle.indices) if (haystack[start + offset] != needle[offset]) continue@outer
            return true
        }
        return false
    }
}
