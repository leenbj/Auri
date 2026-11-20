package app.auri.android.destinations

import app.auri.sync.destinations.AuthResult
import app.auri.sync.destinations.DeliveryView
import app.auri.sync.destinations.GatewayStage
import app.auri.sync.destinations.QueuedPhase
import app.auri.sync.destinations.ServiceFailure
import app.auri.sync.destinations.TargetDecision
import app.auri.sync.destinations.UnavailableReason
import app.auri.sync.destinations.UnknownReason
import app.auri.sync.gateway.Intent
import app.auri.sync.gateway.SendMode
import app.auri.sync.pairing.NeedsUser
import app.auri.sync.policy.DataFlowState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 网关不知道 Agent 之后做了什么：界面里任何一句话都不许说“已处理”这一类的话。 */
internal val PROCESSED_WORDS = listOf("已处理", "已完成", "已消费", "已执行", "处理完", "处理好", "已办", "已读", "已被执行")

/**
 * Ticket 22 第 6 项的字面：详情页显示“已入库 / 已领取 / 已确认”，**不把已确认说成已处理**。
 * 这里把每一种能走到的状态的话都过一遍：两两不同、没有“已处理”一类的词、读不出来的显示未知而不是成功。
 */
class DestinationTextTest {
    private val everyView: List<DeliveryView> = buildList {
        add(DeliveryView.AwaitingYou)
        add(DeliveryView.Skipped)
        add(DeliveryView.NotTargeted)
        add(DeliveryView.Suspended)
        QueuedPhase.entries.forEach { add(DeliveryView.Queued(it)) }
        GatewayStage.entries.forEach { add(DeliveryView.OnGateway(it)) }
        UnknownReason.entries.forEach { add(DeliveryView.Unknown(it)) }
    }

    private val everyFailure: List<ServiceFailure> = listOf(
        ServiceFailure.NotPaired,
        ServiceFailure.NeedsRepair(NeedsUser.KEY_MISMATCH),
        ServiceFailure.NeedsRepair(NeedsUser.AUTH_REJECTED),
        ServiceFailure.Unreachable,
        ServiceFailure.Rejected(422, null, retryable = false),
        ServiceFailure.Rejected(503, null, retryable = true),
        ServiceFailure.Unreadable,
        ServiceFailure.Interrupted,
        ServiceFailure.DestinationUnavailable,
        ServiceFailure.Busy,
        ServiceFailure.LimitReached,
    )

    private val notAuthenticated: List<AuthResult> = listOf(AuthResult.Cancelled, AuthResult.Denied) + UnavailableReason.entries.map { AuthResult.Unavailable(it) }

    @Test
    fun `网关能证明的三步，每一步用它自己的字：已入库、已领取、已确认`() {
        assertTrue(describeStage(GatewayStage.STORED), "已入库" in describeStage(GatewayStage.STORED))
        assertTrue(describeStage(GatewayStage.CLAIMED), "已领取" in describeStage(GatewayStage.CLAIMED))
        assertTrue(describeStage(GatewayStage.ACKNOWLEDGED), "已确认" in describeStage(GatewayStage.ACKNOWLEDGED))
    }

    @Test
    fun `每一种状态的话都不同，也都不是空的`() {
        val texts = everyView.map { describeDelivery(it) }

        assertTrue(texts.all { it.isNotBlank() })
        assertEquals(texts.toString(), everyView.size, texts.toSet().size)
    }

    @Test
    fun `没有任何一句话说“已处理”：已确认只是 Agent 回了确认`() {
        for (view in everyView) {
            val text = describeDelivery(view)
            for (word in PROCESSED_WORDS) assertFalse("$view 的话里有“$word”：$text", text.contains(word))
        }
        val acknowledged = describeDelivery(DeliveryView.OnGateway(GatewayStage.ACKNOWLEDGED))
        assertTrue(acknowledged, "只知道它回了确认" in acknowledged)
    }

    @Test
    fun `读不出网关状态的各种情形都明说未知，没有一种读起来像成功`() {
        for (reason in UnknownReason.entries) {
            val text = describeDelivery(DeliveryView.Unknown(reason))
            assertTrue("$reason：$text", text.startsWith("状态未知"))
            for (success in listOf("已入库", "已领取", "已确认")) assertFalse("$reason 的话里有“$success”：$text", text.contains(success))
        }
    }

    @Test
    fun `还没发出去的几种情形，也不会被读成已入库`() {
        for (view in listOf(DeliveryView.AwaitingYou, DeliveryView.Skipped, DeliveryView.NotTargeted, DeliveryView.Suspended) + QueuedPhase.entries.map { DeliveryView.Queued(it) }) {
            val text = describeDelivery(view)
            assertFalse("$view：$text", text.contains("已入库"))
        }
        assertTrue("等你确认" in describeDelivery(DeliveryView.AwaitingYou))
    }

    @Test
    fun `凭证被撤销的暂停（评审 B3）：说清它没被撤销、新文字不再送它、换新凭证只对之后的文字生效，暂停期间的不补发`() {
        val note = describeSuspension()
        for (needle in listOf("暂停", "凭证被撤销", "不会再送给它", "重新生成凭证", "之后的文字", "不会补发")) assertTrue("缺“$needle”：$note", needle in note)
        assertTrue(SUSPENDED_TAG.isNotBlank() && "暂停" in SUSPENDED_TAG)
        val line = describeDelivery(DeliveryView.Suspended)
        assertTrue(line, "没有送给它" in line && "不会补发" in line)
        assertTrue("已被撤销的说法不能和暂停混在一起：$note", "已被撤销" !in note)
    }

