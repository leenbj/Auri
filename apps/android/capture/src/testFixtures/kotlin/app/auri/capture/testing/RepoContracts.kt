package app.auri.capture.testing

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * 测试直接读仓库 `contracts/` 目录下的原始文件，不用拷贝（Ticket 16 第 2 项：读不到就失败，不悄悄跳过）。
 * 仓库根目录由 Gradle 经系统属性 `auri.repo.root` 传入（见 capture/build.gradle.kts）。
 */
object RepoContracts {
    val repoRoot: File by lazy {
        val path = System.getProperty("auri.repo.root") ?: error("没有系统属性 auri.repo.root：测试必须经 Gradle 运行")
        File(path).also { check(it.resolve("contracts/schemas").isDirectory) { "$it 不是仓库根目录（找不到 contracts/schemas）" } }
    }

    val contractsDir: File get() = repoRoot.resolve("contracts")

    fun file(relativePath: String): File =
        contractsDir.resolve(relativePath).also { check(it.isFile) { "合同文件不存在：$it" } }

    fun json(relativePath: String): JsonElement = Json.parseToJsonElement(file(relativePath).readText(Charsets.UTF_8))
}
