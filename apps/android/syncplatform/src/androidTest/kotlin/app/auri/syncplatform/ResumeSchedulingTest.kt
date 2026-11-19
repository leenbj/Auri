package app.auri.syncplatform

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.auri.syncplatform.resume.JobSchedulerResume
import app.auri.syncplatform.resume.SyncResumeService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 进程被系统杀掉之后的叫醒是向**真实系统**登记的 JobScheduler 任务：登记是否被系统接受（权限、服务声明）、
 * 登记出来的任务是不是要求网络并跨重启保留，只有在设备上才看得到。JVM 单测只能验证“什么时候该登记”（ResumePlannerTest）。
 *
 * 状态：NOT_RUN（模拟器已按要求停用以节省内存，当前机器上没有设备）。只编译过：`./gradlew :syncplatform:assembleDebugAndroidTest`。
 * 将来运行：`./gradlew :syncplatform:connectedDebugAndroidTest --tests '*ResumeSchedulingTest*'`。
 * 即使这些都通过，“杀进程、换网络之后真的被叫醒并把文字发出去”也还需要在真机上手工验证（见验收 README）。
 */
class ResumeSchedulingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val system = context.getSystemService(JobScheduler::class.java)
    private val resume = JobSchedulerResume(context)

    @Before
    fun clean() = system.cancel(JobSchedulerResume.JOB_ID)

    @After
    fun cleanAfter() = system.cancel(JobSchedulerResume.JOB_ID)

    @Suppress("DEPRECATION")
    @Test
    fun 登记的任务要求网络_跨重启保留_指向叫醒服务() {
        resume.ensureScheduled()

        val info = system.getPendingJob(JobSchedulerResume.JOB_ID)
        assertNotNull("系统接受了登记", info)
        assertTrue("跨重启保留", info!!.isPersisted)
        assertEquals("要求有网络", JobInfo.NETWORK_TYPE_ANY, info.networkType)
        assertEquals(ComponentName(context, SyncResumeService::class.java), info.service)
    }

    @Test
    fun 已经有一个就不再登记_不打断正在执行的那一个() {
        resume.ensureScheduled()
        val first = system.getPendingJob(JobSchedulerResume.JOB_ID)

        resume.ensureScheduled()

        assertEquals(1, system.allPendingJobs.count { it.id == JobSchedulerResume.JOB_ID })
        assertEquals(first, system.getPendingJob(JobSchedulerResume.JOB_ID))
    }

    @Test
    fun 取消之后系统里没有这个任务() {
        resume.ensureScheduled()

        resume.cancel()

        assertNull(system.getPendingJob(JobSchedulerResume.JOB_ID))
    }

    @Test
    fun 叫醒服务只允许系统绑定_不导出() {
        val info = context.packageManager.getServiceInfo(ComponentName(context, SyncResumeService::class.java), 0)

        assertEquals("android.permission.BIND_JOB_SERVICE", info.permission)
        assertEquals(false, info.exported)
    }
}
