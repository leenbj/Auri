package app.auri.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评审 H1：叫醒规划器知不知道“账上还欠不欠追加”，只取决于 SyncRuntime 里的这一处接线。没接上，规划器默认“不欠”，
 * 用户确认预览之后进程被杀就再没有叫醒——而 ResumePlannerTest / ResumeRecoveryTest 都是自己拼规划器，看不到这里。
 * 这里读源码，要求接的是发送账本身：欠不欠是实时读（due），发送账一变就重新评估（changes）。
 */
class ResumeWiringGuardTest {
    private val runtime = File("../syncplatform/src/main/kotlin/app/auri/syncplatform/SyncRuntime.kt").readText()

    private val plannerCall = runtime.substringAfter("ResumePlanner(").substringBefore("private val resumeRunner")

    @Test
    fun `叫醒规划器的“欠不欠追加”读的是发送账：实时读 due，发送账一变就重新评估`() {
        assertTrue("应当找到规划器的构造：$plannerCall", plannerCall.contains("pairing.states") && plannerCall.contains("jobs.observe()"))
        assertTrue("欠不欠追加要实时读发送账", Regex("""additionsOwed\s*=\s*\{\s*dispatch\.due\(\)\.isNotEmpty\(\)\s*\}""").containsMatchIn(plannerCall))
        assertTrue("发送账一变要重新评估", Regex("""additionsChanged\s*=\s*dispatch\.changes\(\)""").containsMatchIn(plannerCall))
    }

    /**
     * 复评 R12：用户确认预览的那条路，和叫醒规划器，必须是同一道门（同一把锁）——确认先登记叫醒、再提交，规划器手里过时的“没活了”才抹不掉它。
     * 各用一道门（或者根本没套壳）都能通过 WakeGate 自己的测试，却白白失去保护；这里读源码钉住接线。
     */
    @Test
    fun `确认预览的路与叫醒规划器共用同一道门：发送账外面套着先登记叫醒的壳，规划器拿的是同一个门`() {
        assertTrue("门只造一个", Regex("""private val wakes = WakeGate\(resume,""").containsMatchIn(runtime))
        assertTrue("界面、引擎与状态汇总用的发送账是套了壳的那一份", Regex("""val dispatch: DispatchStore = WakeAheadDispatchStore\(SqliteDispatchStore\(library\), wakes\)""").containsMatchIn(runtime))
        assertTrue("规划器与壳共用这一道门", Regex("""gate\s*=\s*wakes""").containsMatchIn(plannerCall))
    }

    /**
     * 再评 S8：被叫醒的那一轮收尾时“还要不要再叫醒”必须在门的锁里现算（WakeGate.finishing）：收尾判断与确认的“登记 + 提交”串行，没活时同步取消。
     * 自己拼运行器的测试（ResumeRecoveryTest）看不到 SyncRuntime 里这一处接线；没包进去，旧的缝就回来了——
     * 判断“没活了”之后、jobFinished 生效之前用户确认的预览，会因为系统里“还有一个”而没登记叫醒，随后叫醒被这一轮自己带走。
     */
    @Test
    fun `被叫醒的那一轮的收尾判断经过门：在门的锁里现算，不是在门外读一下就下结论`() {
        val runnerCall = runtime.substringAfter("private val resumeRunner = ResumeRunner(").substringBefore("override val status")

        assertTrue("应当找到运行器的构造：$runnerCall", runnerCall.contains("ready =") && runnerCall.contains("runPass ="))
        assertTrue("收尾判断要包在 wakes.finishing 里：$runnerCall", Regex("""stillNeeded\s*=\s*\{\s*wakes\.finishing\s*\{""").containsMatchIn(runnerCall))
        assertTrue("现算用的是同一个判断，读的是实时的账", runnerCall.contains("needsResume(") && runnerCall.contains("dispatch.due().isNotEmpty()"))
    }
}
