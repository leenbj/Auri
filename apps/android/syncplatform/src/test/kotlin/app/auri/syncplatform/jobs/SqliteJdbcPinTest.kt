package app.auri.syncplatform.jobs

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 依赖锁定（PRD 15.4）：迁移测试用的 SQLite 引擎 sqlite-jdbc 按“文件名 + SHA-256”固定，且只出现在 JVM 单元测试类路径上。
 *
 * 哈希取自 2026-11-19 从 Maven Central 直接下载的 jar（它的 .sha1 与 Maven Central 发布的一致，
 * 本地算出的 SHA-256 写在下面）。读的是“驱动类实际是从哪个 jar 加载的”，换了版本、换了来源都会被发现。
 * 升级它之前先读它的变更说明：它自带原生库（SQLite 本体），这里只当测试用，不进 APK。
 */
class SqliteJdbcPinTest {
    private val jarName = "sqlite-jdbc-3.46.1.3.jar"
    private val sha256 = "4a4832720a65eaf7f4d6fd7ede52087b994dc5633c076f9e994dc0c8b4b0b4fa"

    private fun loadedJar(): File =
        File(Class.forName("org.sqlite.JDBC").protectionDomain.codeSource.location.toURI())

    private fun sha256Hex(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun `迁移测试用的 SQLite 引擎是固定的文件名与 SHA-256，不是别的版本`() {
        val jar = loadedJar()
        assertEquals("org.sqlite.JDBC 加载自 ${jar.name}", jarName, jar.name)
        assertTrue("应当是普通文件：$jar", jar.isFile)
        assertEquals("$jarName 的内容与固定的 SHA-256 不一致", sha256, sha256Hex(jar))
    }

    @Test
    fun `固定值本身是 64 位小写十六进制，没有占位符`() {
        assertTrue(Regex("^[0-9a-f]{64}$").matches(sha256))
    }
}
