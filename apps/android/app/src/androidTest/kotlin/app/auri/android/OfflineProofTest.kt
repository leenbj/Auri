package app.auri.android

import android.content.pm.PackageManager
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.auri.asr.TranscriptionState
import app.auri.asr.engine.SherpaOnnxEngineFactory
import java.io.File
import java.net.InetAddress
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ticket 14 第 4 项 / NFR-008：本地转录不需要网络。
 *
 * 内核层面的证明（“已安装的包没有 INTERNET”“内核拒绝该 UID 建任何网络 socket”）**不可能再成立**，不是放宽：Ticket 20（下载模型）与
 * Ticket 21（把文字发到局域网网关）都要 INTERNET，Android 按应用（UID）而不是按模块授予它，不能限在某个模块或进程里
 * （产品负责人已决定：不做不带 INTERNET 的构建变体，那个问题留给 Ticket 30；见 execution/acceptance/android-sync/README.md 第 8 节）。
 * 剩下的保证是**代码守卫加实测零字节**，所以这里的测试要尽量强：
 *  1. 静态（JVM，不在这个文件里）：网络 API 只出现在登记过的模块里（`:platform` 的 NetworkIsolationTest、`:app` 的 NoNetworkCodeGuardTest），
 *     INTERNET 只来自登记过的模块清单（NoNetworkManifestTest）；
 *  2. 已安装的包申请的网络类权限恰好是 INTERNET 与 ACCESS_NETWORK_STATE（同步层监听网络恢复要用），没有别的网络/定位类权限（第一个测试）；
 *  3. 飞行模式下系统没有任何出路：有 INTERNET 的应用，socket 能建出来（对照：它不再被内核拒绝），但连出去的尝试在系统层失败（第二个测试）；
 *  4. 网络**可用**（有默认网络）时整条转录 SUCCEEDED，且该 UID 的收发字节数没有增加——零字节是**测出来**的，不是权限保证的（第三个测试）；
 *  5. 在系统飞行模式下整条转录照常 SUCCEEDED，且该 UID 的收发字节数没有变化（第四个测试）。
 *
 * 失去的是什么：Ticket 14 那种“系统层面证明这个进程根本不能联网”的证据没有了，剩下的是代码扫描加行为实测。这一点写进了验收文档，由产品负责人知情。
 * 模拟器上的飞行模式只是“系统没有默认网络”的 E2 证据；真机飞行模式实测是 E3，见 README 的验收步骤（NOT_RUN）。
 */
