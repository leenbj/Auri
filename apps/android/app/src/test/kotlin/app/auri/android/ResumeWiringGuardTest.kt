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
}
