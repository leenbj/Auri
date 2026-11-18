package app.auri.capture.control

import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.ControlSource
import app.auri.capture.contract.NormalizedControlEvent
import app.auri.capture.contract.PlatformEvent
import app.auri.capture.testing.SequentialIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 归一化与去抖的规则（PRD FR-HS-001 / FR-HS-004）：
 *  - 平台事件 → 业务命令只经过档案的映射；来源只有“系统能证明是外接设备”才写 headset_remote，其余都是 unknown_remote；
 *    永远不猜左右耳，也不假设存在原始的“双击”。
 *  - 同一次手势的突发重复（同一个原始事件被递送两次，或一个手势同时发出几个事件）只算一次：绝不先开始再立刻停止。
 *  - 窗口过后的第二次手势照常通过：录音准备中（ROUTING）的停止 / 切换不会被去抖吞掉。
 */
class HeadsetEventNormalizerTest {
    private val ms = 1_000_000L
    private val generic = ControlProfile.generic()

    private fun normalizer(config: ControlConfig = ControlConfig()) = HeadsetEventNormalizer(SequentialIds(), config)

    private fun input(
        kind: MediaKind,
        atMs: Long,
        phase: KeyPhase = KeyPhase.DOWN,
        rawId: String? = null,
        issuedAtMs: Long? = null,
        external: Boolean? = true,
    ) = RawMediaInput(kind, phase, rawId, issuedAtMs?.times(ms), external, atMs * ms)

    private fun HeadsetEventNormalizer.emit(input: RawMediaInput, profile: ControlProfile = generic): NormalizedControlEvent {
        val result = normalize(input, profile)
        assertTrue("应当产生事件，实际是 $result", result is NormalizeResult.Emit)
        return (result as NormalizeResult.Emit).event
    }

    private fun HeadsetEventNormalizer.dropReason(input: RawMediaInput, profile: ControlProfile = generic): DropReason {
        val result = normalize(input, profile)
        assertTrue("应当被丢弃，实际是 $result", result is NormalizeResult.Drop)
        return (result as NormalizeResult.Drop).reason
    }

    // ---- 映射与来源 ----

    @Test
    fun `播放键：归一成 play 事件并映射为 START，带着来源、序号、档案与单调时间戳`() {
        val event = normalizer().emit(input(MediaKind.PLAY, atMs = 5_000, rawId = "key:126:0:t:4999"))

        assertEquals(ControlSource.HEADSET_REMOTE, event.source)
        assertEquals(PlatformEvent.PLAY, event.platformEvent)
        assertEquals(BusinessCommand.START, event.command)
        assertEquals(0L, event.sequence)
        assertEquals(5_000 * ms, event.monotonicNs)
        assertEquals(ControlProfile.GENERIC_ID, event.profileId)
        assertEquals(1, event.profileVersion)
        assertEquals("key:126:0:t:4999", event.rawEventId)
    }

    @Test
    fun `系统不能证明事件来自外接设备时来源是 unknown_remote：不知道就是不知道，也不猜左右耳`() {
        val n = normalizer()

        assertEquals(ControlSource.UNKNOWN_REMOTE, n.emit(input(MediaKind.PLAY, 0, external = null)).source)
        assertEquals(ControlSource.UNKNOWN_REMOTE, n.emit(input(MediaKind.PLAY, 1_000, external = false)).source)
        assertEquals(ControlSource.HEADSET_REMOTE, n.emit(input(MediaKind.PLAY, 2_000, external = true)).source)
    }

    @Test
    fun `每种媒体键对应的平台事件与命令：切换键与耳机挂机键是 toggle，上一首下一首只是被忽略的事件`() {
        val n = normalizer()
        val table = listOf(
            Triple(MediaKind.PLAY, PlatformEvent.PLAY, BusinessCommand.START),
            Triple(MediaKind.PAUSE, PlatformEvent.PAUSE, BusinessCommand.STOP),
            Triple(MediaKind.PLAY_PAUSE, PlatformEvent.TOGGLE, BusinessCommand.TOGGLE),
            Triple(MediaKind.HEADSET_HOOK, PlatformEvent.TOGGLE, BusinessCommand.TOGGLE),
            Triple(MediaKind.STOP, PlatformEvent.PAUSE, BusinessCommand.STOP),
            Triple(MediaKind.NEXT, PlatformEvent.NEXT, BusinessCommand.IGNORE),
            Triple(MediaKind.PREVIOUS, PlatformEvent.PREVIOUS, BusinessCommand.IGNORE),
        )

        table.forEachIndexed { index, (kind, platformEvent, command) ->
            val event = n.emit(input(kind, atMs = 10_000L * (index + 1)))
            assertEquals("$kind 的平台事件", platformEvent, event.platformEvent)
            assertEquals("$kind 的命令", command, event.command)
        }
    }