@RunWith(AndroidJUnit4::class)
class OfflineProofTest {
    private fun requestedPermissions(): List<String> =
        DeviceEnv.target.packageManager
            .getPackageInfo(DeviceEnv.target.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toList()

    @Test
    fun theInstalledPackageRequestsOnlyTheGatewayLinkNetworkPermissions() {
        // 网络与定位类权限里，只许有网关通信要的两个；多出任何一个（Wi-Fi 状态、定位、附近 Wi-Fi 设备……）都说明有东西悄悄带进了新的网络能力
        val networkRelated = setOf(
            "android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "android.permission.ACCESS_WIFI_STATE",
            "android.permission.CHANGE_WIFI_STATE", "android.permission.CHANGE_NETWORK_STATE", "android.permission.CHANGE_WIFI_MULTICAST_STATE",
            "android.permission.NEARBY_WIFI_DEVICES", "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
        )
        val requested = requestedPermissions().filter { it in networkRelated }.toSet()

        assertEquals(requestedPermissions().toString(), setOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"), requested)
    }

    @Test
    fun transcriptionSendsNoNetworkBytesEvenWhileTheNetworkIsAvailable() {
        DeviceEnv.requireInstalled("zh")
        // 前提：系统飞行模式关着、有默认网络。否则“零字节”可能只是因为没有网，说明不了转录本身不联网
        assertEquals("飞行模式必须是关的", "0", airplaneModeSetting())
        val network = activeDefaultNetworkLine()
        assertTrue("需要有默认网络才有意义：$network", network.isNotEmpty() && !network.endsWith("none"))

        val out = DeviceEnv.outDir("online-zh")
        val result = FileTranscription(SherpaOnnxEngineFactory(), SystemProcessProbe()).run(
            FileTranscription.Request(DeviceEnv.manifestJson, DeviceEnv.modelDir, DeviceEnv.sample("zh"), out),
        )

        assertEquals(TranscriptionState.SUCCEEDED, result.finalState)
        val report = Json.decodeFromString<RunReport>(File(out, "run-report.json").readText())
        assertNotNull("本机 TrafficStats 必须能读到该 UID 的字节数，读不到就不能证明", report.process.uidNetworkBytesDelta)
        assertEquals(0L, report.process.uidNetworkBytesDelta)
    }

    @Test
    fun inAirplaneModeASocketCanBeCreatedButFindsNoRouteOut() {
        val wasOn = airplaneModeSetting() == "1"
        try {
            DeviceEnv.shell("cmd connectivity airplane-mode enable")
            awaitAirplaneMode()
            // 有 INTERNET 时建网络 socket 不再被内核拒绝（Ticket 14 当时断言的正是相反的）；没有默认网络，连向保留的测试地址（RFC 5737）立刻失败
            val fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, 0)
            try {
                Os.connect(fd, InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1)), 80)
                fail("飞行模式下不应该连得出去")
            } catch (e: ErrnoException) {
                assertTrue("errno=${e.errno}（${e.message}）", e.errno in setOf(OsConstants.ENETUNREACH, OsConstants.EHOSTUNREACH))
            } finally {
                Os.close(fd)
            }
        } finally {
            if (!wasOn) DeviceEnv.shell("cmd connectivity airplane-mode disable")
        }
    }

    @Test
    fun transcriptionSucceedsInAirplaneModeWithoutAnyNetworkBytes() {
        DeviceEnv.requireInstalled("zh")
        val wasOn = airplaneModeSetting() == "1"
        try {
            DeviceEnv.shell("cmd connectivity airplane-mode enable")
            val evidence = awaitAirplaneMode()

            val out = DeviceEnv.outDir("airplane-zh")
            val result = FileTranscription(SherpaOnnxEngineFactory(), SystemProcessProbe()).run(
                FileTranscription.Request(DeviceEnv.manifestJson, DeviceEnv.modelDir, DeviceEnv.sample("zh"), out),
            )

            assertEquals(TranscriptionState.SUCCEEDED, result.finalState)
            val report = Json.decodeFromString<RunReport>(File(out, "run-report.json").readText())
            assertNotNull("本机 TrafficStats 必须能读到该 UID 的字节数，读不到就不能证明", report.process.uidNetworkBytesDelta)
            assertEquals(0L, report.process.uidNetworkBytesDelta)
            File(out, "airplane-evidence.json").writeText(Json.encodeToString(AirplaneEvidence.serializer(), evidence))
        } finally {
            if (!wasOn) DeviceEnv.shell("cmd connectivity airplane-mode disable")
        }
    }

    @Serializable
    data class AirplaneEvidence(
        val airplaneModeOn: String,
        val activeDefaultNetworkLine: String,
        val waitedMs: Long,
    )

    private fun airplaneModeSetting(): String = DeviceEnv.shell("settings get global airplane_mode_on").trim()

    private fun activeDefaultNetworkLine(): String =
        DeviceEnv.shell("dumpsys connectivity").lineSequence().firstOrNull { it.contains("Active default network:") }?.trim().orEmpty()

    /** 等到系统设置为 1 且没有默认网络；超时就失败，绝不在没进入飞行模式时继续往下测。 */
    private fun awaitAirplaneMode(timeoutMs: Long = 30_000): AirplaneEvidence {
        val start = System.nanoTime()
        while (true) {
            val waited = (System.nanoTime() - start) / 1_000_000
            val setting = airplaneModeSetting()
            val line = activeDefaultNetworkLine()
            if (setting == "1" && line.endsWith("none")) return AirplaneEvidence(setting, line, waited)
            if (waited > timeoutMs) fail("飞行模式没有生效：airplane_mode_on=$setting，$line")
            Thread.sleep(500)
        }
    }
}
