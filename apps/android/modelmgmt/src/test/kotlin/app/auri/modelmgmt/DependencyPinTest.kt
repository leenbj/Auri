package app.auri.modelmgmt

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 依赖锁定（PRD 15.4）：模型包 .tar.bz2 的解包用到三个库，按“文件名 + SHA-256”固定；升级任何一个都必须有意识地改这里，
 * 并重读它的变更说明（解压库历来是安全修复集中的地方：路径穿越、压缩炸弹、畸形头）。
 *
 * 哈希取自 2026-11-18 从 Maven Central 直接下载的 jar，并与 Gradle 实际解析到、测试里实际加载的字节核对一致。
 * 这里读的是“类实际是从哪个 jar 加载的”，而不是类路径字符串，所以换了版本、换了来源、被别的依赖顶替都会被发现。
 */
class DependencyPinTest {
    private val pins = mapOf(
        "commons-compress-1.28.0.jar" to Pin(
            "org.apache.commons.compress.archivers.tar.TarArchiveInputStream",
            "e1522945218456f3649a39bc4afd70ce4bd466221519dba7d378f2141a4642ca",
        ),
        "commons-io-2.20.0.jar" to Pin(
            "org.apache.commons.io.IOUtils",
            "df90bba0fe3cb586b7f164e78fe8f8f4da3f2dd5c27fa645f888100ccc25dd72",
        ),
        "commons-lang3-3.18.0.jar" to Pin(
            "org.apache.commons.lang3.StringUtils",
            "4eeeae8d20c078abb64b015ec158add383ac581571cddc45c68f0c9ae0230720",
        ),
        "okhttp-4.12.0.jar" to Pin(
            "okhttp3.OkHttpClient",
            "b1050081b14bb7a3a7e55a4d3ef01b5dcfabc453b4573a4fc019767191d5f4e0",
        ),
        "okio-jvm-3.6.0.jar" to Pin(
            "okio.Buffer",
            "67543f0736fc422ae927ed0e504b98bc5e269fda0d3500579337cb713da28412",
        ),
    )

    private class Pin(val className: String, val sha256: String)

    private fun jarOf(className: String): File {
        val location = Class.forName(className).protectionDomain.codeSource.location
        return File(location.toURI())
    }

    @Test
    fun `解包用的三个库是固定的文件名与 SHA-256，不是别的版本`() {
        for ((name, pin) in pins) {
            val jar = jarOf(pin.className)
            assertEquals("${pin.className} 加载自 ${jar.name}", name, jar.name)
            assertTrue("$name 应当是普通文件：$jar", jar.isFile)
            assertEquals("$name 的内容与固定的 SHA-256 不一致", pin.sha256, sha256Hex(jar))
        }
    }

    @Test
    fun `固定值本身是 64 位小写十六进制，没有占位符`() {
        val hex = Regex("^[0-9a-f]{64}$")
        for ((name, pin) in pins) assertTrue("$name 的固定哈希格式不对", hex.matches(pin.sha256))
        assertEquals("三个固定哈希互不相同", pins.size, pins.values.map { it.sha256 }.toSet().size)
    }
}
