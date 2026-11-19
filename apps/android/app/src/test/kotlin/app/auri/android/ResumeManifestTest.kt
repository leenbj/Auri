package app.auri.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进程被系统杀掉之后靠 JobScheduler 把应用叫醒（Ticket 21 评审 F7）。合并后的清单必须让它真的能工作：
 * 没有 RECEIVE_BOOT_COMPLETED，跨重启保留的叫醒在登记时就被系统拒绝；服务没有 BIND_JOB_SERVICE，系统根本不会启动它；
 * 服务如果对外导出，别的应用就能随意触发同步。这三条在真机上才会出错（而且是静默地不工作），所以在清单层面守住。
 */
class ResumeManifestTest {
    private val service = "app.auri.syncplatform.resume.SyncResumeService"

    @Test
    fun `合并后的清单声明 RECEIVE_BOOT_COMPLETED：跨重启保留的叫醒需要它`() {
        assertTrue("没有它，setPersisted(true) 的任务在登记时被拒绝", "android.permission.RECEIVE_BOOT_COMPLETED" in MergedManifest.permissions())
    }

    @Test
    fun `叫醒服务只允许系统绑定，不对外导出`() {
        val declared = MergedManifest.elements("service").filter { it.getAttribute("android:name") == service }

        assertEquals("合并后的清单里应当恰好有一个 $service", 1, declared.size)
        assertEquals("android.permission.BIND_JOB_SERVICE", declared.single().getAttribute("android:permission"))
        assertEquals("false", declared.single().getAttribute("android:exported"))
    }
}
