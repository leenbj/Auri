package app.auri.android

import app.auri.android.netguard.SourceText
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第四轮 F4 的接线守卫：规划器在门的锁里按磁盘上的账现算（ResumeStaleSnapshotTest 用假账摆过每种“快照过时”的情形），
 * 而 SyncRuntime 里怎么把真的账接给它，只有读源码才看得到。没接上，规划器退回“流里的快照 + 实时的欠不欠追加”的老拼法，
 * 用户恰在规划器等门的当口确认预览时，叫醒会被过时的“没活了”取消，进程随后被杀，确认过的文字就没人再来发。
 *
 * 钉三处：规划器拿到现算（currentNeed）；现算读的是配对、任务表、授权、发送账四份实时的账，并交给 needsResume；被叫醒那一轮的收尾判断用的是同一个现算。
 * 做法同 ResumeWiringGuardTest：读生产源码（去掉注释与字符串字面量）。
 */
class ResumeCurrentNeedWiringTest {
    private val runtime = SourceText.withoutCommentsAndStrings(File("../syncplatform/src/main/kotlin/app/auri/syncplatform/SyncRuntime.kt").readText())
        .replace(Regex("""\s+"""), " ")

    private val plannerCall = runtime.substringAfter("ResumePlanner(").substringBefore("private val resumeRunner")
    private val runnerCall = runtime.substringAfter("private val resumeRunner = ResumeRunner(").substringBefore("private suspend fun resumeNeededNow")
    private val neededNow = runtime.substringAfter("private suspend fun resumeNeededNow(): Boolean {").substringBefore("override val status")

    @Test
    fun `叫醒规划器拿到的是现算：在门的锁里读账，不用流里带来的快照`() {
        assertTrue("应当找到规划器的构造：$plannerCall", plannerCall.contains("pairing.states") && plannerCall.contains("gate = wakes"))
        assertTrue("规划器要拿现算（resumeNeededNow）：$plannerCall", "currentNeed = { resumeNeededNow() }" in plannerCall)
    }

    @Test
    fun `现算读四份实时的账（配对、任务表、授权、发送账）并交给 needsResume，不缓存`() {
        assertTrue("应当找到现算的函数体：$neededNow", neededNow.isNotBlank() && "needsResume(" in neededNow)
        assertTrue("读配对记录：$neededNow", "pairing.load()" in neededNow)
        assertTrue("读任务表：$neededNow", "jobs.all()" in neededNow)
        assertTrue("读这个网关的授权：$neededNow", "policy.current(" in neededNow)
        assertTrue("读发送账欠不欠追加：$neededNow", "dispatch.due().isNotEmpty()" in neededNow)
    }

    @Test
    fun `被叫醒的那一轮的收尾判断与规划器用同一个现算`() {
        assertTrue("应当找到运行器的构造：$runnerCall", runnerCall.contains("ready =") && runnerCall.contains("runPass ="))
        assertTrue("收尾判断是 wakes.finishing { resumeNeededNow() }：$runnerCall", "stillNeeded = { wakes.finishing { resumeNeededNow() } }" in runnerCall)
    }
}
