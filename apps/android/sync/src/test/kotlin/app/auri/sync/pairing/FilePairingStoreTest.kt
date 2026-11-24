package app.auri.sync.pairing

import app.auri.sync.testing.SyncSamples
import app.auri.sync.testing.TlsFixtures
import java.io.File
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ---- 复评三 S5：这一段配对的世代随配对记录一起、一次原子写入落盘 ----

    private val legacyRecord = """{"version":1,"gateway_id":"${SyncSamples.GATEWAY_ID}","device_id":"${SyncSamples.DEVICE_ID}","pin_sha256":"${TlsFixtures.gatewayA.pin.hex}","addresses":[{"host":"192.168.1.20","port":8443}],"last_good_address":null,"token":"${SyncSamples.TOKEN_TEXT}","paired_at":"2026-11-15T02:05:00Z","needs_user":null}"""

    @Test
    fun `这一段配对的世代随记录落盘，换存储对象（重启）后读回，并且进入文字授权认的身份`() = runBlocking {
        FilePairingStore(file, box).save(gateway.copy(pairingEpoch = "epoch-7f3a"))

        val loaded = (FilePairingStore(file, box).load() as PairingState.Paired).gateway

        assertEquals("epoch-7f3a", loaded.pairingEpoch)
        assertEquals(GatewayIdentity(SyncSamples.GATEWAY_ID, TlsFixtures.gatewayA.pin.hex, "epoch-7f3a"), loaded.identity)
    }

    @Test
    fun `升级前的记录（没有世代这一项）读成 legacy 世代，其余字段照常；世代写得不合规的记录当作丢失`() = runBlocking {
        file.writeBytes(box.seal(legacyRecord.toByteArray()))
        val legacy = (FilePairingStore(file, box).load() as PairingState.Paired).gateway
        assertEquals(GatewayIdentity.LEGACY_EPOCH, legacy.pairingEpoch)
        assertEquals(SyncSamples.GATEWAY_ID, legacy.gatewayId)

        for (bad in listOf("", "has space", "x".repeat(65), "含中文")) {
            file.writeBytes(box.seal(legacyRecord.replace("\"needs_user\":null", "\"needs_user\":null,\"pairing_epoch\":\"$bad\"").toByteArray()))
            assertEquals("世代不合规：$bad", PairingState.Lost, FilePairingStore(file, box).load())
        }
    }

    @Test
    fun `update 改记录（记最近连上的地址、打标记）时世代原样带着走`() = runBlocking {
        val store = FilePairingStore(file, box)
        store.save(gateway.copy(pairingEpoch = "epoch-7f3a", needsUser = null))

        store.update { it.copy(lastGoodAddress = SyncSamples.NAS_ADDRESS) }

        assertEquals("epoch-7f3a", (store.load() as PairingState.Paired).gateway.pairingEpoch)
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
    fun `update 在同一把锁里读改写：变换看到的是此刻磁盘上的记录，写回的仍是密文`() = runBlocking {
        val store = FilePairingStore(file, box)
        store.save(gateway.copy(needsUser = null))

        val applied = store.update { current -> current.copy(needsUser = NeedsUser.AUTH_REJECTED) }

        assertTrue(applied)
        assertEquals(NeedsUser.AUTH_REJECTED, (FilePairingStore(file, box).load() as PairingState.Paired).gateway.needsUser)
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains(SyncSamples.TOKEN_TEXT))
    }

    @Test
    fun `网关太旧的标记（评审 R7）落盘，换存储对象（重启）后还在；清掉之后同样持久，配对本身（令牌、公钥、设备）一点没动`() = runBlocking {
        val store = FilePairingStore(file, box)
        store.save(gateway.copy(needsUser = null))

        assertTrue(store.markGatewayTooOld(gateway.copy(needsUser = null)))
        val flagged = (FilePairingStore(file, box).load() as PairingState.Paired).gateway
        assertEquals(NeedsUser.GATEWAY_TOO_OLD, flagged.needsUser)

        FilePairingStore(file, box).clearGatewayTooOld(flagged)

        val cleared = (FilePairingStore(file, box).load() as PairingState.Paired).gateway
        assertNull(cleared.needsUser)
        assertEquals(gateway.copy(needsUser = null), cleared)
    }

    @Test
    fun `打“网关太旧”的标记只在同一份配对、没有更重的连接问题时才写：公钥不符不被覆盖，途中重新配对了就不写并告知`() = runBlocking {
        val store = FilePairingStore(file, box)
        store.save(gateway) // 带着 KEY_MISMATCH
        assertTrue("还是这份配对，只是不去改它", store.markGatewayTooOld(gateway))
        assertEquals(NeedsUser.KEY_MISMATCH, (store.load() as PairingState.Paired).gateway.needsUser)

        val repaired = gateway.copy(needsUser = null, pairedAt = gateway.pairedAt.plusSeconds(60))
        store.save(repaired)
        assertFalse("旧配对的结论不能落到新配对上", store.markGatewayTooOld(gateway))
        assertNull((store.load() as PairingState.Paired).gateway.needsUser)

        store.save(repaired.copy(needsUser = NeedsUser.AUTH_REJECTED))
        store.clearGatewayTooOld(repaired)
        assertEquals("清标记只清“网关太旧”这一个", NeedsUser.AUTH_REJECTED, (store.load() as PairingState.Paired).gateway.needsUser)
    }

    @Test
    fun `update 的变换返回 null 就什么都不写；没配对或记录已丢失时更不会凭空造出一条`() = runBlocking {
        val store = FilePairingStore(file, box)
        assertFalse("没配对", store.update { it })
        assertFalse(file.exists())

        store.save(gateway)
        val before = file.readBytes()
        assertFalse(store.update { null })
        assertTrue("没改就不能重写文件", before.contentEquals(file.readBytes()))

        file.writeBytes("garbage".toByteArray())
        assertFalse("记录已丢失", store.update { it.copy(needsUser = null) })
        assertEquals(PairingState.Lost, store.load())
    }

    @Test
    fun `update 与 save 互斥：重新配对写入的新记录不会被晚到的旧观察覆盖`() = runBlocking {
        val store = FilePairingStore(file, box)
        val old = SyncSamples.pairedGateway(lastGoodAddress = null)
        store.save(old)
        val fresh = SyncSamples.pairedGateway(pairedAt = java.time.Instant.parse("2026-11-18T09:00:00Z"))

        // 引擎在旧记录上观察到问题；就在它回来更新之前，用户重新配对，写入了新记录
        store.save(fresh)
        val applied = store.update { current -> if (current.pairedAt == old.pairedAt) current.copy(needsUser = NeedsUser.KEY_MISMATCH) else null }

        assertFalse("变换必须看到新记录，所以不能再套用旧观察", applied)
        assertEquals(fresh, (store.load() as PairingState.Paired).gateway)
    }

    @Test
    fun `update 持锁期间 save 必须等着：读改写不会被夹在中间的重新配对覆盖`() = runBlocking {
        val store = FilePairingStore(file, box)
        store.save(SyncSamples.pairedGateway())
        val fresh = SyncSamples.pairedGateway(pairedAt = java.time.Instant.parse("2026-11-18T09:00:00Z"))
        val inside = CompletableDeferred<Unit>()

        val updating = async(Dispatchers.IO) {
            store.update { current ->
                inside.complete(Unit)
                Thread.sleep(300) // 变换还在进行时，用户重新配对了
                current.copy(needsUser = NeedsUser.KEY_MISMATCH)
            }
        }
        inside.await()
        store.save(fresh)
        updating.await()

        assertEquals("save 排在 update 之后：最终留下的是新记录", fresh, (store.load() as PairingState.Paired).gateway)
    }

    // ---- 复评 R5：在“配对着的仍是这一份”的前提下做事，判断与动作在同一把锁里 ----

    @Test
    fun `whileCurrent 只在配对着的仍是这一份时执行：换了一份、取消了、记录读不出来、从没配对，都不执行也不报错`() = runBlocking {
        val store = FilePairingStore(file, box)
        val first = SyncSamples.pairedGateway(lastGoodAddress = null)
        val second = SyncSamples.pairedGateway(pairedAt = java.time.Instant.parse("2026-11-18T09:00:00Z")) // 重新配对：新的配对时刻
        var ran = 0

        assertNull("从没配对", store.whileCurrent(first) { ran++ })
        store.save(first)
        assertEquals("就是这一份：执行，并交回它的结果", "做了", store.whileCurrent(first) { ran++; "做了" })
        store.save(second)
        assertNull("用户重新配对了：第一份不再是当前的", store.whileCurrent(first) { ran++ })
        assertEquals("新的这一份是当前的", "做了", store.whileCurrent(second) { ran++; "做了" })
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertNull("记录读不出来（丢失）：不能当成还是这一份", store.whileCurrent(second) { ran++ })
        store.clear()
        assertNull("取消了配对", store.whileCurrent(second) { ran++ })

        assertEquals("只有“就是这一份”的两次执行了", 2, ran)
    }

    @Test
    fun `whileCurrent 持锁期间 save 必须等着：判断与动作之间不会被夹进重新配对`() = runBlocking {
        val store = FilePairingStore(file, box)
        val first = SyncSamples.pairedGateway()
        store.save(first)
        val second = SyncSamples.pairedGateway(pairedAt = java.time.Instant.parse("2026-11-18T09:00:00Z"))
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()

        val working = async(Dispatchers.IO) {
            store.whileCurrent(first) {
                events += "动作开始"
                inside.complete(Unit)
                release.await() // 动作还在进行时，用户重新配对了
                events += "动作结束"
            }
        }
        inside.await()
        val saving = async(Dispatchers.IO) {
            store.save(second)
            events += "新配对已写入"
        }
        kotlinx.coroutines.delay(300) // 给 save 足够的机会插队
        assertEquals("save 必须等着：$events", listOf("动作开始"), events.toList())
        release.complete(Unit)
        working.await()
        saving.await()

        assertEquals(listOf("动作开始", "动作结束", "新配对已写入"), events.toList())
        assertEquals(second, (store.load() as PairingState.Paired).gateway)
    }

    @Test
    fun `toString 不含令牌、指纹和地址`() {
        val text = gateway.toString()
        for (secret in listOf(SyncSamples.TOKEN_TEXT, TlsFixtures.gatewayA.pin.hex, "192.168.1.20", "study-pc.local")) assertFalse(text.contains(secret))
    }
}
