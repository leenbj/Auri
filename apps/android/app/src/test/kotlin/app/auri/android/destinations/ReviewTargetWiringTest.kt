package app.auri.android.destinations

import app.auri.android.netguard.SourceText
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复评 R10 的接线：视图模型把网关投递与拒收原因交出来（SendReviewViewModelTest 摆过行为），界面的 Composable 在 JVM 里跑不了，
 * 这里钉住“待发送页给每个目标写的那句话，用的是带着这两样的 describeDecision”——否则界面又会对着一个被拒收的目标写“等你确认”。
 * 做法同 ClipboardWiringTest：读生产源码（去掉注释与字符串字面量）。
 */
class ReviewTargetWiringTest {
    private val androidDir = File("..")

    private fun code(path: String) = SourceText.withoutCommentsAndStrings(File(androidDir, "app/src/main/kotlin/app/auri/android/$path").readText()).replace(Regex("""\s+"""), " ")

    @Test
    fun `待发送页给每个目标写的那句话带上网关投递与拒收原因，确认与不发送两个按钮只在账本认为在等你决定时才有`() {
        val screen = code("ui/SendReviewScreen.kt")

        assertTrue("说法要带上 delivery 与 refusal：$screen", "describeDecision(target.decision, target.eligible, target.delivery, target.refusal)" in screen)
        assertTrue("按钮只看 awaitingUser（它已经把拒收算进去了）", "if (target.awaitingUser) {" in screen)
    }
}
