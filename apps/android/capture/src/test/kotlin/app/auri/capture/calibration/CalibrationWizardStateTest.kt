package app.auri.capture.calibration

import app.auri.capture.calibration.CalibrationPhase.ABORTED
import app.auri.capture.calibration.CalibrationPhase.COMPLETED
import app.auri.capture.calibration.CalibrationPhase.IN_PROGRESS
import app.auri.capture.calibration.CalibrationPhase.NOT_STARTED
import app.auri.capture.calibration.CalibrationStep.GESTURE_WHILE_RECORDING
import app.auri.capture.calibration.CalibrationStep.IDLE_EVENTS
import app.auri.capture.calibration.CalibrationStep.RESTART_AFTER_STOP
import app.auri.capture.calibration.CalibrationStep.SCREEN_LOCKED
import app.auri.capture.calibration.StepOutcome.DONE
import app.auri.capture.calibration.StepOutcome.PENDING
import app.auri.capture.calibration.StepOutcome.SKIPPED
import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.PlatformEvent
import app.auri.capture.control.ControlProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校准向导的状态机：四个场景按固定顺序走，每一步都可以“完成 / 跳过 / 重做”，任何时候都可以放弃。
 * 为什么要逐步测：向导的结论要写进兼容档案，走错一步（跳过算完成、重做没清掉旧观察、放弃后还能出档案）
 * 就会把没做过的事记成做过。
 */
class CalibrationWizardStateTest {
    private val order = listOf(IDLE_EVENTS, GESTURE_WHILE_RECORDING, RESTART_AFTER_STOP, SCREEN_LOCKED)

    private fun wizard() = CalibrationWizard("run-1", EvidenceSetting.UNIT_TEST)

    /** 走到第 [index] 个场景（从 0 开始）。 */
    private fun wizardAt(index: Int): CalibrationWizard = wizard().also { w ->
        assertTrue(w.start())
        repeat(index) { assertTrue(w.next()) }
    }

    private fun CalibrationWizard.snapshot() = state.value

    @Test
    fun `没开始时什么都不能做：只有开始有效，也出不了档案`() {
        val w = wizard()
        assertEquals(NOT_STARTED, w.snapshot().phase)
        assertNull(w.snapshot().step)
        assertFalse(w.next())
        assertFalse(w.skip())
        assertFalse(w.repeat())
        assertFalse(w.reportNoEffect())
        assertFalse(w.abort())
        assertNull(w.controlProfile())
        assertNull(w.buildProfile("android-headset", 1, null))
        assertEquals(order.associateWith { PENDING }, w.snapshot().stepOutcomes)
    }

    @Test
    fun `开始后停在第一个场景，第一次尝试；再开始一次无效`() {
        val w = wizard()
        assertTrue(w.start())
        assertEquals(IN_PROGRESS, w.snapshot().phase)
        assertEquals(IDLE_EVENTS, w.snapshot().step)
        assertEquals(1, w.snapshot().attempt)
        assertFalse("一个向导只跑一轮，重新校准要新建", w.start())
    }

    @Test
    fun `四个场景按固定顺序走，每步完成后记为 DONE，走完是 COMPLETED`() {
        val w = wizard()
        w.start()
        order.forEachIndexed { index, step ->
            assertEquals(step, w.snapshot().step)
            assertEquals(order.take(index).associateWith { DONE } + order.drop(index).associateWith { PENDING }, w.snapshot().stepOutcomes)
            assertTrue(w.next())
        }
        assertEquals(COMPLETED, w.snapshot().phase)
        assertNull(w.snapshot().step)
        assertEquals(order.associateWith { DONE }, w.snapshot().stepOutcomes)
        assertNotNull(w.buildProfile("android-headset", 1, null))
    }

    @Test
    fun `每一步都能跳过：记为 SKIPPED 并进入下一步，最后一步跳过则完成`() {
        order.forEachIndexed { index, step ->
            val w = wizardAt(index)
            assertEquals(step, w.snapshot().step)
            assertTrue(w.skip())
            assertEquals("$step 被跳过", SKIPPED, w.snapshot().stepOutcomes.getValue(step))
            if (index < order.lastIndex) {
                assertEquals(IN_PROGRESS, w.snapshot().phase)
                assertEquals(order[index + 1], w.snapshot().step)
                assertEquals("下一步从第一次尝试开始", 1, w.snapshot().attempt)
            } else {
                assertEquals(COMPLETED, w.snapshot().phase)
            }
        }
    }

