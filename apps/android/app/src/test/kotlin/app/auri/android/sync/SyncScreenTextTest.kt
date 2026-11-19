package app.auri.android.sync

import app.auri.android.ui.describeCause
import app.auri.android.ui.describeCounts
import app.auri.android.ui.describeHeadline
import app.auri.android.ui.describeProblem
import app.auri.android.ui.describeStorageProblem
import app.auri.sync.status.SyncCounts
import app.auri.sync.status.SyncHeadline
import app.auri.sync.status.SyncStatus
import app.auri.sync.status.UserCause
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同步页上的字：Ticket 21 第 6 项要求“等网关”“稍后重试”“需要你处理”是三种不同的情形。
 * 用户读到的话必须不同、并且只有需要用户处理的那一种让他做事——混成一句“同步失败”，用户要么白担心、要么漏掉真正的问题。
 */
class SyncScreenTextTest {
    private fun status(headline: SyncHeadline, causes: List<UserCause> = emptyList(), retryAt: Instant? = null) =
        SyncStatus(headline, causes, SyncCounts(1, 2, 3, 4, 5, 6), retryAt)

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
    fun `计数那一行六个数都在，顺序固定`() {
        assertEquals("排队 1 · 发送中 2 · 等网关 3 · 稍后重试 4 · 需处理 5 · 已入库 6", describeCounts(SyncCounts(1, 2, 3, 4, 5, 6)))
    }
}
