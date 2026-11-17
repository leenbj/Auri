import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// ---------------------------------------------------------------------------------------------
// 固定的本地转录引擎：sherpa-onnx 预编译 AAR（内含 ONNX Runtime）。
// 版本、来源与 SHA-256 记录在 engine/sherpa-onnx.properties。构建只接受这个哈希对应的字节：
//   fetchSherpaAar   缓存里没有时，从固定的 https 地址下载到仓库外的缓存目录（先写 .part，哈希对了才改名）。
//   verifySherpaAar  逐字节校验缓存里的 AAR，通过后才把“同一份字节”复制到 build/pinned-engine/，
//                    settings.gradle.kts 里的本地仓库只从那里取。哈希不符、缺文件都会让构建失败。
// 每个 Android 模块的 preBuild 都依赖 verifySherpaAar。
// ---------------------------------------------------------------------------------------------
val enginePin = Properties().apply {
    file("engine/sherpa-onnx.properties").inputStream().use { load(it) }
}
extra["enginePin"] = enginePin

val cacheRoot: File = File(System.getenv("AURI_CACHE_DIR") ?: "${System.getProperty("user.home")}/.cache/auri")

abstract class FetchPinnedFile : DefaultTask() {
    @get:Input abstract val sourceUrl: Property<String>
    @get:Input abstract val expectedSha256: Property<String>
    @get:Input abstract val offline: Property<Boolean>
    @get:Internal abstract val cacheFile: RegularFileProperty

    private fun hex(digest: MessageDigest): String = digest.digest().joinToString("") { "%02x".format(it) }

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun fetch() {
        val target = cacheFile.get().asFile
        // 已有文件时不重新下载，也不替换：它对不对由 verifySherpaAar 判断，不对就让构建失败。
        if (target.isFile) return
        if (offline.get()) {
            throw GradleException(
                "Pinned engine file is missing at $target and Gradle is offline. " +
                    "Run `./gradlew fetchSherpaAar` with network access, or copy the file there by hand."
            )
        }
        val url = sourceUrl.get()
        require(url.startsWith("https://")) { "Pinned source must be https: $url" }
        target.parentFile.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        part.delete()
        logger.lifecycle("Downloading $url")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        val digest = MessageDigest.getInstance("SHA-256")
        connection.inputStream.use { input ->
            DigestInputStream(input, digest).use { stream -> part.outputStream().use { out -> stream.copyTo(out) } }
        }
        val actual = hex(digest)
        if (actual != expectedSha256.get()) {
            part.delete()
            throw GradleException(
                "Downloaded file has SHA-256 $actual but the pin expects ${expectedSha256.get()}; discarded."
            )
        }
        check(part.renameTo(target)) { "Could not move $part to $target" }
    }
}

abstract class VerifyPinnedFile : DefaultTask() {
    @get:Input abstract val expectedSha256: Property<String>
    @get:Input abstract val expectedSizeBytes: Property<Long>
    @get:Internal abstract val cacheFile: RegularFileProperty
    @get:OutputFile abstract val verifiedFile: RegularFileProperty

    private fun hex(digest: MessageDigest): String = digest.digest().joinToString("") { "%02x".format(it) }

    init {
        // 每次构建都重新校验：缓存文件不在 Gradle 的输入里，不能因为“上次通过了”就跳过。
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun verify() {
        val out = verifiedFile.get().asFile
        out.delete() // 校验失败时不能留下上一次通过的副本
        val source = cacheFile.get().asFile
        if (!source.isFile) {
            throw GradleException(
                "Pinned engine AAR not found at $source. Run `./gradlew fetchSherpaAar` (needs network) " +
                    "or copy the file there."
            )
        }
        out.parentFile.mkdirs()
        val temp = File(out.parentFile, out.name + ".tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        // 边读边算哈希边写：校验的字节就是复制出去的字节，中间没有被替换的空隙。
        val size = source.inputStream().use { input ->
            DigestInputStream(input, digest).use { stream -> temp.outputStream().use { stream.copyTo(it) } }
        }
        val actual = hex(digest)
        if (actual != expectedSha256.get() || size != expectedSizeBytes.get()) {
            temp.delete()
            throw GradleException(
                """
                |Engine AAR does not match the pin in engine/sherpa-onnx.properties; refusing to build.
                |  file:             $source
                |  expected SHA-256: ${expectedSha256.get()} (${expectedSizeBytes.get()} bytes)
                |  actual SHA-256:   $actual ($size bytes)
                |Delete the file and run `./gradlew fetchSherpaAar`, or review the pin change.
                """.trimMargin()
            )
        }
        check(temp.renameTo(out)) { "Could not move $temp to $out" }
    }
}

val pinnedAarName = enginePin.getProperty("aar.file")

val fetchSherpaAar = tasks.register<FetchPinnedFile>("fetchSherpaAar") {
    group = "engine"
    description = "Downloads the pinned sherpa-onnx AAR into the cache outside the repository if it is missing."
    sourceUrl.set(enginePin.getProperty("aar.url"))
    expectedSha256.set(enginePin.getProperty("aar.sha256"))
    offline.set(gradle.startParameter.isOffline)
    cacheFile.set(File(cacheRoot, "engine/$pinnedAarName"))
}

val verifySherpaAar = tasks.register<VerifyPinnedFile>("verifySherpaAar") {
    group = "engine"
    description = "Refuses to continue unless the cached sherpa-onnx AAR has the pinned SHA-256."
    dependsOn(fetchSherpaAar)
    expectedSha256.set(enginePin.getProperty("aar.sha256"))
    expectedSizeBytes.set(enginePin.getProperty("aar.size_bytes").toLong())
    cacheFile.set(File(cacheRoot, "engine/$pinnedAarName"))
    verifiedFile.set(layout.buildDirectory.file("pinned-engine/$pinnedAarName"))
}

subprojects {
    tasks.matching { it.name == "preBuild" }.configureEach {
        dependsOn(rootProject.tasks.named("verifySherpaAar"))
    }
}
