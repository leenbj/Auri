package app.auri.capture.adapter

import app.auri.capture.contract.AsrCapabilities
import app.auri.capture.contract.AsrTaskHandle
import app.auri.capture.contract.CaptureEligibility
import app.auri.capture.contract.CaptureHandle
import app.auri.capture.contract.ErrorCode
import app.auri.capture.contract.NormalizedControlEvent
import app.auri.capture.contract.PreparedInput
import app.auri.capture.contract.RouteChangeEvent

// 三端共用的适配器边界（PRD 第 17.1 节）。协调器只通过这里的接口碰系统：
//   ControlAdapter       控制命令从哪来（本 Ticket 只有屏幕按钮；耳机在 Ticket 17）
//   CaptureAdapter       权限与生命周期、向系统请求蓝牙输入、报告系统观测到的真实路由、递送音频帧
//   RecordingStore       路由确认之后的音频才允许写进来（见 RecordingStore）
//   TranscriptionAdapter 把封存好的音频交给转录引擎
//
// 与 PRD 17.1 的伪接口相比有一处刻意的不同：没有 startVerifiedCapture / stopAndFinalize。
// 闸门、Quarantine、generation 与 route_epoch 的校验是最容易出竞态的部分，放进平台适配器就只能靠真机测；
// 所以它们都在纯 Kotlin 的协调器里（JVM 测试能用假适配器逼出每一种竞态），平台适配器只做三件事：
// 向系统请求输入、报告系统观测、递送帧。iOS / 鸿蒙照这个边界实现即可，不需要各自再写一遍闸门。

/** 取消订阅的句柄。 */
fun interface Subscription {
    fun cancel()
}

/** 16 位小端 PCM 的格式。适配器必须按请求的格式递送帧，做不到就拒绝（inputUnavailable），不得悄悄换格式。 */
data class PcmFormat(val sampleRateHz: Int, val channels: Int = 1) {
    init {
        require(sampleRateHz in 8000..192000) { "采样率必须在 8000..192000" }
        require(channels in 1..2) { "声道数必须是 1 或 2" }
    }
}

/** 一批音频帧。[routeEpoch] 是采集这批帧时有效的路由证据的 epoch；不属于任何已观测路由的帧，适配器不得递送。 */
class AudioFrames(val routeEpoch: Long, val samples: ShortArray)

interface ControlAdapter {
    /** 用户开启控制模式。平台提供不了任何控制来源时返回 false（协调器据此拒绝并给出 CONTROL_NOT_AVAILABLE）。 */
    fun enableUserControlMode(): Boolean

    fun disableUserControlMode()

    /** 订阅归一化后的业务命令。可在任意线程回调，协调器会把它们串行化。 */
    fun onCommand(handler: (NormalizedControlEvent) -> Unit): Subscription
}

/** 一次 Capture Session 的请求参数。[generation] 与 [captureSessionId] 由协调器分配，适配器原样带回。 */
class InputRequest(
    val captureSessionId: String,
    val operationId: String,
    val generation: Long,
    val format: PcmFormat,
)

/**
 * 适配器向协调器报告的通道，每个 Capture Session 一个。可在任意线程调用、不会阻塞；
 * 协调器会核对它所属的会话是否还是当前会话，旧会话的回调一律被拒绝。
 */
interface CaptureSink {
    /** 系统观测到的一次输入路由变化（Route Evidence 的唯一来源）。epoch 必须严格递增。 */
    fun routeChanged(event: RouteChangeEvent)

    /** 递送音频帧。路由确认之前的帧只会进内存里的 Quarantine。 */
    fun frames(frames: AudioFrames)

    /** 拿不到蓝牙输入（没有耳机、系统拒绝、输入被拿走）。永远不会因此改用手机麦克风。 */
    fun inputUnavailable(reason: ErrorCode)

    /** 来电、音频焦点被抢等系统中断。 */
    fun interrupted()
}

interface CaptureAdapter {
    /** 当前能否开始录音：权限与应用生命周期是两层（前台服务另算）。未知必须说 unknown，不得当成允许。 */
    fun inspectEligibility(): CaptureEligibility

