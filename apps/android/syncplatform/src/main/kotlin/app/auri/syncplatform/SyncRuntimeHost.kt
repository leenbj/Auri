package app.auri.syncplatform

/**
 * 装配了 [SyncRuntime] 的应用对象实现它。系统把应用叫醒（JobScheduler）时，Application.onCreate 先于服务运行，
 * [app.auri.syncplatform.resume.SyncResumeService] 通过它拿到运行时——不用全局单例，宿主不对就直接不重排。
 */
interface SyncRuntimeHost {
    val syncRuntime: SyncRuntime
}
