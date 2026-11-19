package app.auri.android

import app.auri.android.netguard.SourceText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会被备份的目录（filesDir、databases、sharedpref、外部存储）里不能有敏感文件：配对记录里的设备令牌、录音、耳机配置。
 * 它们要经 NoBackupStorage.place 放进 noBackupFilesDir。这里扫生产源码里所有提到 filesDir 的地方，
 * 每一处要么是 place 的“旧位置”实参，要么是登记过的非敏感用途；新增一处就会让这个测试失败，逼人想清楚这个文件敏不敏感。
 * 数据库没有挪（挪 WAL/SHM 要原子地搬三个文件）：由备份规则整域排除（BackupRulesTest）。
 */
class StorageLocationGuardTest {
    private val androidDir = File("..")

    /** 登记过的 filesDir 用途：(文件路径包含的片段, 这一行包含的片段, 为什么不敏感)。 */
    private val registered = listOf(
        Triple("app/src/main/kotlin/app/auri/android/AuriRuntime.kt", "modelsDir: File = File(application.filesDir", "Ticket 14 的调试布局默认值，产品转录不读它（模型权重不敏感）"),
        Triple("app/src/main/kotlin/app/auri/android/AuriRuntime.kt", "modelStoreRoot: File = application.filesDir", "模型管理器的根目录：下载的模型权重（公开文件，不敏感）"),
        Triple("platform/src/main/kotlin/app/auri/platform/system/AndroidResourceMonitor.kt", "context.applicationContext.filesDir", "只量剩余空间，不存任何东西"),
        Triple("app/src/main/kotlin/app/auri/android/AuriRuntime.kt", "DurationLimitSetting(File(application.filesDir", "录音时长上限（Ticket 18）：只有一个分钟数，没有音频、文字、设备标识或凭证"),
    )

    private fun productionSources(): List<File> = listOf("capture", "asr", "platform", "app", "sync", "syncplatform", "modelmgmt").flatMap { module ->
        listOf("main", "release").map { File(androidDir, "$module/src/$it") }
    }.filter { it.isDirectory }.flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList() }

    @Test
    fun `生产源码里每一处 filesDir 要么是 NoBackupStorage 的旧位置实参，要么是登记过的非敏感用途`() {
        val files = productionSources()
        assertTrue("应当扫到全部生产源文件：${files.size}", files.size > 100)

        val problems = files.flatMap { file ->
            val path = file.invariantSeparatorsPath
            SourceText.withoutCommentsAndStrings(file.readText()).lines().withIndex()
                .filter { (_, line) -> line.contains("filesDir", ignoreCase = true) || line.contains("getFilesDir") }
                .filterNot { (_, line) -> line.contains("NoBackupStorage.place(") }
                .filterNot { (_, line) -> registered.any { (pathPart, linePart, _) -> path.endsWith(pathPart) && line.contains(linePart) } }
                .map { (index, line) -> "$path:${index + 1}: ${line.trim()}" }
        }

        assertEquals(
            "这些行把东西放进了 filesDir（会被备份）。敏感文件改用 NoBackupStorage.place(noBackupFilesDir, filesDir, 名字)；" +
                "确实不敏感的在 registered 里登记并写明理由：\n" + problems.joinToString("\n"),
            emptyList<String>(),
            problems,
        )
    }

    @Test
    fun `三个敏感文件都经 NoBackupStorage 放置：配对记录、录音、耳机配置`() {
        val runtime = File(androidDir, "app/src/main/kotlin/app/auri/android/AuriRuntime.kt").readText()
        val sync = File(androidDir, "syncplatform/src/main/kotlin/app/auri/syncplatform/SyncRuntime.kt").readText()

        assertTrue("录音", Regex("""recordingsDir: File = NoBackupStorage\.place\(application\.noBackupFilesDir, application\.filesDir, "recordings"\)""").containsMatchIn(runtime))
        assertTrue("耳机配置", Regex("""FileProfileStore\(NoBackupStorage\.place\(application\.noBackupFilesDir, application\.filesDir, PROFILE_FILE_NAME\)\)""").containsMatchIn(runtime))
        assertTrue("配对记录", Regex("""pairingFile = NoBackupStorage\.place\(application\.noBackupFilesDir, application\.filesDir, PAIRING_FILE\)""").containsMatchIn(sync))
    }
}