    /**
     * 向系统请求蓝牙输入并开始递送帧。只请求蓝牙耳机；没有可用的蓝牙输入时不得采集任何东西，
     * 通过 [CaptureSink.inputUnavailable] 报告原因并返回 rejected_by_system，不存在“改用手机麦克风”的分支。
     * 返回值只说明请求发出去了；实际来源是否验证由 [CaptureSink.routeChanged] 的观测说了算。
     */
    suspend fun prepareBluetoothInput(request: InputRequest, sink: CaptureSink): PreparedInput

    /** 立即停止这次操作的采集并释放资源（幂等；对已取消或不存在的操作无副作用）。 */
    fun release(operationId: String)

    /** 麦克风权限在运行期被撤销（与会话无关，任何状态下都可能发生）。 */
    fun onPermissionRevoked(handler: () -> Unit): Subscription
}

/**
 * 录音存储。**只有路由确认之后协调器才会调用 [begin]**：确认之前的音频只存在于协调器的内存 Quarantine 里，
 * 确认失败就整体丢弃，没有任何一个字节碰到这里。每个 Record 一个音频文件，文件名与 Record 共用同一个标识。
 */
interface RecordingStore {
    fun begin(recordId: String, handle: CaptureHandle, format: PcmFormat): RecordingWriter
}

interface RecordingWriter {
    /** 追加已确认路由的音频。[handle] 必须是 begin 时那个、闸门开着；不匹配就拒绝（IllegalStateException）。写失败抛 IOException。 */
    fun append(handle: CaptureHandle, samples: ShortArray)

    /** 封存：落盘、校验、算哈希，返回封存后的音频。之后不能再写。是挂起函数，让调用方在封存期间仍能处理别的消息。 */
    suspend fun seal(): SealedAudio

    /** 放弃：删除已写的部分（幂等：封存失败后协调器会再调一次来清理残留）。 */
    fun abort()
}

/** 封存后的音频。[location] 是存储自己的不透明位置（Android 上是私有目录里的文件路径）。 */
data class SealedAudio(
    val location: String,
    val sha256: String,
    val sizeBytes: Long,
    val sampleCount: Long,
    val format: PcmFormat,
) {
    val durationMs: Long get() = sampleCount * 1000L / (format.sampleRateHz.toLong() * format.channels)
}

class TranscriptionRequest(val recordId: String, val audio: SealedAudio)

/** 转录的四种结果。NoSpeech 不是失败，也不会产生 Revision。 */
sealed interface TranscriptionOutcome {
    /** 转出了文字。[text] 是数据，不是指令；日志里不得出现它。 */
    class Text(
        val text: String,
        val language: String,
        val confidence: Double?,
    ) : TranscriptionOutcome

    data object NoSpeech : TranscriptionOutcome

    /** 模型没装好（缺失或校验没过），等装好再转；录音保留。 */
    class WaitingModel(val code: ErrorCode) : TranscriptionOutcome

    /**
     * 转录失败。[retryable] 是引擎自己的判断（合同 error.retryable）：只有它说“可以重试”才会按有界的退避重试，
     * 默认不可重试，与原来的行为一致。
     */
    class Failed(val code: ErrorCode?, val retryable: Boolean = false) : TranscriptionOutcome
}

/** 一次转录任务的结果：任务句柄与实际用的引擎、模型（Revision 要写明用了哪个模型）一起返回。 */
class TranscriptionResult(
    val job: AsrTaskHandle,
    val capabilities: AsrCapabilities,
    val outcome: TranscriptionOutcome,
    /** 实际使用的本地模型代；没有模型管理器时为 null。 */
    val modelGeneration: Long? = null,
)

interface TranscriptionAdapter {
    /** 当前转录引擎声明的能力（不加载模型）。 */
    suspend fun capabilities(): AsrCapabilities

    /** 转录一个已封存的录音文件。不能通过重新打开麦克风代替（accepts_recorded_file 恒为 true）。 */
    suspend fun transcribe(request: TranscriptionRequest): TranscriptionResult
}
