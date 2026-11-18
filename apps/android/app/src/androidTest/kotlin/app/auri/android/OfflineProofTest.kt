package app.auri.android

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.auri.asr.TranscriptionState
import app.auri.asr.engine.SherpaOnnxEngineFactory
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ticket 14 第 4 项 / NFR-008：本地转录不需要网络。证据分三层，缺一层都不算证明：
 *  1. 已安装的包申请的网络类权限只有网关通信需要的那两个（INTERNET、ACCESS_NETWORK_STATE），没有别的网络/定位类权限；
 *  2. 网络**可用**（有默认网络）时整条转录 SUCCEEDED，该 UID 的收发字节数没有变化——转录本身不碰网络；
 *  3. 在系统飞行模式下整条转录照常 SUCCEEDED，且该 UID 的收发字节数没有变化。
 *
 * Ticket 21 之前，第 1、2 层是“包里没有 INTERNET，内核对该 UID 的网络 socket 一律拒绝”：权限不存在，字节数为 0 是必然的。
 * Ticket 21 让应用能把文字发到局域网网关，必须声明 INTERNET，那种整体证明不再成立（需求冲突的说明见
 * execution/acceptance/android-sync/README.md）。现在权限在、网络也在，零字节是**测出来**的，不是权限保证的；
 * 代码层面还有 NoNetworkCodeGuardTest（转录、录音、本地库的编译类里没有网络类）与 NoNetworkManifestTest（INTERNET 只来自 :syncplatform）。
 *
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
