package app.auri.capture.control

import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.PlatformEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 命令映射的规则来自 PRD FR-HS-004 与 headset-profile.schema.json 的 command_mapping：
 * 键是平台事件（不是物理手势），映射里没有的事件不是命令，音量变化永远不能用来开始录音。
 */
class ControlProfileTest {
    @Test
    fun `默认档案按 FR-HS-004：play 开始、pause 停止、toggle 切换，上一首下一首与其余事件都忽略`() {
        val generic = ControlProfile.generic()

        assertEquals(BusinessCommand.START, generic.commandFor(PlatformEvent.PLAY))
        assertEquals(BusinessCommand.STOP, generic.commandFor(PlatformEvent.PAUSE))
        assertEquals(BusinessCommand.TOGGLE, generic.commandFor(PlatformEvent.TOGGLE))
        assertEquals(BusinessCommand.IGNORE, generic.commandFor(PlatformEvent.NEXT))
        assertEquals(BusinessCommand.IGNORE, generic.commandFor(PlatformEvent.PREVIOUS))
        assertEquals(ControlProfile.GENERIC_ID, generic.profileId)
        assertEquals(1, generic.profileVersion)
    }

    @Test
    fun `映射里没有的平台事件当作 IGNORE：未知不是命令`() {
        val onlyPlay = ControlProfile("play-only", 1, mapOf(PlatformEvent.PLAY to BusinessCommand.START))

        assertEquals(BusinessCommand.IGNORE, onlyPlay.commandFor(PlatformEvent.TOGGLE))
        assertEquals(BusinessCommand.IGNORE, onlyPlay.commandFor(PlatformEvent.PAUSE))
    }

    @Test
    fun `音量变化只能映射到 STOP 或 IGNORE，不能用来开始录音`() {
        assertThrows(IllegalArgumentException::class.java) {
            ControlProfile("volume-start", 1, mapOf(PlatformEvent.VOLUME_CHANGE to BusinessCommand.START))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ControlProfile("volume-toggle", 1, mapOf(PlatformEvent.VOLUME_CHANGE to BusinessCommand.TOGGLE))
        }
        ControlProfile("volume-stop", 1, mapOf(PlatformEvent.VOLUME_CHANGE to BusinessCommand.STOP))
        ControlProfile("volume-ignore", 1, mapOf(PlatformEvent.VOLUME_CHANGE to BusinessCommand.IGNORE))
    }

    @Test
    fun `屏幕按钮不经过档案：映射里不能出现 screen_button`() {
        assertThrows(IllegalArgumentException::class.java) {
            ControlProfile("screen-in-profile", 1, mapOf(PlatformEvent.SCREEN_BUTTON to BusinessCommand.START))
        }
    }

    @Test
    fun `映射不能为空，编号只能是小写字母数字与连字符且不超过 100 字，版本至少为 1`() {
        val ok = mapOf(PlatformEvent.PLAY to BusinessCommand.START)

        assertThrows(IllegalArgumentException::class.java) { ControlProfile("empty-mapping", 1, emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) { ControlProfile("", 1, ok) }
        assertThrows(IllegalArgumentException::class.java) { ControlProfile("Upper-Case", 1, ok) }
        assertThrows(IllegalArgumentException::class.java) { ControlProfile("trailing-", 1, ok) }
        assertThrows(IllegalArgumentException::class.java) { ControlProfile("under_score", 1, ok) }
        assertThrows(IllegalArgumentException::class.java) { ControlProfile("a".repeat(101), 1, ok) }
        assertThrows(IllegalArgumentException::class.java) { ControlProfile("version-zero", 0, ok) }
        ControlProfile("a".repeat(100), 1, ok)
    }

    @Test
    fun `观察档案：编号与版本不变，每个已知事件都映射成 IGNORE，所以观察期间不会触发任何录音`() {
        val observing = ControlProfile.generic().observeOnly()

        assertEquals(ControlProfile.GENERIC_ID, observing.profileId)
        assertEquals(1, observing.profileVersion)
        PlatformEvent.entries.filter { it != PlatformEvent.SCREEN_BUTTON && it != PlatformEvent.VOLUME_CHANGE }.forEach {
            assertEquals("$it 在观察档案里必须被忽略", BusinessCommand.IGNORE, observing.commandFor(it))
        }
    }

    @Test
    fun `单事件切换映射：只改被证明的那个事件，只允许 play、pause、toggle`() {
        val toggled = ControlProfile.generic().withSingleEventToggle(PlatformEvent.PLAY)

        assertEquals(BusinessCommand.TOGGLE, toggled.commandFor(PlatformEvent.PLAY))
        assertEquals(BusinessCommand.STOP, toggled.commandFor(PlatformEvent.PAUSE))
        assertEquals(BusinessCommand.TOGGLE, toggled.commandFor(PlatformEvent.TOGGLE))

        assertThrows(IllegalArgumentException::class.java) { ControlProfile.generic().withSingleEventToggle(PlatformEvent.NEXT) }
        assertThrows(IllegalArgumentException::class.java) { ControlProfile.generic().withSingleEventToggle(PlatformEvent.VOLUME_CHANGE) }
    }
}
