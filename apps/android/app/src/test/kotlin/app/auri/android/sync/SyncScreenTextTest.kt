package app.auri.android.sync

import app.auri.android.ui.describeCause
import app.auri.android.ui.describeCounts
import app.auri.android.ui.describeGrantHint
import app.auri.android.ui.describeHeadline
import app.auri.android.ui.describeProblem
import app.auri.android.ui.describeStorageProblem
import app.auri.android.ui.describeStrictLocalHint
import app.auri.sync.policy.DataFlowState
import app.auri.sync.status.SyncCounts
import app.auri.sync.status.SyncHeadline
import app.auri.sync.status.SyncStatus
import app.auri.sync.status.UserCause
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同步页上的字：Ticket 21 第 6 项要求“等网关”“稍后重试”“需要你处理”是三种不同的情形。
 * 用户读到的话必须不同、并且只有需要用户处理的那一种让他做事——混成一句“同步失败”，用户要么白担心、要么漏掉真正的问题。
 */
class SyncScreenTextTest {
    private fun status(headline: SyncHeadline, causes: List<UserCause> = emptyList(), retryAt: Instant? = null) =
        SyncStatus(headline, causes, SyncCounts(1, 2, 3, 4, 5, 6), retryAt, DataFlowState(gatewayTextGranted = true, strictLocal = false))

    @Test
    fun `六种情形的话两两不同`() {
        val texts = SyncHeadline.entries.map { describeHeadline(status(it)) }

        assertEquals(texts.toString(), SyncHeadline.entries.size, texts.toSet().size)
        assertTrue(texts.all { it.isNotBlank() })
    }

    @Test
    fun `等网关与稍后重试明说不需要用户做什么，需要用户处理的那一种不这么说`() {
        val waiting = describeHeadline(status(SyncHeadline.WAITING_FOR_GATEWAY))
        val retrying = describeHeadline(status(SyncHeadline.RETRYING_LATER))
        val needsUser = describeHeadline(status(SyncHeadline.NEEDS_USER, listOf(UserCause.KEY_MISMATCH)))

        assertTrue(waiting, "不需要你做什么" in waiting)
        assertTrue(retrying, "不需要你做什么" in retrying)
        assertFalse(needsUser, "不需要你做什么" in needsUser)
        assertTrue(needsUser, "需要你处理" in needsUser)
    }

    @Test
    fun `稍后重试在知道下次重试时间时带上它`() {
        val at = Instant.parse("2026-11-18T10:00:00Z")

        assertTrue(describeHeadline(status(SyncHeadline.RETRYING_LATER, retryAt = at)).contains("2026-11-18T10:00:00Z"))
    }

    @Test
    fun `每个需要用户处理的原因都有自己的话，并且告诉用户该怎么办或发生了什么`() {
        val texts = UserCause.entries.map { describeCause(it) }

        assertEquals(UserCause.entries.size, texts.toSet().size)
        assertTrue(texts.all { it.isNotBlank() })
        // 连接层面的三个原因要用户重新配对
        for (cause in listOf(UserCause.KEY_MISMATCH, UserCause.AUTH_REJECTED, UserCause.PAIRING_LOST)) {
            assertTrue("$cause", "重新配对" in describeCause(cause))
        }
    }

    @Test
    fun `每种配对失败的话两两不同，需要在网关上重新生成的几种都这么说`() {
        val texts = PairingProblem.entries.map { describeProblem(it) }

        assertEquals(PairingProblem.entries.size, texts.toSet().size)
        for (problem in listOf(PairingProblem.EXPIRED, PairingProblem.CODE_REJECTED, PairingProblem.UNCERTAIN)) {
            assertTrue("$problem", "重新生成" in describeProblem(problem))
        }
    }

    @Test
    fun `本机存储暂时读不了：是另一种情形，明说会自动重试、不需要用户做什么，不能被读成“同步失败”`() {
        val storage = describeStorageProblem()

        assertTrue(storage, "自动重试" in storage && "不需要你做什么" in storage)
        assertFalse(storage, SyncHeadline.entries.any { describeHeadline(status(it)) == storage })
    }

    @Test
    fun `文字被留在本机：没授权与严格仅本地是两句不同的话，都明说文字只在手机上，没授权那句告诉用户哪个开关能放行，且不是故障的口气（评审 B1）`() {
        val notGranted = describeHeadline(status(SyncHeadline.HELD_BY_POLICY).copy(dataFlow = DataFlowState.Unset))
        val strictLocal = describeHeadline(status(SyncHeadline.HELD_BY_POLICY).copy(dataFlow = DataFlowState(gatewayTextGranted = true, strictLocal = true)))

        assertNotEquals(notGranted, strictLocal)
        for (text in listOf(notGranted, strictLocal)) {
            assertTrue(text, "只保存在这台手机上" in text)
            assertFalse("这不是故障：$text", "失败" in text || "出错" in text)
        }
        assertTrue(notGranted, "允许把文字发送到我配对的网关" in notGranted)
        assertTrue(strictLocal, "严格仅本地" in strictLocal)
        assertTrue("开关旁的说明要讲清默认关闭、与其它授权互相独立", "默认关闭" in describeGrantHint() && "各自独立" in describeGrantHint())
        assertTrue(describeStrictLocalHint(), "一个字节也不会离开" in describeStrictLocalHint())
    }

    @Test
    fun `计数那一行六个数都在，顺序固定`() {
        assertEquals("排队 1 · 发送中 2 · 等网关 3 · 稍后重试 4 · 需处理 5 · 已入库 6", describeCounts(SyncCounts(1, 2, 3, 4, 5, 6)))
    }
}