    @Test
    fun `每种没做成的原因都有自己的话，结果不确定的两种要用户先刷新、不要直接重试`() {
        val texts = everyFailure.map { describeFailure(it) }

        assertTrue(texts.all { it.isNotBlank() })
        assertEquals(texts.toString(), everyFailure.size, texts.toSet().size)
        for (failure in listOf(ServiceFailure.Unreadable, ServiceFailure.Interrupted)) {
            val text = describeFailure(failure)
            assertTrue("$failure：$text", "结果不确定" in text && "刷新" in text && "不要直接重试" in text)
        }
        assertTrue("请求一定没发出去的，明说没有发出", "没有发出" in describeFailure(ServiceFailure.Unreachable))
    }

    @Test
    fun `本机核验没通过的每种情形都说明没有生成凭证，并且说法两两不同`() {
        val texts = notAuthenticated.map { describeNotAuthenticated(it) }

        assertEquals(texts.toString(), notAuthenticated.size, texts.toSet().size)
        assertTrue(texts.all { "没有生成凭证" in it || "不能生成凭证" in it })
        assertThrows(IllegalStateException::class.java) { describeNotAuthenticated(AuthResult.Authenticated) }
    }

    @Test
    fun `没登记生物识别时（评审 B4）：明说锁屏密码不能代替、凭证没有生成、该去登记指纹或人脸；没有硬件时也不再提锁屏方式`() {
        val none = describeNotAuthenticated(AuthResult.Unavailable(UnavailableReason.NONE_ENROLLED))
        for (needle in listOf("指纹或人脸", "锁屏密码不能代替", "没有生成凭证")) assertTrue("缺“$needle”：$none", needle in none)
        val noHardware = describeNotAuthenticated(AuthResult.Unavailable(UnavailableReason.NO_HARDWARE))
        assertFalse("没有硬件：不能说锁屏方式可以顶替：$noHardware", "锁屏方式" in noHardware)
        assertTrue(noHardware, "生物识别" in noHardware && "不能生成凭证" in noHardware)
    }

    @Test
    fun `凭证的说明：生成与轮换都说清只显示这一次；轮换还说旧的立刻失效`() {
        assertTrue("只显示这一次" in describeCredentialRules(rotating = false))
        assertTrue("只显示这一次" in describeCredentialRules(rotating = true))
        assertTrue("旧凭证立刻失效" in describeCredentialRules(rotating = true))
    }

    @Test
    fun `凭证面板如实说剪贴板里的凭证到点会被清掉：说的秒数就是守卫真正用的那个`() {
        val notice = describeClipboardNotice()

        assertTrue(notice, "${SecretClipboardGuard.TIMEOUT_MS / 1_000} 秒" in notice)
        assertTrue(notice, "自动清掉" in notice)
        assertTrue("清掉之后只能轮换：凭证只显示一次，不要让人以为还能找回", "轮换" in notice)
    }

    @Test
    fun `两种发送方式的话不同；“自动发送”说明发出去的是未审核的初稿，“预览确认”说明不点就不发`() {
        val auto = describeSendModeDetail(SendMode.AUTO_SEND)
        val preview = describeSendModeDetail(SendMode.PREVIEW_CONFIRM)

        assertTrue(describeSendMode(SendMode.AUTO_SEND) != describeSendMode(SendMode.PREVIEW_CONFIRM))
        assertTrue(auto, "没有审核过" in auto && "不再问你" in auto)
        assertTrue(preview, "确认发送" in preview && "不会发" in preview)
        assertEquals("未审核", UNREVIEWED_LABEL)
    }

    @Test
    fun `请求执行标签的说明：只是数据上的标记，不会被执行，也不给 Agent 额外权限`() {
        val note = describeIntentNote(Intent.ACTION_REQUEST)

        assertTrue(note, "不会被自动执行" in note && "不会因此多得到任何权限" in note)
        assertTrue(describeIntent(Intent.CONTEXT) != describeIntent(Intent.ACTION_REQUEST))
    }

    @Test
    fun `预览里每个目标的处置，说法两两不同`() {
        val texts = listOf(
            describeDecision(TargetDecision.AUTO, true),
            describeDecision(TargetDecision.AWAITING, true),
            describeDecision(TargetDecision.CONFIRMED, true),
            describeDecision(TargetDecision.SKIPPED, true),
            describeDecision(TargetDecision.AWAITING, false),
        )

        assertEquals(texts.toString(), texts.size, texts.toSet().size)
    }

    @Test
    fun `创建时的提示说明没有默认值`() {
        assertTrue("没有默认值" in describeCreateHint())
    }

    @Test
    fun `没放行文字时待发送页顶上的提示：没授权与严格仅本地分开说，都讲清点确认只是记下决定；放行时没有提示`() {
        assertNull(describeDataFlowHold(DataFlowState(gatewayTextGranted = true, strictLocal = false)))

        val notGranted = describeDataFlowHold(DataFlowState.Unset)!!
        assertTrue(notGranted, "允许把文字发送到我配对的网关" in notGranted && "记下你的决定" in notGranted && "不会发出" in notGranted)

        val strict = describeDataFlowHold(DataFlowState(gatewayTextGranted = true, strictLocal = true))!!
        assertTrue("授权了也一样：严格仅本地压过它。$strict", "严格仅本地" in strict && "一个字节也不会离开" in strict)

        assertEquals("读不到时按最保守的算：说的是严格仅本地", describeDataFlowHold(DataFlowState.Closed), strict)
        assertTrue(notGranted != strict)
    }
}
