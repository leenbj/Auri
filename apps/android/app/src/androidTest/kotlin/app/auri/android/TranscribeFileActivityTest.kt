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
