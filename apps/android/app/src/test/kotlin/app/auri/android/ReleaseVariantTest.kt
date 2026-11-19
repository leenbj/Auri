package app.auri.android

import app.auri.android.netguard.NetworkSurface
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评审 F14：“粘贴配对载荷”只是没有摄像头时的调试入口（模拟器用）。原先它写在 src/main，只靠运行时的 debuggable 标志藏起来——
 * 也就是说 release 包里仍然带着这段界面、这段文案和这个视图模型入口；标志一旦判错（或被改包工具改掉 debuggable），
 * 用户就能在 release 版里粘一段文字去配对。现在它只在 src/debug 里有真实现，release 版是个什么都不画的空实现。
 *
 * 这里不信源码、不信运行时标志，直接读两个变体**编译出来的类**的常量池（和网络卫士同一套读法）：
 * release 的类里不能出现入口的任何痕迹；debug 的类里要有它（对照：证明这里没有读漏，也证明调试入口还在）。
 * release 的类由 Gradle 在测试之前编译（app/build.gradle.kts 里 testDebugUnitTest 依赖 compileReleaseKotlin）。
 *
 * 限制：看的是 :app 模块自己的类；最终 APK 的 dex 由验收 README 里的 assembleRelease 命令再核对一次（手动，没有进自动测试）。
 */
class ReleaseVariantTest {
    /** 界面上的字：它们只会出现在真正的手动输入界面里。 */
    private val interfaceText = listOf("用文字配对", "配对载荷", "调试版：没有摄像头")

    /** 入口在代码里的名字：运行时开关、视图模型入口、原先写在 main 里的那个界面函数。 */
    private val entryNames = listOf("onManualPayload", "manualEntryAvailable", "ManualPayloadEntry")

    private fun classes(variant: String): Map<String, List<String>> {
        val build = File("..", "app/build")
        val roots = listOf(
            File(build, "tmp/kotlin-classes/$variant"),
            File(build, "intermediates/javac/$variant/compile${variant.replaceFirstChar { it.uppercase() }}JavaWithJavac/classes"),
        ).filter { it.isDirectory }
        assertTrue("没有找到 $variant 版编译出来的类：$build（release 的由 Gradle 在测试前编译）", roots.isNotEmpty())
        return roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "class" }.map {
                it.relativeTo(root).invariantSeparatorsPath to NetworkSurface.constants(it.readBytes())
            }.toList()
        }.toMap().also { assertTrue("$variant 版里一个类都没有", it.isNotEmpty()) }
    }

    private fun hits(classes: Map<String, List<String>>, markers: List<String>): List<String> =
        classes.flatMap { (name, constants) ->
            markers.filter { marker -> constants.any { it.contains(marker) } }.map { marker -> "$name 含 $marker" }
        }

    @Test
    fun `release 版编译出的类里没有手动输入配对载荷的入口：界面、文案、视图模型入口都不在`() {
        val found = hits(classes("release"), interfaceText + entryNames)

        assertEquals("release 版里不能有调试入口的任何痕迹", emptyList<String>(), found)
    }

    @Test
    fun `对照：debug 版的类里有手动输入界面，扫描没有读漏`() {
        val debug = classes("debug")

        for (text in interfaceText) {
            assertTrue("debug 版里应当有界面文字「$text」，否则扫描方法本身是瞎的", hits(debug, listOf(text)).isNotEmpty())
        }
    }

    @Test
    fun `运行时开关与视图模型入口已经整个删掉：debug 版里也没有，调试入口只是 src debug 里的一个界面`() {
        val found = hits(classes("debug"), entryNames)

        assertEquals("入口不再靠运行时标志藏着：名字在 debug 版里也不应该出现", emptyList<String>(), found)
    }
}
