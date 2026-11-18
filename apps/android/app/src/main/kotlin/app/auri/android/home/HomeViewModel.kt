package app.auri.android.home

import androidx.lifecycle.ViewModel
import app.auri.capture.contract.CaptureState
import app.auri.capture.contract.ErrorCode
import app.auri.capture.contract.EndReason
import app.auri.capture.contract.ObservedInputRouteType
import app.auri.capture.coordinator.CaptureCoordinator
import app.auri.capture.coordinator.CaptureSnapshot
import app.auri.capture.library.RecordEntry
import app.auri.capture.library.RecordLibrary
import app.auri.capture.library.TranscriptionStatus
import app.auri.capture.pipeline.PipelineNotice
import app.auri.platform.audio.HeadsetPresence
import app.auri.platform.audio.HeadsetStatus
import app.auri.platform.control.ScreenControlAdapter
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/*
 * ===================================================================================================================
 *  首页（HomeScreen）的数据与操作：另一个人会在这上面做真正的界面，界面只需要读 HomeUiState、调 HomeViewModel 的方法。
 * ===================================================================================================================
 *
 * 读：`HomeViewModel.state: StateFlow<HomeUiState>`（唯一的可观察入口）。
 *
 *   HomeUiState
 *   ├─ headset: HeadsetFact          事实 1：耳机连接了吗（connected、name）。只表示系统里有蓝牙音频设备，**不是**输入来源的证据。
 *   ├─ input: InputFact              事实 2：现在实际在用哪个输入（source、deviceName）。只来自系统对这次录音的观测；
 *   │                                没有观测到（没在录、系统还没说清）时 source 为 null。
 *   ├─ capture: CaptureFact          事实 3：采集状态（state: DISARMED/ARMED/ROUTING/RECORDING/FINALIZING/BLOCKED）
 *   │                                与受阻原因（blockedReason，只有 BLOCKED 时非 null，用它告诉用户“为什么没录”）。
 *   ├─ startEnabled / stopEnabled    两个按钮此刻能不能按（由采集状态决定，界面不要自己再判断）。
 *   ├─ records: List<RecordItem>     已录下的 Record，最新的在前；每条有时长与文字状态（TextState：转录中 / 已转出文字 /
 *   │                                没有语音 / 等待模型 / 失败）。已转出文字的带模型名与版本。
 *   └─ notice: Notice?               需要告诉用户的一次性消息（录音没能保存等）；界面显示后调用 dismissNotice()。
 *   durationLimit: DurationLimitStop? 最近一次因时长上限自动停止的原因和上限秒数；下一次开始时清除。
 *
 *   这三个事实互相独立，界面必须分开显示，不能合并成一句“已连接并录音中”：
 *   耳机已连接 ≠ 正在用耳机麦克风（FR-REC-001）。例如：耳机已连接、实际输入却是手机麦克风（路由被系统切走）；
 *   或者耳机没连接、采集状态为 BLOCKED（原因 INPUT_DEVICE_MISSING，什么都没录）。
 *
 * 写（都不阻塞，可在主线程调）：
 *   - start()          用户按“开始”。需要先拿到运行时权限（麦克风、蓝牙连接；Android 13+ 还有通知）：权限请求在界面层做，
 *                      权限没给也可以调 start()，协调器会拒绝并把原因放进 capture.blockedReason。
 *   - stop()           用户按“停止”（录音中、或准备阶段取消）。
 *   - dismissNotice()  关掉当前消息。
 *
 * 规则：转录文字是数据，界面只显示它，不要把它当指令执行或写进日志（SEC-004）。
 * 不要在界面里用手机麦克风做任何事；这里没有“改用手机麦克风”的选项，也不该有。
 * ===================================================================================================================
 */

data class HomeUiState(
    val headset: HeadsetFact,
    val input: InputFact,
    val capture: CaptureFact,
    val startEnabled: Boolean,
    val stopEnabled: Boolean,
    val records: List<RecordItem>,
    val notice: Notice?,
    val durationLimit: DurationLimitStop?,
)

data class DurationLimitStop(val reason: EndReason, val limitSeconds: Double)

/** 事实 1：耳机是否连接。 */
data class HeadsetFact(val connected: Boolean, val name: String?)

/** 事实 2：实际输入来源。[source] 为 null 表示系统没有观测到（没在录，或系统还没说清）。 */
data class InputFact(val source: ObservedInputRouteType?, val deviceName: String?)

/** 事实 3：采集状态。 */
data class CaptureFact(val state: CaptureState, val blockedReason: ErrorCode?)

data class RecordItem(
    val recordId: String,
    val startedAt: Instant,
    val durationMs: Long,
    val text: TextState,
)

