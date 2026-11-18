package app.auri.platform.service

import android.content.Context
import android.content.Intent
import app.auri.capture.adapter.Subscription
import java.util.concurrent.CopyOnWriteArrayList

internal object ForegroundPromotionEvents {
    private val handlers = CopyOnWriteArrayList<(Boolean) -> Unit>()
    fun subscribe(handler: (Boolean) -> Unit): Subscription {
        handlers += handler
        return Subscription { handlers -= handler }
    }
    fun promoted() = handlers.forEach { it(true) }
    fun failed() = handlers.forEach { it(false) }
}

/** A refused stop cannot tear down a startForegroundService request until promotion succeeds. */
internal class ForegroundStopGate(private val requestStop: () -> Unit, private val stopPromoted: () -> Unit) {
    private var started = false
    private var promoted = false
    private var deferred = false

    @Synchronized fun started() { started = true; promoted = false; deferred = false }
    @Synchronized fun failed() { started = false; promoted = false; deferred = false }
    @Synchronized fun needsEvents() = started || deferred

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        try {
            requestStop()
        } catch (e: IllegalStateException) {
            if (promoted) stopPromoted() else deferred = true
        } catch (e: SecurityException) {
            if (promoted) stopPromoted() else deferred = true
        }
    }

    @Synchronized
    fun promoted() {
        promoted = true
        if (deferred && !started) {
            deferred = false
            stopPromoted()
        }
    }
}

/**
 * [ForegroundServiceController] 的 Android 实现。
 *
 * “停”不直接 stopService，而是再发一条 startForegroundService（命令 [MicCaptureForegroundService.ACTION_STOP]）让服务自己停：
 * startForegroundService 之后、服务调用 startForeground 之前，系统不允许服务被拆掉，直接 stopService 抢在前面会让
 * 整个进程以 ForegroundServiceDidNotStartInTimeException 崩溃（logcat：“Bringing down service while still waiting for
 * start foreground”）。拒绝路径每次都撞这个时序：状态进入 ROUTING 就起服务，几十毫秒后系统说没有蓝牙输入、
 * 状态变成 BLOCKED 就要停（API 35 模拟器实测，见验收文档）。若系统拒收停止命令，等服务确认提升后才可直接停服务。
 */
class AndroidForegroundServiceController(private val context: Context) : ForegroundServiceController {
    private fun intent(action: String) = Intent(context, MicCaptureForegroundService::class.java).setAction(action)
    private val gate = ForegroundStopGate(
        requestStop = { context.startForegroundService(intent(MicCaptureForegroundService.ACTION_STOP)) },
        stopPromoted = { context.stopService(intent(MicCaptureForegroundService.ACTION_STOP)) },
    )
    private val failureHandlers = CopyOnWriteArrayList<() -> Unit>()
    private var promotionSubscription: Subscription? = null

    override fun onFailure(handler: () -> Unit): Subscription {
        failureHandlers += handler
        return Subscription { failureHandlers -= handler }
    }

    @Synchronized
    override fun start(): Boolean {
        if (promotionSubscription == null) promotionSubscription = ForegroundPromotionEvents.subscribe(::onPromotion)
        gate.started()
        return try {
            context.startForegroundService(intent(MicCaptureForegroundService.ACTION_START))
            true
        } catch (e: IllegalStateException) {
            gate.failed()
            unsubscribeIfIdle()
            false
        } catch (e: SecurityException) {
            gate.failed()
            unsubscribeIfIdle()
            false
        }
    }

    @Synchronized
    override fun stop() {
        gate.stop()
        unsubscribeIfIdle()
    }

    @Synchronized
    private fun onPromotion(promoted: Boolean) {
        if (promoted) gate.promoted() else {
            gate.failed()
            failureHandlers.forEach { it() }
        }
        unsubscribeIfIdle()
    }

    private fun unsubscribeIfIdle() {
        if (!gate.needsEvents()) {
            promotionSubscription?.cancel()
            promotionSubscription = null
        }
    }
}
