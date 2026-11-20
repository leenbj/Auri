package app.auri.android.destinations

import app.auri.android.netguard.SourceText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 22 第 2 项的“凭证只显示一次”要靠一条结构上的事实撑着：凭证从网关响应到屏幕、到剪贴板，中间不经过任何会留下它的地方。
 * 这里扫发送与目的地相关的全部生产源码（去掉注释与字符串之后），逐条登记：
 *  1. 没有日志、打印、偏好设置、保存实例状态、Bundle / Parcelable / Serializable、文件写入——凭证不可能被它们带走；
 *  2. 读出凭证明文（`reveal()`）的地方只有两处：显示它的详情页、复制到剪贴板的接线；
 *  3. 界面里唯一的 rememberSaveable 只存“现在在哪一页”（一个字符串），不存别的；
 *  4. 日志只写事件名和异常类名：每一处 `log.event(...)` 里的插值都得是 `::class.simpleName`（或登记过的布尔）。
 * 新增一处就会让这个测试失败，逼人想清楚它会不会带走凭证（做法同 StorageLocationGuardTest）。
 */
class CredentialSurfaceGuardTest {
    private val androidDir = File("..")

    private fun kotlinFiles(vararg dirs: String): List<File> = dirs.map { File(androidDir, it) }.filter { it.isDirectory }.flatMap { dir ->
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun surface(): List<File> {
        val ui = listOf("DestinationsScreen", "DestinationDetailScreen", "DestinationsPage", "SendReviewScreen", "SecretWindow").map {
            File(androidDir, "app/src/main/kotlin/app/auri/android/ui/$it.kt")
        }
        return kotlinFiles(
            "app/src/main/kotlin/app/auri/android/destinations",
            "sync/src/main/kotlin/app/auri/sync/destinations",
            "syncplatform/src/main/kotlin/app/auri/syncplatform/auth",
            "syncplatform/src/main/kotlin/app/auri/syncplatform/dispatch",
        ) + ui
    }

    private fun File.rel() = invariantSeparatorsPath.removePrefix("../")

    /** 会把东西留在进程之外（或日志里）的 API。 */
    private val forbidden = listOf(
        "android.util.Log" to Regex("""\bandroid\.util\.Log\b|\bLog\.[a-z]\("""),
        "println / print" to Regex("""\bprintln\(|\bprint\("""),
        "System.out / System.err" to Regex("""System\.(out|err)\b"""),
        "printStackTrace" to Regex("""printStackTrace"""),
        "SharedPreferences / DataStore" to Regex("""SharedPreferences|getSharedPreferences|DataStore"""),
        "保存实例状态" to Regex("""SavedStateHandle|onSaveInstanceState|rememberSaveable"""),
        "Parcelable / Serializable / Bundle" to Regex("""\bParcelable\b|\bParcelize\b|\bSerializable\b|\bBundle\b"""),
        "文件写入" to Regex("""\bFile\(|FileOutputStream|FileWriter|\.writeText\(|\.writeBytes\(|openFileOutput|\bfilesDir\b|\bcacheDir\b|getExternal"""),
    )

    /** 登记过的例外：(文件, 禁用项, 这一行包含的片段, 为什么没问题)。 */
    private val registered = listOf(
        Triple("app/src/main/kotlin/app/auri/android/ui/DestinationsPage.kt", "保存实例状态", "rememberSaveable { mutableStateOf(LIST) }"),
        Triple("app/src/main/kotlin/app/auri/android/ui/DestinationsPage.kt", "保存实例状态", "import androidx.compose.runtime.saveable.rememberSaveable"),
    )

    @Test
    fun `发送与目的地相关的生产源码里，没有任何会带走凭证的 API，例外都登记了理由`() {
        val files = surface()
        assertTrue("应当扫到这些文件：${files.size}", files.size >= 15)

        val problems = files.flatMap { file ->
            val path = file.rel()
            SourceText.withoutCommentsAndStrings(file.readText()).lines().withIndex().flatMap { (index, line) ->
                forbidden.filter { (_, pattern) -> pattern.containsMatchIn(line) }
                    .filterNot { (name, _) -> registered.any { (p, n, part) -> path.endsWith(p) && n == name && line.contains(part) } }
                    .map { (name, _) -> "$path:${index + 1} [$name]: ${line.trim()}" }
            }
        }

        assertEquals("这些行用了会留下东西的 API：\n" + problems.joinToString("\n"), emptyList<String>(), problems)
    }

    @Test
    fun `读出凭证明文的地方只有两处：详情页的显示、复制到剪贴板的接线`() {
        val allowed = setOf(
            "app/src/main/kotlin/app/auri/android/ui/DestinationDetailScreen.kt",
            "app/src/main/kotlin/app/auri/android/ui/DestinationsPage.kt",
            // 目的地凭证类自己的定义（reveal 的声明；toString 是脱敏的）
            "sync/src/main/kotlin/app/auri/sync/gateway/DestinationModels.kt",
            // 以下两处是设备令牌（Ticket 21 的配对记录），不是目的地凭证：令牌类的声明，与加密存储里写入它的那一处
            "sync/src/main/kotlin/app/auri/sync/pairing/DeviceToken.kt",
            "sync/src/main/kotlin/app/auri/sync/pairing/FilePairingStore.kt",
        )
        val everything = kotlinFiles("app/src/main/kotlin", "sync/src/main/kotlin", "syncplatform/src/main/kotlin", "platform/src/main/kotlin", "capture/src/main/kotlin", "modelmgmt/src/main/kotlin")

        val callers = everything.filter { Regex("""\.reveal\(\)|\bfun reveal\(""").containsMatchIn(SourceText.withoutCommentsAndStrings(it.readText())) }.map { it.rel() }.toSet()

        assertEquals("读出凭证明文的文件应当只有登记过的", allowed, callers)
    }

    @Test
    fun `界面里唯一保存的状态是“现在在哪一页”，一个字符串`() {
        val page = File(androidDir, "app/src/main/kotlin/app/auri/android/ui/DestinationsPage.kt").readText()

        assertEquals(1, Regex("""rememberSaveable\s*\{""").findAll(page).count())
        assertTrue("存的是页面名（字符串常量）", page.contains("var route by rememberSaveable { mutableStateOf(LIST) }"))
        assertTrue(page.contains("""private const val LIST = "list""""))
    }

    @Test
    fun `应用退到后台时详情页抹掉凭证：页面登记了 ON_STOP 观察者，并且离开页面时摘掉`() {
        val page = File(androidDir, "app/src/main/kotlin/app/auri/android/ui/DestinationsPage.kt").readText()

        assertTrue(page.contains("event == Lifecycle.Event.ON_STOP) viewModel.backgrounded()"))
        assertTrue(page.contains("owner.lifecycle.addObserver(observer)"))
        assertTrue(page.contains("owner.lifecycle.removeObserver(observer)"))
    }

    /** 日志模板里允许的插值：异常/结果的类名、“是不是替换了旧凭证”的布尔、代码里写死的操作名（$what、$name 都来自字面量）。 */
    private val allowedInterpolation = Regex(
        "\\\$\\{[A-Za-z_.]+::class\\.simpleName\\}|\\\$\\{result\\.value\\.replacedPrevious\\}|\\\$what\\b|\\\$name\\b",
    )

    @Test
    fun `日志只写事件名与异常类名：每处 log event 的插值都是 class simpleName 或登记过的布尔`() {
        val problems = surface().flatMap { file ->
            val raw = file.readText()
            Regex("""\blog\.event\(\s*"([^"\n]*)"""").findAll(raw).mapNotNull { match ->
                val template = match.groupValues[1]
                val leftover = allowedInterpolation.replace(template, "")
                if (leftover.contains('$')) "${file.rel()}: log.event(\"$template\")" else null
            }.toList()
        }

        assertEquals("这些日志把不该写的东西插进去了：\n" + problems.joinToString("\n"), emptyList<String>(), problems)
        // 守卫自己有效：真的找到了不少 log.event 调用，不是一个也没扫到
        val calls = surface().sumOf { Regex("""\blog\.event\(""").findAll(it.readText()).count() }
        assertTrue("应当扫到 log.event 调用：$calls", calls >= 8)
    }

    @Test
    fun `守卫自己能抓到坏例子：带插值的日志、写文件、打日志都会被认出来`() {
        assertTrue(allowedInterpolation.replace("credential.issued \${credential.reveal()}", "").contains('$'))
        assertTrue(allowedInterpolation.replace("destination.create.crashed \${failure::class.simpleName}", "").contains('$').not())

        val bad = SourceText.withoutCommentsAndStrings("""Log.i("x", "y"); File(dir, "c").writeText(secret); val s = SavedStateHandle()""")
        val hits = forbidden.filter { (_, pattern) -> pattern.containsMatchIn(bad) }.map { it.first }
        assertEquals(setOf("android.util.Log", "文件写入", "保存实例状态"), hits.toSet())
    }
}