    @Test
    fun `停止键只能停止：即使档案把 pause 映射成切换，停止键也不会开始录音`() {
        val pauseIsToggle = ControlProfile.generic().withSingleEventToggle(PlatformEvent.PAUSE)
        val n = normalizer()

        assertEquals(BusinessCommand.TOGGLE, n.emit(input(MediaKind.PAUSE, 0), pauseIsToggle).command)
        assertEquals("MEDIA_STOP 是明确的停止意图", BusinessCommand.STOP, n.emit(input(MediaKind.STOP, 10_000), pauseIsToggle).command)
    }

    @Test
    fun `观察档案下事件照常产生但命令是 IGNORE，停止键也是`() {
        val observing = generic.observeOnly()
        val n = normalizer()

        assertEquals(BusinessCommand.IGNORE, n.emit(input(MediaKind.PLAY, 0), observing).command)
        assertEquals(BusinessCommand.IGNORE, n.emit(input(MediaKind.STOP, 10_000), observing).command)
        assertEquals(PlatformEvent.PLAY, n.emit(input(MediaKind.PLAY, 20_000), observing).platformEvent)
    }

    @Test
    fun `事件带的是当前档案的编号与版本：换了档案，之后的事件跟着换`() {
        val calibrated = ControlProfile("calibrated-android", 3, mapOf(PlatformEvent.PLAY to BusinessCommand.TOGGLE))
        val n = normalizer()

        assertEquals(ControlProfile.GENERIC_ID, n.emit(input(MediaKind.PLAY, 0)).profileId)
        val after = n.emit(input(MediaKind.PLAY, 10_000), calibrated)
        assertEquals("calibrated-android", after.profileId)
        assertEquals(3, after.profileVersion)
        assertEquals(BusinessCommand.TOGGLE, after.command)
    }

    // ---- 不产生命令的输入 ----

    @Test
    fun `按键抬起与长按自动重复不产生事件：一次手势只有一个按下`() {
        val n = normalizer()

        assertEquals(DropReason.KEY_UP, n.dropReason(input(MediaKind.PLAY, 0, phase = KeyPhase.UP)))
        assertEquals(DropReason.KEY_REPEAT, n.dropReason(input(MediaKind.PLAY, 10, phase = KeyPhase.REPEAT)))
        assertEquals("抬起与重复不能占用去抖窗口", 0L, n.emit(input(MediaKind.PLAY, 20)).sequence)
    }

    @Test
    fun `不认识的媒体键不产生事件，也不占用序号与去抖窗口`() {
        val n = normalizer()

        assertEquals(DropReason.UNMAPPED_KEY, n.dropReason(input(MediaKind.OTHER, 0)))
        assertEquals(0L, n.emit(input(MediaKind.PLAY, 10)).sequence)
    }

    // ---- 去抖：一次手势只算一次 ----

    @Test
    fun `同一个原始事件被递送两次只算一次：晚到的重复也一样`() {
        val n = normalizer()

        n.emit(input(MediaKind.PLAY_PAUSE, 0, rawId = "key:85:0:t:1"))
        assertEquals(DropReason.DUPLICATE_RAW_EVENT, n.dropReason(input(MediaKind.PLAY_PAUSE, 2_000, rawId = "key:85:0:t:1")))
    }

    @Test
    fun `一次手势发出一串事件：先开始再立刻停止永远不会发生，只产生第一个`() {
        val n = normalizer()
        val burst = listOf(
            input(MediaKind.PLAY, 0),
            input(MediaKind.PAUSE, 20),
            input(MediaKind.PLAY_PAUSE, 45),
            input(MediaKind.HEADSET_HOOK, 90),
            input(MediaKind.STOP, 200),
        )

        val results = burst.map { n.normalize(it, generic) }

        assertEquals(1, results.count { it is NormalizeResult.Emit })
        assertEquals(BusinessCommand.START, (results.first() as NormalizeResult.Emit).event.command)
        assertTrue(results.drop(1).all { it is NormalizeResult.Drop && it.reason == DropReason.SAME_GESTURE_BURST })
    }

    @Test
    fun `窗口边界：差一纳秒还是同一次手势，刚好满窗口就是新的手势`() {
        val config = ControlConfig(gestureWindowNs = 300 * ms)
        val n = normalizer(config)
        n.emit(input(MediaKind.PLAY, 1_000))

        assertEquals(DropReason.SAME_GESTURE_BURST, n.dropReason(RawMediaInput(MediaKind.PAUSE, receivedAtNs = 1_000 * ms + 300 * ms - 1)))
        val next = n.emit(RawMediaInput(MediaKind.PAUSE, receivedAtNs = 1_000 * ms + 300 * ms))
        assertEquals(BusinessCommand.STOP, next.command)
    }

    @Test
    fun `被去抖丢掉的事件不会延长窗口：窗口从最近一个真正产生的事件算起`() {
        val config = ControlConfig(gestureWindowNs = 300 * ms)
        val n = normalizer(config)
        n.emit(input(MediaKind.PLAY, 0))
        n.dropReason(input(MediaKind.PAUSE, 250))

        // 如果被丢掉的 250ms 那个也续了窗口，350ms 的这个会被误吞
        assertEquals(BusinessCommand.STOP, n.emit(input(MediaKind.PAUSE, 350)).command)
    }

