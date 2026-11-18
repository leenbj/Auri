package app.auri.platform.system

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.os.Bundle
import app.auri.capture.contract.LifecycleState
import java.util.concurrent.atomic.AtomicInteger

/**
 * 应用此刻处于什么生命周期：有 Activity 在 started 状态 = 前台；设备锁屏 = 锁屏；否则后台。
 * 采集的资格检查用它判断能不能开始（后台不允许启动麦克风）。
 */
class AppLifecycleTracker(application: Application) : Application.ActivityLifecycleCallbacks {
    private val started = AtomicInteger(0)
    private val keyguard = application.getSystemService(KeyguardManager::class.java)

    init {
        application.registerActivityLifecycleCallbacks(this)
    }

    fun lifecycle(): LifecycleState = when {
        keyguard?.isKeyguardLocked == true -> LifecycleState.LOCKED
        started.get() > 0 -> LifecycleState.FOREGROUND
        else -> LifecycleState.BACKGROUND
    }

    override fun onActivityStarted(activity: Activity) {
        started.incrementAndGet()
    }

    override fun onActivityStopped(activity: Activity) {
        started.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
