package app.auri.android

import android.app.Application
import app.auri.syncplatform.SyncRuntime
import app.auri.syncplatform.SyncRuntimeHost

class AuriApplication : Application(), SyncRuntimeHost {
    lateinit var runtime: AuriRuntime
        private set

    /** 系统因为有网络把应用叫醒时，同步服务从这里拿运行时（Application.onCreate 一定先于服务运行）。 */
    override val syncRuntime: SyncRuntime get() = runtime.sync

    override fun onCreate() {
        super.onCreate()
        // 要在第一个 Activity 启动之前创建：生命周期跟踪器靠它登记回调
        runtime = AuriRuntime(this)
        runtime.sync.start() // 配对记录读一次；没配对时什么也不会发生（Ticket 21）
    }
}
