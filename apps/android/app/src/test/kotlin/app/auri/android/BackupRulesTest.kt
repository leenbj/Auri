package app.auri.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * 录音、文字、配对令牌不能离开这台手机（FR-TXT、SEC 的隐私授权分离：上传到别处必须是用户显式的、有授权的动作）。
 * `allowBackup="false"` 只关掉了云备份；Android 12+ 的“换机迁移”（device-to-device transfer）按 dataExtractionRules 走，
 * Android 11 及以下的备份按 fullBackupContent 走。所以三者必须同时在，并且规则把**每一个**域整个排除、没有任何 include。
 * 规则文件本身是资源，运行时才有人读它，所以在这里把它当数据检查；检查函数自己也有正反例（规则被改坏时必须报出来）。
 */
class BackupRulesTest {
    private val xmlDir = File("src/main/res/xml")

    // ---- 真实的清单与规则 ----

    @Test
    fun `合并后的清单：allowBackup 关闭，并且同时声明换机迁移规则与旧版备份规则`() {
        val application = MergedManifest.application()

        assertEquals("false", application.getAttribute("android:allowBackup"))
        assertEquals("Android 12+ 的换机迁移规则", "@xml/data_extraction_rules", application.getAttribute("android:dataExtractionRules"))
        assertEquals("Android 11 及以下的备份规则", "@xml/backup_rules", application.getAttribute("android:fullBackupContent"))
    }

    @Test
    fun `换机迁移规则：云备份与设备到设备迁移两处都把每个域整个排除`() {
        val problems = BackupRules.problems(File(xmlDir, "data_extraction_rules.xml").readText())

        assertEquals(problems.joinToString("\n"), emptyList<String>(), problems)
    }

    @Test
    fun `旧版备份规则：每个域整个排除`() {
        val problems = BackupRules.problems(File(xmlDir, "backup_rules.xml").readText())

        assertEquals(problems.joinToString("\n"), emptyList<String>(), problems)
    }

    // ---- 检查函数自己：坏规则必须被报出来 ----

    private fun extraction(cloud: String = domains(BackupRules.EXTRACTION_DOMAINS), transfer: String = domains(BackupRules.EXTRACTION_DOMAINS)) =
        "<data-extraction-rules><cloud-backup>$cloud</cloud-backup><device-transfer>$transfer</device-transfer></data-extraction-rules>"

    private fun domains(list: List<String>, path: String = ".") = list.joinToString("") { """<exclude domain="$it" path="$path"/>""" }

    @Test
    fun `检查函数认得出合规的两种规则`() {
        assertEquals(emptyList<String>(), BackupRules.problems(extraction()))
        assertEquals(emptyList<String>(), BackupRules.problems("<full-backup-content>${domains(BackupRules.FULL_DOMAINS)}</full-backup-content>"))
    }

    @Test
    fun `检查函数认得出缺了设备迁移那一节`() {
        val problems = BackupRules.problems("<data-extraction-rules><cloud-backup>${domains(BackupRules.EXTRACTION_DOMAINS)}</cloud-backup></data-extraction-rules>")

        assertTrue(problems.toString(), problems.any { "device-transfer" in it })
    }

    @Test
    fun `检查函数认得出漏掉某个域（数据库、外部存储……）`() {
        for (missing in BackupRules.EXTRACTION_DOMAINS) {
            val problems = BackupRules.problems(extraction(cloud = domains(BackupRules.EXTRACTION_DOMAINS - missing)))
            assertTrue("漏了 $missing：$problems", problems.any { "cloud-backup" in it && missing in it })
        }
        val full = BackupRules.problems("<full-backup-content>${domains(BackupRules.FULL_DOMAINS - "database")}</full-backup-content>")
        assertTrue(full.toString(), full.any { "database" in it })
    }

    @Test
    fun `检查函数认得出只排除了一个子目录而不是整个域，以及任何 include`() {
        val partial = BackupRules.problems(extraction(transfer = domains(BackupRules.EXTRACTION_DOMAINS, path = "cache")))
        assertTrue(partial.toString(), partial.any { "device-transfer" in it })

        val included = BackupRules.problems(extraction(cloud = domains(BackupRules.EXTRACTION_DOMAINS) + """<include domain="file" path="keep"/>"""))
        assertTrue(included.toString(), included.any { "include" in it })
    }

    @Test
    fun `检查函数认不出的根元素与读不懂的内容都算有问题，不悄悄放行`() {
        assertTrue(BackupRules.problems("<something-else/>").isNotEmpty())
        assertTrue(runCatching { BackupRules.problems("not xml at all") }.isFailure)
    }
}

/** 备份规则的检查器：每个域都要用 path="." 整个排除，不许有 include。 */
internal object BackupRules {
    val FULL_DOMAINS = listOf("root", "file", "database", "sharedpref", "external")
    val EXTRACTION_DOMAINS = FULL_DOMAINS + listOf("device_root", "device_file", "device_database", "device_sharedpref")

    fun problems(xml: String): List<String> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream()).documentElement
        return when (root.tagName) {
            "data-extraction-rules" -> listOf("cloud-backup", "device-transfer").flatMap { section ->
                val element = root.children(section).singleOrNull()
                if (element == null) listOf("缺少 <$section> 一节") else check(section, element, EXTRACTION_DOMAINS)
            }
            "full-backup-content" -> check("full-backup-content", root, FULL_DOMAINS)
            else -> listOf("不认识的根元素 <${root.tagName}>")
        }
    }

    private fun check(section: String, element: Element, required: List<String>): List<String> {
        val problems = mutableListOf<String>()
        if (element.children("include").isNotEmpty()) problems += "$section：不许有 include（它会把排除的数据重新放进备份）"
        val excluded = element.children("exclude").filter { it.getAttribute("path") == "." }.map { it.getAttribute("domain") }.toSet()
        for (domain in required) if (domain !in excluded) problems += "$section：没有用 path=\".\" 把 $domain 域整个排除"
        return problems
    }

    private fun Element.children(tag: String): List<Element> = childNodes.let { nodes ->
        (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { it.tagName == tag }
    }
}