/** 一个 Record 的文字处于什么状态。 */
sealed interface TextState {
    /** 音频已保存，正在或即将转录。 */
    data object Transcribing : TextState

    /** 已转出文字。[modelId]/[modelVersion] 是产出这段文字的模型。 */
    data class Done(val text: String, val modelId: String?, val modelVersion: String?) : TextState

    /** 有音频、没有语音：没有文字，也不会编造文字。 */
    data object NoSpeech : TextState

    /** 转录模型没装好，装好后会自动转录；音频保留。 */
    data class WaitingModel(val reason: ErrorCode?) : TextState

    /** 转录失败，音频保留。 */
    data class Failed(val reason: ErrorCode?) : TextState
}

sealed interface Notice {
    /** 录音封存失败，这次录音没能保存。 */
    data class RecordingLost(val reason: ErrorCode) : Notice

    /** 音频已保存，但 Record 没能记进库。 */
    data object RecordNotSaved : Notice
}

/** 界面对采集的全部操作：开始、停止，以及可观察的采集快照。生产实现是 [CoordinatorControls]，测试里用假的。 */
interface CaptureControls {
    val snapshot: StateFlow<CaptureSnapshot>

    fun start()

    fun stop()
}

/** 屏幕按钮 → 控制适配器 → 协调器；手动采集不改变耳机控制模式。 */
class CoordinatorControls(
    private val coordinator: CaptureCoordinator,
    private val screen: ScreenControlAdapter,
) : CaptureControls {
    override val snapshot: StateFlow<CaptureSnapshot> get() = coordinator.snapshot

    override fun start() = screen.pressStart()

    override fun stop() = screen.pressStop()
}

class HomeViewModel(
    private val controls: CaptureControls,
    headset: HeadsetPresence,
    library: RecordLibrary,
    notices: Flow<PipelineNotice>,
    private val scope: CoroutineScope,
) : ViewModel() {
    private val notice = MutableStateFlow<Notice?>(null)

    val state: StateFlow<HomeUiState> = combine(
        controls.snapshot,
        headset.status,
        library.observe(),
        notice,
    ) { snapshot, headsetStatus, records, currentNotice -> buildState(snapshot, headsetStatus, records, currentNotice) }.stateIn(scope, SharingStarted.Eagerly, buildState(controls.snapshot.value, headset.status.value, emptyList(), null))

    init {
        scope.launch {
            notices.collect { n ->
                notice.value = when (n) {
                    is PipelineNotice.RecordingLost -> Notice.RecordingLost(n.reason)
                    is PipelineNotice.RecordNotSaved -> Notice.RecordNotSaved
                }
            }
        }
    }

    fun start() = controls.start()

    fun stop() = controls.stop()

    fun dismissNotice() {
        notice.value = null
    }

    override fun onCleared() {
        scope.cancel()
    }

    private fun buildState(snapshot: CaptureSnapshot, headset: HeadsetStatus, records: List<RecordEntry>, notice: Notice?) = HomeUiState(
        headset = HeadsetFact(headset.connected, headset.name),
        input = InputFact(snapshot.actualInput?.routeType, snapshot.actualInput?.displayName),
        capture = CaptureFact(snapshot.state, snapshot.blockedReason),
        startEnabled = snapshot.state in START_STATES,
        stopEnabled = snapshot.state in STOP_STATES,
        records = records.map(::toItem),
        notice = notice,
        durationLimit = snapshot.durationLimitMs?.let { DurationLimitStop(EndReason.DURATION_LIMIT, it / 1000.0) },
    )

    private fun toItem(entry: RecordEntry) = RecordItem(
        recordId = entry.recordId,
        startedAt = entry.startedAt,
        durationMs = entry.captureDurationMs,
        text = when (entry.transcription) {
            TranscriptionStatus.PENDING -> TextState.Transcribing
            TranscriptionStatus.DONE -> {
                val latest = entry.revisions.last()
                TextState.Done(latest.text, latest.modelId, latest.modelVersion)
            }
            TranscriptionStatus.NO_SPEECH -> TextState.NoSpeech
            TranscriptionStatus.WAITING_MODEL -> TextState.WaitingModel(entry.failureCode)
            TranscriptionStatus.FAILED -> TextState.Failed(entry.failureCode)
        },
    )

    private companion object {
        /** 没在录也没在封存的状态都能开始；BLOCKED 时再按“开始”就是重试。 */
        val START_STATES = setOf(CaptureState.DISARMED, CaptureState.ARMED, CaptureState.BLOCKED)

        /** 准备阶段（取消）与录音中都能停止；封存中不能（已经在停了）。 */
        val STOP_STATES = setOf(CaptureState.ROUTING, CaptureState.RECORDING)
    }
}
