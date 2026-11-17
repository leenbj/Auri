package app.auri.android

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 一次“转录这个文件”的运行报告（run-report.json）：只有数字与版本，**没有转录正文**、没有路径。
 * 证据等级不写在文件里，由运行它的人按环境标注（模拟器 E2、真机 E3，见 execution/acceptance/asr-android/README.md）。
 * 取不到的读数是 null，不用 0 冒充。
 */
@Serializable
data class RunReport(
    @SerialName("report_version") val reportVersion: Int,
    @SerialName("final_state") val finalState: String,
    val audio: ReportAudio,
    val metrics: ReportMetrics,
    val process: ReportProcess,
    /** 第一次之后在同一个引擎上额外重复的推理耗时（毫秒）。 */
    @SerialName("warm_inference_ms") val warmInferenceMs: List<Long>,
    val engine: ReportEngine,
    val model: ReportModel,
    val device: ReportDevice,
) {
    companion object {
        const val VERSION = 1
    }
}

@Serializable
data class ReportAudio(
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("sample_rate_hz") val sampleRateHz: Int,
    val channels: Int,
)

/** 与 [app.auri.asr.TranscriptionMetrics] 一致：RTF = 推理时间 / 音频时长（NFR-006）。 */
@Serializable
data class ReportMetrics(
    @SerialName("verify_ms") val verifyMs: Long,
    @SerialName("engine_load_ms") val engineLoadMs: Long,
    @SerialName("resample_ms") val resampleMs: Long,
    @SerialName("inference_ms") val inferenceMs: Long,
    val rtf: Double?,
)

@Serializable
data class ReportProcess(
    /** 进程启动 → 第一次转录出结果（含类加载、模型校验、引擎加载）。 */
    @SerialName("start_to_first_result_ms") val startToFirstResultMs: Long,
    /** /proc/self/status 的 VmHWM（进程驻留内存历史峰值，千字节），运行前后各读一次。 */
    @SerialName("vm_hwm_kb_before") val vmHwmKbBefore: Long?,
    @SerialName("vm_hwm_kb_after") val vmHwmKbAfter: Long?,
    @SerialName("vm_rss_kb_after") val vmRssKbAfter: Long?,
    /** 本应用 UID 在运行期间收发的字节数增量；离线转录必须是 0。 */
    @SerialName("uid_network_bytes_delta") val uidNetworkBytesDelta: Long?,
)

@Serializable
data class ReportEngine(
    val version: String,
    @SerialName("onnxruntime_version") val onnxruntimeVersion: String,
    @SerialName("git_sha1") val gitSha1: String,
    @SerialName("num_threads") val numThreads: Int,
) {
    companion object {
        /** 原生库没能加载，读不到任何版本信息。 */
        val UNAVAILABLE = ReportEngine("unavailable", "unavailable", "unavailable", 0)
    }
}

@Serializable
data class ReportModel(
    @SerialName("model_id") val modelId: String,
    @SerialName("model_version") val modelVersion: String,
)

/** 设备字段取自 android.os.Build 原值；是不是模拟器由读报告的人看 hardware（模拟器是 ranchu）判断。 */
@Serializable
data class ReportDevice(
    val model: String,
    val hardware: String,
    val fingerprint: String,
    @SerialName("sdk_int") val sdkInt: Int,
    val abis: List<String>,
)
