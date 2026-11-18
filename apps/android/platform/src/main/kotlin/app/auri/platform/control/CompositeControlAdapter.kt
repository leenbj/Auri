package app.auri.platform.control

import app.auri.capture.adapter.ControlAdapter
import app.auri.capture.adapter.Subscription
import app.auri.capture.contract.NormalizedControlEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * 协调器只接一个控制适配器：把屏幕按钮与耳机合成一个。
 *  - 开启成功的条件是“至少一个来源可用”：耳机控制开不起来时屏幕按钮仍然可用，手动兜底不会因耳机失败而丢；
 *  - 两个来源的命令在这里统一重新编号（“进入串行调度器的单调递增序号”），所有订阅者看到的是同一个事件；
 *    编号、旁听、递送在同一把锁里完成，所以两个来源同时来事件时，旁听者与命令订阅者的先后顺序一致、也与编号一致；
 *  - 任何一个来源的框架调用抛异常都被包住，不影响另一个来源；
 *  - [observe] 的旁听者在所有命令订阅者之前、在递送线程上同步收到同一个事件：校准向导要在协调器处理这个命令之前登记它，
 *    这样协调器的决定（异步产出）到达时向导一定已经见过对应的事件。旁听者只看不改，抛异常也不影响命令递送。
 */
class CompositeControlAdapter(
    private val screen: ControlAdapter,
    private val headset: ControlAdapter,
    private val log: (String) -> Unit = {},
) : ControlAdapter {
    private val handlers = CopyOnWriteArrayList<(NormalizedControlEvent) -> Unit>()
    private val observers = CopyOnWriteArrayList<(NormalizedControlEvent) -> Unit>()
    private val sequence = AtomicLong(0)
    private val deliveryLock = Any()

    init {
        screen.onCommand(::forward)
        headset.onCommand(::forward)
    }

    override fun enableUserControlMode(): Boolean {
        val headsetOk = guarded("headset_enable", false) { headset.enableUserControlMode() }
        val screenOk = guarded("screen_enable", false) { screen.enableUserControlMode() }
        return headsetOk || screenOk
    }

    override fun disableUserControlMode() {
        guarded("headset_disable", Unit) { headset.disableUserControlMode() }
        guarded("screen_disable", Unit) { screen.disableUserControlMode() }
    }

    override fun onCommand(handler: (NormalizedControlEvent) -> Unit): Subscription {
        handlers += handler
        return Subscription { handlers -= handler }
    }

    fun observe(observer: (NormalizedControlEvent) -> Unit): Subscription {
        observers += observer
        return Subscription { observers -= observer }
    }

    private fun forward(event: NormalizedControlEvent) {
        // 编号、旁听、递送是一个不可分的整体：两个来源同时来事件时，旁听者与命令订阅者必须看到同一个先后顺序
        synchronized(deliveryLock) {
            val numbered = event.copy(sequence = sequence.getAndIncrement())
            observers.forEach {
                try {
                    it(numbered)
                } catch (e: Exception) {
                    log("control_observer_failed reason=${e.javaClass.simpleName}")
                }
            }
            handlers.forEach {
                try {
                    it(numbered)
                } catch (e: Exception) {
                    log("control_subscriber_failed reason=${e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun <T> guarded(what: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        log("control_${what}_failed reason=${e.javaClass.simpleName}")
        fallback
    }
}