    @Test
    fun `录音准备中的停止不会被去抖吞掉：窗口过后第二次手势照常通过，序号继续递增`() {
        val n = normalizer()

        val start = n.emit(input(MediaKind.PLAY_PAUSE, 0))
        val cancel = n.emit(input(MediaKind.PLAY_PAUSE, 1_500))
        val explicitStop = n.emit(input(MediaKind.PAUSE, 3_000))

        assertEquals(listOf(0L, 1L, 2L), listOf(start, cancel, explicitStop).map { it.sequence })
        assertEquals(BusinessCommand.STOP, explicitStop.command)
    }

    @Test
    fun `没有原始标识的事件绝不会因为“标识相同”被当成重复：两次相隔够远的按键都通过`() {
        val n = normalizer()

        n.emit(input(MediaKind.PLAY_PAUSE, 0, rawId = null))
        n.emit(input(MediaKind.PLAY_PAUSE, 5_000, rawId = null))
        n.emit(input(MediaKind.PLAY_PAUSE, 10_000, rawId = null))
    }

    @Test
    fun `原始标识的记忆有上限：太久以前的标识会被忘掉，不会无限增长`() {
        val n = normalizer(ControlConfig(rawIdMemory = 2))

        n.emit(input(MediaKind.PLAY, 0, rawId = "a"))
        n.emit(input(MediaKind.PLAY, 10_000, rawId = "b"))
        n.emit(input(MediaKind.PLAY, 20_000, rawId = "c"))

        // a 已被挤出记忆：同一个标识再来会被当作新事件；b、c 还记得
        n.emit(input(MediaKind.PLAY, 30_000, rawId = "a"))
        assertEquals(DropReason.DUPLICATE_RAW_EVENT, n.dropReason(input(MediaKind.PLAY, 40_000, rawId = "c")))
    }

    @Test
    fun `事件标识与序号唯一递增：每个事件有自己的 event_id`() {
        val n = normalizer()

        val events = (0 until 5).map { n.emit(input(MediaKind.PLAY_PAUSE, atMs = 1_000L * it)) }

        assertEquals((0L until 5L).toList(), events.map { it.sequence })
        assertEquals(5, events.map { it.eventId }.toSet().size)
    }

    @Test
    fun `过长的原始标识被截断到合同上限，归一化本身不会因此抛异常`() {
        val event = normalizer().emit(input(MediaKind.PLAY, 0, rawId = "x".repeat(500)))

        assertEquals(200, event.rawEventId!!.length)
    }

    @Test
    fun `重置手势状态：去抖窗口与原始标识清空，序号继续递增`() {
        val n = normalizer()
        n.emit(input(MediaKind.PLAY, 0, rawId = "k"))

        n.resetGestureState()

        val again = n.emit(input(MediaKind.PLAY, 10, rawId = "k"))
        assertEquals("序号在适配器的整个生命期内单调递增", 1L, again.sequence)
    }

    // ---- 时间戳：按“手势发生的时刻”记，不是“我们处理它的时刻” ----

    @Test
    fun `平台给了手势发生的时刻就用它：晚到的命令仍然带着它真正发出的时间`() {
        val event = normalizer().emit(input(MediaKind.PLAY_PAUSE, atMs = 5_000, issuedAtMs = 4_200))

        assertEquals(4_200 * ms, event.monotonicNs)
    }

    @Test
    fun `手势时刻不可信时退回接收时刻：未来的、过老的、负的、没有的`() {
        val config = ControlConfig(maxIssuedAgeNs = 5_000 * ms)
        val n = normalizer(config)

        assertEquals("比接收时刻还晚：时钟对不上", 10_000 * ms, n.emit(input(MediaKind.PLAY, 10_000, issuedAtMs = 10_500)).monotonicNs)
        assertEquals("比接收时刻早太多：不可信", 30_000 * ms, n.emit(input(MediaKind.PLAY, 30_000, issuedAtMs = 20_000)).monotonicNs)
        assertEquals("负数", 50_000 * ms, n.emit(input(MediaKind.PLAY, 50_000, issuedAtMs = -1)).monotonicNs)
        assertEquals("没有", 70_000 * ms, n.emit(input(MediaKind.PLAY, 70_000, issuedAtMs = null)).monotonicNs)
        assertEquals("刚好在上限内", 85_000 * ms, n.emit(input(MediaKind.PLAY, 90_000, issuedAtMs = 85_000)).monotonicNs)
    }

    @Test
    fun `去抖按手势时刻比较，不按递送时刻：延迟递送的同一次手势也算同一次`() {
        val n = normalizer()
        // 同一次手势在 1000ms 发生，两个事件分别在 1200ms 与 2500ms 才递送（主线程卡住）
        n.emit(input(MediaKind.PLAY, atMs = 1_200, issuedAtMs = 1_000))

        assertEquals(DropReason.SAME_GESTURE_BURST, n.dropReason(input(MediaKind.PAUSE, atMs = 2_500, issuedAtMs = 1_010)))
    }
}
