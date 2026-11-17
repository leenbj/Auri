package app.auri.android

import android.content.Intent
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * debug 入口 TranscribeFileActivity 是产品负责人在真机上跑验收的那个入口，所以它本身也要被测到：
 * 额外参数能驱动完整转录，出错时只在界面上留下异常类型，不泄露路径或正文。
 */
@RunWith(AndroidJUnit4::class)
class TranscribeFileActivityTest {
    private fun launch(vararg extras: Pair<String, String>): Intent =
        Intent(DeviceEnv.target, TranscribeFileActivity::class.java).apply { extras.forEach { (k, v) -> putExtra(k, v) } }

    private fun awaitStatus(scenario: ActivityScenario<TranscribeFileActivity>, timeoutMs: Long = 60_000): String {
        val start = System.nanoTime()
        while (true) {
            var text = ""
            scenario.onActivity { activity ->
                text = (activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as TextView).text.toString()
            }
            if (!text.contains("运行中")) return text
            if ((System.nanoTime() - start) / 1_000_000 > timeoutMs) fail("界面状态一直是：$text")
            Thread.sleep(250)
        }
    }

    @Test
    fun aWavExtraDrivesTheWholeTranscriptionAndWritesContractFilesAndAReport() {
        DeviceEnv.requireInstalled("zh")
        val runDir = File(DeviceEnv.target.filesDir, "runs/activity-zh").also { it.deleteRecursively() }

        val status = ActivityScenario.launch<TranscribeFileActivity>(launch("wav" to "samples/zh.wav", "out" to "activity-zh")).use { awaitStatus(it) }

        assertTrue(status, status.startsWith("state=SUCCEEDED rtf="))
        assertTrue(status, status.endsWith("out=runs/activity-zh"))
        val report = Json.decodeFromString<RunReport>(File(runDir, "run-report.json").readText())
        assertEquals("SUCCEEDED", report.finalState)
        assertTrue(File(runDir, "revision-content.json").isFile)
        val text = Json.parseToJsonElement(File(runDir, "revision-content.json").readText()).toString()
        assertFalse("界面状态里不能带转录正文", status.contains("开饭"))
        assertTrue("正文只在合同输出里", text.contains("开饭"))
    }

    /** debug 入口是导出的，任何应用都能带参数启动它；输出名不能借 ../ 跑到 runs/ 之外去写文件。 */
    @Test
    fun anOutputNameThatEscapesTheRunsDirectoryIsRejectedBeforeAnythingIsWritten() {
        DeviceEnv.requireInstalled("zh")
        val escaped = File(DeviceEnv.target.filesDir, "escaped-by-test").also { it.deleteRecursively() }

        val status = ActivityScenario.launch<TranscribeFileActivity>(launch("wav" to "samples/zh.wav", "out" to "../escaped-by-test")).use { awaitStatus(it) }

        assertTrue(status, status.startsWith("error=IllegalArgumentException"))
        assertFalse("日志与界面里不回显不合法的名字：$status", status.contains("escaped-by-test"))
        assertFalse("不合法的输出名不能在 runs/ 之外建出目录", escaped.exists())
    }

    /** 屏幕旋转等配置变化会重建 Activity；重建不能再起第二个并发转录（两份模型同时占内存，基准读数也被污染）。 */
    @Test
    fun recreatingTheActivityDuringARunDoesNotStartASecondRun() {
        DeviceEnv.requireInstalled("zh")
        val runDir = File(DeviceEnv.target.filesDir, "runs/activity-recreate").also { it.deleteRecursively() }
        DeviceEnv.shell("logcat -c")

        ActivityScenario.launch<TranscribeFileActivity>(launch("wav" to "samples/zh.wav", "out" to "activity-recreate")).use { scenario ->
            scenario.recreate()
            val deadline = System.nanoTime() + 60_000_000_000L
            while (!File(runDir, "run-report.json").isFile) {
                if (System.nanoTime() > deadline) fail("第一次运行一直没有写出报告")
                Thread.sleep(250)
            }
        }

        // 数“开始”而不是“结束”：多出来的那次运行在重建的那一刻就会写下开始记录，不取决于它多久才跑完
        val started = DeviceEnv.shell("logcat -d -s AuriAsr:I").lines().count { it.contains("start out=runs/activity-recreate") }
        assertEquals("同一个 Intent 只应启动一次转录，日志里出现了 $started 条开始记录", 1, started)
    }

    /** 不合法名字在日志里用 (invalid) 代替；传进来的名字恰好就是 (invalid) 时，不能因为和替身同名而被放行。 */
    @Test
    fun theLiteralPlaceholderTextIsNotAValidOutputNameEither() {
        DeviceEnv.requireInstalled("zh")
        val placeholderDir = File(DeviceEnv.target.filesDir, "runs/(invalid)").also { it.deleteRecursively() }

        val status = ActivityScenario.launch<TranscribeFileActivity>(launch("wav" to "samples/zh.wav", "out" to "(invalid)")).use { awaitStatus(it) }

        assertTrue(status, status.startsWith("error=IllegalArgumentException"))
        assertFalse("(invalid) 不是合法的输出名，不能被当成目录写出", placeholderDir.exists())
    }

    /**
     * 输出名被复用时，请求即使在转录开始之前就被拒绝（清单资源不存在、没带 wav），上一次的正文与报告也不能留着：
     * 否则“这次失败了”和“目录里是上一次成功的结果”同时成立，只看文件的人会被误导。
     */
    @Test
    fun aRequestRejectedBeforeTranscriptionStillClearsThePreviousRunsOutputs() {
        DeviceEnv.requireInstalled("zh")
        val runDir = File(DeviceEnv.target.filesDir, "runs/activity-stale")
        fun leaveStaleOutputs() {
            runDir.deleteRecursively()
            runDir.mkdirs()
            listOf("revision-content.json", "run-report.json", "keep.txt").forEach { File(runDir, it).writeText("STALE-$it") }
        }

        try {
            leaveStaleOutputs()
            val noManifest = ActivityScenario.launch<TranscribeFileActivity>(
                launch("wav" to "samples/zh.wav", "out" to "activity-stale", "manifest" to "no-such-manifest.json"),
            ).use { awaitStatus(it) }
            assertTrue(noManifest, noManifest.startsWith("error="))
            assertEquals("清单资源不存在被拒绝后，旧输出要被清掉，不属于本程序的文件不动", listOf("keep.txt"), runDir.list()!!.sorted())

            leaveStaleOutputs()
            val noWav = ActivityScenario.launch<TranscribeFileActivity>(launch("out" to "activity-stale")).use { awaitStatus(it) }
            assertTrue(noWav, noWav.startsWith("error="))
            assertEquals("没带 wav 被拒绝后同样", listOf("keep.txt"), runDir.list()!!.sorted())
        } finally {
            runDir.deleteRecursively()
        }
    }

    @Test
    fun aMissingWavShowsOnlyTheExceptionTypeAndLeavesNoOutput() {
        DeviceEnv.requireInstalled()
        val runDir = File(DeviceEnv.target.filesDir, "runs/activity-missing").also { it.deleteRecursively() }

        val status = ActivityScenario.launch<TranscribeFileActivity>(launch("wav" to "samples/does-not-exist.wav", "out" to "activity-missing")).use { awaitStatus(it) }

        assertTrue(status, status.startsWith("error="))
        assertFalse("界面状态不能泄露路径", status.contains("does-not-exist"))
        assertFalse("输入有问题时不留半成品输出", runDir.exists())
    }
}
