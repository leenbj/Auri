package app.auri.platform.control

import app.auri.capture.adapter.ControlAdapter
import app.auri.capture.adapter.Subscription
import app.auri.capture.contract.NormalizedControlEvent
import app.auri.capture.control.ControlConfig
import app.auri.capture.control.ControlProfile
import app.auri.capture.control.HeadsetControlStatus
import app.auri.capture.control.HeadsetEventNormalizer
import app.auri.capture.control.NormalizeResult
import app.auri.capture.control.RawMediaInput
import app.auri.capture.coordinator.IdGenerator
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 耳机控制适配器（PRD FR-HS-001 / FR-HS-004）：只在控制模式开启期间占着系统的媒体按键通道，把按键交给
 * [HeadsetEventNormalizer] 归一化与去抖，再递送合同里的 normalized_control_event。
 *
 *  - 控制模式关闭 = 通道已释放：没有人收听，关闭之后（或上一次激活留下的）迟到回调通过“激活代数”校验丢掉；
 *  - 框架调用（[MediaButtonSource.activate] / [MediaButtonSource.deactivate]）随时可能抛异常：包住、照样清理、
 *    照样到达终态，绝不向协调器抛；激活失败如实报 [HeadsetControlStatus.UNAVAILABLE]，不装作开着；
 *  - [profile] 每次按键都实时读取：校准向导可以临时换成“只观察”的映射；
 *  - 日志只写状态与原因，不写设备名、标识或任何内容。
 */
class HeadsetControlAdapter(
    private val source: MediaButtonSource,
    ids: IdGenerator,
    private val profile: () -> ControlProfile,
    config: ControlConfig = ControlConfig(),
    private val log: (String) -> Unit = {},
) : ControlAdapter {
    private val normalizer = HeadsetEventNormalizer(ids, config)
    private val handlers = CopyOnWriteArrayList<(NormalizedControlEvent) -> Unit>()
    private val statusFlow = MutableStateFlow(HeadsetControlStatus.OFF)
    private val lock = Any()
    private var active = false
    private var activation = 0L

    val status: StateFlow<HeadsetControlStatus> = statusFlow

    override fun enableUserControlMode(): Boolean {
        synchronized(lock) {
            if (active) return true
            activation += 1
            val mine = activation
            val acquired = try {
                source.activate { raw -> onRaw(mine, raw) }
            } catch (e: Exception) {
                log("headset_control_activate_failed reason=${e.javaClass.simpleName}")
                false
            }
            if (acquired) {
                active = true
            } else {
                // 半成品也要释放；激活失败之后收到的任何回调都属于一个不会被承认的代数
                activation += 1
                release()
            }
            statusFlow.value = if (acquired) HeadsetControlStatus.ACTIVE else HeadsetControlStatus.UNAVAILABLE
            return acquired
        }
    }

    override fun disableUserControlMode() {
        synchronized(lock) {
            statusFlow.value = HeadsetControlStatus.OFF
            if (!active) return
            // 先让所有在途的旧回调失效，再去碰框架：释放抛异常也不会让按键继续往下送
            active = false
            activation += 1
            normalizer.resetGestureState()
            release()
        }
    }

    override fun onCommand(handler: (NormalizedControlEvent) -> Unit): Subscription {
        handlers += handler
        return Subscription { handlers -= handler }
    }

    private fun release() {
        try {
            source.deactivate()
        } catch (e: Exception) {
            log("headset_control_release_failed reason=${e.javaClass.simpleName}")
        }
    }

    private fun onRaw(token: Long, raw: RawMediaInput) {
        val event = synchronized(lock) {
            if (!active || token != activation) return
            when (val result = normalizer.normalize(raw, profile())) {
                is NormalizeResult.Emit -> result.event
                is NormalizeResult.Drop -> {
                    log("headset_control_dropped reason=${result.reason}")
                    return
                }
            }
        }
        handlers.forEach {
            try {
                it(event)
            } catch (e: Exception) {
                log("headset_control_subscriber_failed reason=${e.javaClass.simpleName}")
            }
        }
    }
}
