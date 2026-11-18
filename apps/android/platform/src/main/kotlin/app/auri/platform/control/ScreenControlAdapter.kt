package app.auri.platform.control

import app.auri.capture.adapter.ControlAdapter
import app.auri.capture.adapter.Subscription
import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.ControlSource
import app.auri.capture.contract.NormalizedControlEvent
import app.auri.capture.contract.PlatformEvent
import app.auri.capture.coordinator.Clock
import app.auri.capture.coordinator.IdGenerator
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * 控制适配器：本 Ticket 只有屏幕上的开始/停止按钮（耳机手势是 Ticket 17）。
 * 屏幕操作不经过 Headset Profile，所以 profile_id / profile_version 为 null；它是用户在屏幕上明确按下的，
 * 不是从耳机事件猜出来的（合同 NormalizedControlEvent 的跨字段规则：screen_button ⇒ source=screen_control）。
 * 命令只是被递送出去：该不该执行由协调器按状态机决定（比如 DISARMED 时按开始会被拒绝）。
 */
class ScreenControlAdapter(
    private val clock: Clock,
    private val ids: IdGenerator,
) : ControlAdapter {
    private val handlers = CopyOnWriteArrayList<(NormalizedControlEvent) -> Unit>()
    private val sequence = AtomicLong(0)

    /** 屏幕按钮始终可用，不依赖任何耳机或权限。 */
    override fun enableUserControlMode(): Boolean = true

    override fun disableUserControlMode() = Unit

    override fun onCommand(handler: (NormalizedControlEvent) -> Unit): Subscription {
        handlers += handler
        return Subscription { handlers -= handler }
    }

    fun pressStart() = deliver(BusinessCommand.START)

    fun pressStop() = deliver(BusinessCommand.STOP)

    private fun deliver(command: BusinessCommand) {
        val event = NormalizedControlEvent(
            eventId = ids.newUuid(),
            source = ControlSource.SCREEN_CONTROL,
            platformEvent = PlatformEvent.SCREEN_BUTTON,
            command = command,
            monotonicNs = clock.monotonicNs(),
            sequence = sequence.getAndIncrement(),
            profileId = null,
            profileVersion = null,
            rawEventId = null,
        )
        handlers.forEach { it(event) }
    }
}
