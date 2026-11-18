package app.auri.platform.service

import app.auri.capture.contract.CaptureState
import app.auri.capture.coordinator.CaptureSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** 麦克风类前台服务的开关。Android 上的实现是 [AndroidForegroundServiceController]。 */
interface ForegroundServiceController {
    /** 启动（幂等）。系统不允许（例如后台不得启动、缺权限）时返回 false。 */
    fun start(): Boolean

    /** 停止（幂等）。 */
    fun stop()
}

/**
 * 前台服务只在采集真正在进行时开着：ROUTING / RECORDING / FINALIZING 开，其余（DISARMED、ARMED、BLOCKED）关。
 * 麦克风运行时权限与麦克风前台服务是两层，开始前的资格检查两层都查了；这里只管服务的生命周期跟着采集状态走，
 * 所以应用被杀、状态回到 DISARMED 之后不会留下一个空转的服务（服务本身也不会被系统自动重启，见服务类）。
 */
val FOREGROUND_SERVICE_STATES: Set<CaptureState> = setOf(CaptureState.ROUTING, CaptureState.RECORDING, CaptureState.FINALIZING)

/**
 * 让服务跟着 [snapshots] 的采集状态走。服务起不来时调用 [onStartRefused]：没有前台服务，录音一进后台就会被系统静音，
 * 继续录只会录下一段静音，所以调用方应当取消这次采集。
 */
fun CoroutineScope.bindForegroundService(
    snapshots: Flow<CaptureSnapshot>,
    controller: ForegroundServiceController,
    onStartRefused: () -> Unit,
): Job = launch {
    snapshots.map { it.state in FOREGROUND_SERVICE_STATES }.distinctUntilChanged().collect { needed ->
        if (!needed) {
            controller.stop()
        } else if (!controller.start()) {
            onStartRefused()
        }
    }
}
