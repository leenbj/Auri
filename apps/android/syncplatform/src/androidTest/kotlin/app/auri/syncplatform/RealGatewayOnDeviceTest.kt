package app.auri.syncplatform

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.auri.capture.testing.Samples
import app.auri.sync.gateway.GatewayClient
import app.auri.sync.gateway.GatewayResult
import app.auri.sync.gateway.StoredResponse
import app.auri.sync.gateway.SubmissionBuilder
import app.auri.sync.http.PinnedHttpsClient
import app.auri.sync.pairing.PairingOutcome
import app.auri.sync.pairing.PairingPayload
import app.auri.sync.pairing.PairingState
import app.auri.sync.pairing.PayloadParse
import app.auri.sync.status.SyncHeadline
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Ticket 21 的设备端真实网关测试（证据等级 E2：模拟器，不是真机、不是真的 Wi-Fi）：
 * 模拟器上的真实装配（Keystore 加密的配对记录、SQLite 任务表、系统网络回调、固定公钥的 HTTPS）对着宿主机上跑的
 * 真实 Python 网关说话——模拟器用 10.0.2.2 访问宿主机的回环地址。
 *
 * 宿主机一侧由 run_emulator_acceptance.sh 起网关、生成配对载荷并作为参数传进来；
 * 入库的结果由宿主机的 real_gateway_host.py 在测试结束后回头查网关数据库核对（恰好一条设备、恰好一条这一版、正文哈希一致）。
 * 不带参数直接跑会失败并说明缺什么，不会悄悄跳过。
 */
class RealGatewayOnDeviceTest {
    private val application = ApplicationProvider.getApplicationContext<Context>().applicationContext as Application
    private val arguments = InstrumentationRegistry.getArguments()
    private val databaseName = "real-gateway-${UUID.randomUUID()}.db"
    private val pairingFile = File(application.filesDir, "pairing.sealed")
    private var scope: CoroutineScope? = null
    private var runtime: SyncRuntime? = null

    @Before
    fun startClean() {
        pairingFile.delete() // 上一次留下的配对记录会让这一次的结果不可比
    }

    @After
    fun cleanUp() {
        scope?.cancel()
        runtime?.library?.close()
        application.deleteDatabase(databaseName)
        pairingFile.delete()
    }

    private fun argument(name: String): String =
        arguments.getString(name) ?: error("缺少仪器参数 $name：这个测试要对着宿主机上的真网关跑，参数由 run_emulator_acceptance.sh 提供")

    @Test
    fun 对着真网关配对_入库_重复提交幂等_令牌既不在日志里也不在明文文件里() = runBlocking {
        // 载荷是 JSON，带引号和空格，经 adb shell 传不稳妥：宿主机传 base64
        val payloadText = String(java.util.Base64.getDecoder().decode(argument("auri.gateway.payload_b64")), Charsets.UTF_8)
        val recordId = argument("auri.gateway.record_id")
        val text = argument("auri.gateway.text")
        val parsed = PairingPayload.parse(payloadText)
        assertTrue("宿主机给的配对载荷设备端必须认", parsed is PayloadParse.Valid)
        val payload = (parsed as PayloadParse.Valid).payload

        val library = SyncStorage.library(application, databaseName)
        val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = ownScope
        val sync = SyncRuntime.create(application, library, ownScope)
        runtime = sync
        sync.start()

        // 1. 公钥不符：把载荷里的指纹改掉一位。必须拒绝连接，配对码不能发出去（宿主机事后核对网关上没有登记任何设备）
        val flipped = (if (payload.publicKeySha256.hex[0] == '0') "1" else "0") + payload.publicKeySha256.hex.substring(1)
        val tampered = PairingPayload.parse(payloadText.replace(payload.publicKeySha256.hex, flipped))
        assertTrue(tampered is PayloadParse.Valid)
        assertEquals("指纹被改过的载荷：真网关的证书对不上，必须拒绝", PairingOutcome.KeyMismatch, sync.pair((tampered as PayloadParse.Valid).payload))
        assertTrue("公钥不符不能留下配对记录", sync.pairing.load() !is PairingState.Paired)

        // 2. 正确的载荷：配对成功
        val outcome = sync.pair(payload)
        assertTrue("配对结果：$outcome", outcome is PairingOutcome.Paired)
        val paired = (outcome as PairingOutcome.Paired).gateway

        // 3. 配对完成不等于同意：用户没授权“文字可以进网关”之前，文字只留在手机上（评审 B1）；明确授权之后同步循环才把它送到网关
        library.addRecord(Samples.entry(1).copy(recordId = recordId))
        val revision = library.publishRevision(Samples.draft(recordId, text))
        sync.syncNow()
        withTimeout(10_000) { sync.status.first { it.headline == SyncHeadline.HELD_BY_POLICY } }
        sync.setGatewayTextGranted(true)
        val status = withTimeout(60_000) { sync.status.first { it.headline == SyncHeadline.UP_TO_DATE && it.counts.stored == 1 } }
        assertEquals(0, status.counts.needsUser)

        // 4. 重复提交同一版：网关回 duplicate（幂等），设备端不会弄出第二条
        // 与 SyncRuntime.create 同一条路：仪器测试包没有 versionName，deviceInfo 把空版本补成 "unknown"，直接用空串会被 SubmissionBuilder 拒收
        val version = deviceInfo(application.packageManager.getPackageInfo(application.packageName, 0).versionName.orEmpty()).appVersion
        val submission = checkNotNull(SubmissionBuilder(version).build(checkNotNull(library.get(recordId)), revision))
        val replay = GatewayClient(PinnedHttpsClient(paired.pin)).putRevision(paired.addresses.first(), paired.token, submission)
        assertTrue("重复提交的结果：$replay", replay is GatewayResult.Success)
        assertTrue("同一版第二次提交，网关必须说这是重复", (replay as GatewayResult.Success<StoredResponse>).value.duplicate)

        // 5. 令牌、配对码、公钥指纹、网关地址：不在明文文件里，不在 logcat 里
        //    （logcat 里不查裸的 10.0.2.2：那是模拟器自己的网关地址，系统日志里本来就有；查“地址:端口”）
        val address = payload.addresses.first()
        val shared = mapOf("设备令牌" to paired.token.reveal(), "配对码" to payload.pairingCode, "公钥指纹" to payload.publicKeySha256.hex)
        assertTrue("配对记录文件应当存在", pairingFile.isFile)
        val onDisk = String(pairingFile.readBytes(), Charsets.ISO_8859_1)
        val log = shell("logcat -d")
        assertTrue("同步层应当打过日志（否则下面“日志里没有机密”什么也没证明）", log.contains("AuriSync"))
        for ((what, secret) in shared + ("网关地址" to address.host)) {
            assertFalse("$what 不能以明文出现在配对记录文件里", onDisk.contains(secret))
        }
        for ((what, secret) in shared + ("网关地址和端口" to "${address.host}:${address.port}")) {
            assertFalse("$what 不能出现在 logcat 里", log.contains(secret))
        }
        assertNotNull(sync.pairing.load())
        Unit
    }
}
