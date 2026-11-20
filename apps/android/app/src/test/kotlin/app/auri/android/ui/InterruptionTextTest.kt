package app.auri.android.ui

import app.auri.android.home.InterruptionFact
import app.auri.android.home.Notice
import app.auri.capture.contract.EndReason
import app.auri.capture.contract.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 19 第 5、6 项的“界面有明确的状态”：被打断之后首页要说清是哪一种、已录下的部分怎么样了、要不要用户做什么。
 * 文案本身可以改，这里钉住的是用户必须读得到的事实：原因、已保存、不会自己继续。
 */
class InterruptionTextTest {
    private val facts = listOf(
        InterruptionFact(EndReason.ROUTE_LOST, ErrorCode.INPUT_ROUTE_CHANGED),
        InterruptionFact(EndReason.ROUTE_LOST, ErrorCode.INPUT_DEVICE_MISSING),
        InterruptionFact(EndReason.SYSTEM_INTERRUPTION, ErrorCode.AUDIO_INTERRUPTED),
        InterruptionFact(EndReason.SYSTEM_INTERRUPTION, ErrorCode.STORAGE_EXHAUSTED),
        InterruptionFact(EndReason.SYSTEM_INTERRUPTION, ErrorCode.BACKGROUND_START_DENIED),
        InterruptionFact(EndReason.SYSTEM_INTERRUPTION, null),
        InterruptionFact(EndReason.PERMISSION_REVOKED, ErrorCode.PERMISSION_MIC_DENIED),
        InterruptionFact(EndReason.PERMISSION_REVOKED, ErrorCode.INPUT_DEVICE_MISSING),
    )

    @Test
    fun `每一种打断都有说明：录音已停止、已录的部分已保存、不会自动继续`() {
        for (fact in facts) {
            val text = describeInterruption(fact)

            assertTrue("$fact：要说录音已停止（$text）", "已停止" in text)
            assertTrue("$fact：要说已录的部分已保存（$text）", "已保存" in text || "已写入" in text)
            assertTrue("$fact：要说不会自动继续（$text）", "不会自动继续" in text)
        }
    }

    @Test
    fun `原因说清楚：耳机断开、来电或抢占、存储写满、权限被关各有各的说法`() {
        val route = describeInterruption(InterruptionFact(EndReason.ROUTE_LOST, ErrorCode.INPUT_ROUTE_CHANGED))
        val call = describeInterruption(InterruptionFact(EndReason.SYSTEM_INTERRUPTION, ErrorCode.AUDIO_INTERRUPTED))
        val full = describeInterruption(InterruptionFact(EndReason.SYSTEM_INTERRUPTION, ErrorCode.STORAGE_EXHAUSTED))
        val permission = describeInterruption(InterruptionFact(EndReason.PERMISSION_REVOKED, ErrorCode.PERMISSION_MIC_DENIED))

        assertTrue("耳机断开要提到耳机（$route）", "耳机" in route)
        assertTrue("来电要提到来电（$call）", "来电" in call)
        assertTrue("存储写满要提到存储（$full）", "存储" in full)
        assertTrue("存储写满要告诉用户没出现在列表里时会找回（$full）", "找回" in full)
        assertTrue("权限被撤销要提到权限（$permission）", "权限" in permission)
        assertEquals("四种原因四句不同的话", 4, setOf(route, call, full, permission).size)
    }

    @Test
    fun `同一个结束原因下错误码不同，说法也不同；蓝牙权限被撤销与耳机断开共用错误码，但说法不同`() {
        val missingByDisconnect = describeInterruption(InterruptionFact(EndReason.ROUTE_LOST, ErrorCode.INPUT_DEVICE_MISSING))
        val missingByPermission = describeInterruption(InterruptionFact(EndReason.PERMISSION_REVOKED, ErrorCode.INPUT_DEVICE_MISSING))
        val call = describeInterruption(InterruptionFact(EndReason.SYSTEM_INTERRUPTION, ErrorCode.AUDIO_INTERRUPTED))
        val full = describeInterruption(InterruptionFact(EndReason.SYSTEM_INTERRUPTION, ErrorCode.STORAGE_EXHAUSTED))

        assertTrue("同一个错误码、不同的结束原因：说法要看结束原因", missingByDisconnect != missingByPermission)
        assertTrue("同一个结束原因、不同的错误码：说法要看错误码", call != full)
    }

    @Test
    fun `录音没能封存的提示说明已写下的部分还留在手机上、下次打开应用会尝试找回（存储写满时尤其如此）`() {
        val text = describeNotice(Notice.RecordingLost(ErrorCode.STORAGE_EXHAUSTED))

        assertTrue("要说没能完整保存（$text）", "没能" in text)
        assertTrue("要说已写下的部分还在，下次会找回（$text）", "找回" in text)
    }

    @Test
    fun `被截断的 Record 标出原因，四种各不相同；应用被中途关闭的要说明是从磁盘找回的`() {
        val reasons = listOf(EndReason.ROUTE_LOST, EndReason.SYSTEM_INTERRUPTION, EndReason.PERMISSION_REVOKED, EndReason.PROCESS_TERMINATED)
        val texts = reasons.map(::describeTruncation)

        assertTrue("每一种都要有说明：$texts", texts.all { "被截断" in it })
        assertEquals("四种原因四句不同的话", 4, texts.toSet().size)
        assertTrue("应用被中途关闭：要说明是找回的", "找回" in describeTruncation(EndReason.PROCESS_TERMINATED))
    }
}
