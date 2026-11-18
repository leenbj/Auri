package app.auri.android

import android.app.Application

class AuriApplication : Application() {
    lateinit var runtime: AuriRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        // 要在第一个 Activity 启动之前创建：生命周期跟踪器靠它登记回调
        runtime = AuriRuntime(this)
    }
}
