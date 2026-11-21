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
}
