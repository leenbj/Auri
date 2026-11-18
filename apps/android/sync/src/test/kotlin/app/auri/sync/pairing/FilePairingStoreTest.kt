package app.auri.sync.pairing

import app.auri.sync.testing.SyncSamples
import app.auri.sync.testing.TlsFixtures
import java.io.File
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 配对记录（含设备令牌）落盘：只以密文存在、重启后还在、被改动就当作丢失（要求重新配对）。
 * “令牌不在明文文件里”靠直接去读磁盘上的字节来证明，不是看接口。
 */
class FilePairingStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun key(seed: Int) = SecretKeySpec(ByteArray(32) { (seed + it).toByte() }, "AES")
    private val box = AesGcmSecretBox({ key(1) }, "auri.pairing.v1")
    private val file by lazy { File(tmp.root, "pairing.bin") }
    private val gateway = SyncSamples.pairedGateway(lastGoodAddress = SyncSamples.LAN_ADDRESS, needsUser = NeedsUser.KEY_MISMATCH)

    @Test
    fun `存下的配对记录在换一个存储对象（模拟进程重启）后原样读回，令牌可用`() = runBlocking {
        FilePairingStore(file, box).save(gateway)

        val loaded = (FilePairingStore(file, box).load() as PairingState.Paired).gateway

        assertEquals(gateway, loaded)
        assertEquals("Bearer ${SyncSamples.TOKEN_TEXT}", loaded.token.bearer())
        assertEquals(TlsFixtures.gatewayA.pin, loaded.pin)
        assertEquals(listOf(SyncSamples.NAS_ADDRESS, SyncSamples.LAN_ADDRESS), loaded.addresses)
        assertEquals(NeedsUser.KEY_MISMATCH, loaded.needsUser)
    }

    @Test
    fun `磁盘上的文件里找不到令牌、指纹、标识、主机名（只有密文）`() = runBlocking {
        FilePairingStore(file, box).save(gateway)
        val onDisk = file.readBytes()
        val text = String(onDisk, Charsets.ISO_8859_1)

        for (secret in listOf(SyncSamples.TOKEN_TEXT, TlsFixtures.gatewayA.pin.hex, SyncSamples.GATEWAY_ID, SyncSamples.DEVICE_ID, "study-pc.local", "192.168.1.20", "token")) {
            assertFalse("文件里不该出现 $secret", text.contains(secret))
        }
        // 目录里也不能留下任何别的文件（比如明文备份）
        assertEquals(listOf("pairing.bin"), file.parentFile.list()!!.toList())
    }

    @Test
    fun `没有文件是没配对，文件被改动、截断、清空或是乱码是丢失（要求重新配对）`() = runBlocking {
        val store = FilePairingStore(file, box)
        assertEquals(PairingState.NotPaired, store.load())

        store.save(gateway)
        val original = file.readBytes()
        for (broken in listOf(
            original.copyOf().also { it[original.size / 2] = (it[original.size / 2].toInt() xor 1).toByte() },
            original.copyOf(original.size - 1),
            ByteArray(0),
            "this is not a pairing file".toByteArray(),
        )) {
            file.writeBytes(broken)
            assertEquals(PairingState.Lost, FilePairingStore(file, box).load())
        }
    }

    @Test
    fun `换了密钥（比如备份恢复到新手机，Keystore 密钥不跟过去）读不出来，是丢失而不是崩溃`() = runBlocking {
        FilePairingStore(file, box).save(gateway)

        assertEquals(PairingState.Lost, FilePairingStore(file, AesGcmSecretBox({ key(9) }, "auri.pairing.v1")).load())
    }

    @Test
    fun `解得开但内容不合格的记录（令牌太短、指纹不是十六进制、缺字段）一律是丢失，不会带着坏数据继续用`() = runBlocking {
        val good = """{"version":1,"gateway_id":"${SyncSamples.GATEWAY_ID}","device_id":"${SyncSamples.DEVICE_ID}","pin_sha256":"${TlsFixtures.gatewayA.pin.hex}","addresses":[{"host":"192.168.1.20","port":8443}],"last_good_address":null,"token":"${SyncSamples.TOKEN_TEXT}","paired_at":"2026-11-15T02:05:00Z","needs_user":null}"""
        file.writeBytes(box.seal(good.toByteArray()))
        assertTrue("基线必须是可读的", FilePairingStore(file, box).load() is PairingState.Paired)

        for (bad in listOf(
            good.replace(SyncSamples.TOKEN_TEXT, "short"),
            good.replace(TlsFixtures.gatewayA.pin.hex, "zz".repeat(32)),
            good.replace("\"version\":1", "\"version\":2"),
            good.replace("\"gateway_id\":\"${SyncSamples.GATEWAY_ID}\",", ""),
            good.replace("\"port\":8443", "\"port\":0"),
            good.replace("\"needs_user\":null", "\"needs_user\":\"SOMETHING_ELSE\""),
            good.replace("\"addresses\":[{\"host\":\"192.168.1.20\",\"port\":8443}]", "\"addresses\":[]"),
            "not json",
        )) {
            file.writeBytes(box.seal(bad.toByteArray()))
            assertEquals("应当当作丢失：$bad", PairingState.Lost, FilePairingStore(file, box).load())
        }
    }

    @Test
    fun `保存是替换：新的取代旧的；清除之后是没配对；对不存在的文件清除不报错`() = runBlocking {
        val store = FilePairingStore(file, box)
        store.save(gateway)
        store.save(gateway.copy(needsUser = null, lastGoodAddress = null))

        assertEquals(null, (store.load() as PairingState.Paired).gateway.needsUser)

        store.clear()
        assertEquals(PairingState.NotPaired, store.load())
        assertFalse(file.exists())
        store.clear()
    }

    @Test
    fun `上次保存中途崩溃留下的临时文件不影响读取，下次保存会把它清掉`() = runBlocking {
        val store = FilePairingStore(file, box)
        store.save(gateway)
        val leftover = File(tmp.root, "pairing.bin.tmp").also { it.writeBytes("half-written garbage".toByteArray()) }

        assertTrue("临时文件不能影响读取", store.load() is PairingState.Paired)
        store.save(gateway)

        assertFalse(leftover.exists())
        assertEquals(listOf("pairing.bin"), tmp.root.list()!!.toList())
    }

    @Test
    fun `toString 不含令牌、指纹和地址`() {
        val text = gateway.toString()
        for (secret in listOf(SyncSamples.TOKEN_TEXT, TlsFixtures.gatewayA.pin.hex, "192.168.1.20", "study-pc.local")) assertFalse(text.contains(secret))
    }
}
