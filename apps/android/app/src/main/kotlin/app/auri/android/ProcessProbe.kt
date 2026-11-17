package app.auri.android

import android.net.TrafficStats
import android.os.Build
import android.os.Process
import android.os.SystemClock
import app.auri.asr.engine.SherpaOnnxEngineFactory
import java.io.File

/** 运行报告需要的、依赖 Android 或原生库的读数。取不到的一律是 null，不用 0 冒充。 */
interface ProcessProbe {
    /** /proc/self/status 的 VmHWM（驻留内存历史峰值，千字节）。 */
    fun peakRssKb(): Long?

    /** /proc/self/status 的 VmRSS（当前驻留内存，千字节）。 */
    fun currentRssKb(): Long?

    fun msSinceProcessStart(): Long

    /** 本应用 UID 到目前为止收发的字节总数。 */
    fun uidNetworkBytes(): Long?

    fun device(): ReportDevice

    fun engine(): ReportEngine
}

object ProcStatus {
    private val line = Regex("""^\S+:\s+(\d+)\s*kB\s*$""")

    /** 从 /proc/<pid>/status 的文本读 `字段:<空白>N kB`；字段名必须完整匹配（VmHWM 不会读成 VmPeak）。 */
    fun kb(status: String, field: String): Long? {
        val text = status.lineSequence().firstOrNull { it.startsWith("$field:") } ?: return null
        return line.matchEntire(text)?.groupValues?.get(1)?.toLongOrNull()
    }
}

class SystemProcessProbe(private val numThreads: Int = SherpaOnnxEngineFactory.DEFAULT_THREADS) : ProcessProbe {
    private fun status(): String? = runCatching { File("/proc/self/status").readText() }.getOrNull()

    override fun peakRssKb() = status()?.let { ProcStatus.kb(it, "VmHWM") }

    override fun currentRssKb() = status()?.let { ProcStatus.kb(it, "VmRSS") }

    override fun msSinceProcessStart() = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()

    override fun uidNetworkBytes(): Long? {
        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        val unsupported = TrafficStats.UNSUPPORTED.toLong()
        return if (rx == unsupported || tx == unsupported) null else rx + tx
    }

    override fun device() = ReportDevice(
        model = Build.MODEL,
        hardware = Build.HARDWARE,
        fingerprint = Build.FINGERPRINT,
        sdkInt = Build.VERSION.SDK_INT,
        abis = Build.SUPPORTED_ABIS.toList(),
    )

    // 读引擎版本会加载 JNI 库；报告在转录结束之后才取它，不影响冷启动读数
    override fun engine(): ReportEngine {
        val info = SherpaOnnxEngineFactory.runtimeInfo()
        return ReportEngine(info.version, info.onnxruntimeVersion, info.gitSha1, numThreads)
    }
}
