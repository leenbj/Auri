package app.auri.android

import android.content.pm.PackageManager
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.auri.asr.TranscriptionState
import app.auri.asr.engine.SherpaOnnxEngineFactory
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ticket 14 第 4 项 / NFR-008：本地转录不需要网络。证据分三层，缺一层都不算证明：
 *  1. 已安装的包没有申请 INTERNET；
 *  2. 因此系统对该 UID 的任何网络 socket 一律“权限被拒”（EACCES/EPERM；同一测试里用 AF_UNIX 做正对照，证明 Os.socket 本身可用）；
 *  3. 在系统飞行模式下整条转录照常 SUCCEEDED，且该 UID 的收发字节数没有变化。
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
    fun theInstalledPackageRequestsNoInternetPermission() {
        assertFalse(requestedPermissions().toString(), requestedPermissions().contains("android.permission.INTERNET"))
    }

    @Test
    fun theKernelRefusesEveryNetworkSocketButStillAllowsLocalOnes() {
        // 缺 INTERNET 时系统拒绝建网络 socket；错误码因内核机制而异（API 35 模拟器实测是 EPERM，旧内核是 EACCES），
        // 两者都是“权限被拒”。EAFNOSUPPORT、ENETUNREACH 之类“没权限以外的原因”不能算数，所以不放宽到任意错误。
        val permissionDenied = setOf(OsConstants.EACCES, OsConstants.EPERM)
        for ((family, type) in listOf(OsConstants.AF_INET to OsConstants.SOCK_STREAM, OsConstants.AF_INET6 to OsConstants.SOCK_DGRAM)) {
            try {
                Os.close(Os.socket(family, type, 0))
                fail("family=$family type=$type 的网络 socket 不应该创建成功")
            } catch (e: ErrnoException) {
                assertTrue("family=$family：errno=${e.errno}（${e.message}）", e.errno in permissionDenied)
            }
        }
        // 正对照：本机 AF_UNIX socket 不属于网络，必须能建出来，否则上面的失败说明不了任何事
        Os.close(Os.socket(OsConstants.AF_UNIX, OsConstants.SOCK_STREAM, 0))
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