    @Test
    fun `每一步都能重做：留在原地，尝试次数加一，不改变其他步骤的结果`() {
        order.forEachIndexed { index, step ->
            val w = wizardAt(index)
            val before = w.snapshot().stepOutcomes
            assertTrue(w.repeat())
            assertTrue(w.repeat())
            assertEquals(IN_PROGRESS, w.snapshot().phase)
            assertEquals(step, w.snapshot().step)
            assertEquals(3, w.snapshot().attempt)
            assertEquals(before, w.snapshot().stepOutcomes)
        }
    }

    @Test
    fun `每一步都能放弃：之后没有档案，也不能再操作`() {
        order.forEachIndexed { index, _ ->
            val w = wizardAt(index)
            assertTrue(w.abort())
            assertEquals(ABORTED, w.snapshot().phase)
            assertNull(w.snapshot().step)
            assertNull("放弃的校准不出档案", w.buildProfile("android-headset", 1, null))
            assertNull(w.controlProfile())
            assertFalse(w.next())
            assertFalse(w.skip())
            assertFalse(w.repeat())
            assertFalse(w.reportNoEffect())
            assertFalse(w.abort())
            assertFalse(w.start())
        }
    }

    @Test
    fun `完成之后不能再动，输入也不再被记录`() {
        val w = wizardAt(0)
        repeat(4) { w.next() }
        assertEquals(COMPLETED, w.snapshot().phase)
        assertFalse(w.next())
        assertFalse(w.skip())
        assertFalse(w.repeat())
        assertFalse(w.reportNoEffect())
        assertFalse(w.abort())
        assertFalse(w.start())
    }

    @Test
    fun `“没起作用”只适用于第二到第四个场景；第一个场景只观察事件`() {
        assertFalse(wizardAt(0).reportNoEffect())
        listOf(1, 2, 3).forEach { index ->
            val w = wizardAt(index)
            assertTrue(w.reportNoEffect())
            assertTrue(w.snapshot().summary.ownerReportedNoEffect)
        }
    }

    @Test
    fun `重做会清掉这一步的声明；换到下一步也不带过去`() {
        val w = wizardAt(1)
        w.reportNoEffect()
        assertTrue(w.snapshot().summary.ownerReportedNoEffect)
        w.repeat()
        assertFalse("重做 = 当作没发生过", w.snapshot().summary.ownerReportedNoEffect)
        w.reportNoEffect()
        w.next()
        assertEquals(RESTART_AFTER_STOP, w.snapshot().step)
        assertFalse(w.snapshot().summary.ownerReportedNoEffect)
    }

    @Test
    fun `校准期间的临时映射：第一步只观察，其余步骤任何播放类事件都当作切换`() {
        assertEquals(ControlProfile.generic().observeOnly().mapping, wizardAt(0).controlProfile()!!.mapping)
        listOf(1, 2, 3).forEach { index ->
            val mapping = wizardAt(index).controlProfile()!!
            listOf(PlatformEvent.PLAY, PlatformEvent.PAUSE, PlatformEvent.TOGGLE).forEach {
                assertEquals("第 ${index + 1} 步 $it", BusinessCommand.TOGGLE, mapping.commandFor(it))
            }
            listOf(PlatformEvent.NEXT, PlatformEvent.PREVIOUS).forEach {
                assertEquals(BusinessCommand.IGNORE, mapping.commandFor(it))
            }
        }
    }

    @Test
    fun `没有观察的整轮校准：每一步都完成，但没有任何能力被测试`() {
        val w = wizardAt(0)
        repeat(4) { w.next() }
        val profile = w.buildProfile("android-headset", 1, null)!!
        assertEquals(List(5) { CompatStatus.NOT_TESTED }, profile.capabilities.all.map { it.status })
        assertEquals(List(5) { null }, profile.capabilities.all.map { it.evidenceLevel })
        assertEquals(List(5) { emptyList<String>() }, profile.capabilities.all.map { it.evidenceIds })
    }
}
