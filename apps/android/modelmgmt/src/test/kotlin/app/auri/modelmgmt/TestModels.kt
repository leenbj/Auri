package app.auri.modelmgmt

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream

/** 造一个 .tar.bz2：每一项 [Item] 是包里的一个条目。 */
internal object TestArchives {
    class Item(val name: String, val bytes: ByteArray = ByteArray(0), val type: Byte = TarConstants.LF_NORMAL, val linkName: String? = null)

    fun write(file: File, items: List<Item>): ByteArray {
        file.parentFile.mkdirs()
        BZip2CompressorOutputStream(FileOutputStream(file)).use { bz ->
            TarArchiveOutputStream(bz).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                for (item in items) {
                    val entry = TarArchiveEntry(item.name, item.type)
                    if (item.linkName != null) entry.linkName = item.linkName
                    if (item.type == TarConstants.LF_NORMAL) entry.size = item.bytes.size.toLong()
                    tar.putArchiveEntry(entry)
                    if (item.type == TarConstants.LF_NORMAL) tar.write(item.bytes)
                    tar.closeArchiveEntry()
                }
            }
        }
        return file.readBytes()
    }
}

/**
 * 一个小模型：两个文件（model.bin、tokens.txt）打成 `<顶层目录>/…` 的 .tar.bz2，外加一个清单。
 * 清单里的下载地址是占位的 https 地址（清单解析只收 https）；测试用 [RewritingTransport] 把它指到本机假服务器。
 */
internal class TestModel(
    val workDir: File,
    val modelId: String = "test-model",
    val version: String = "1.0.0",
    seed: Int = 1,
    val modelSize: Int = 200_000,
    /** 清单里 model.bin 的哈希；默认是真实的，测试可以故意写错。 */
    private val declaredModelSha256: String? = null,
    private val declaredTokensSha256: String? = null,
) {
    // 伪随机、不可压缩：包大小才接近文件大小，“下载到一半卡住 / 断线”的偏移量（几万字节）才落在包里面
    val modelBytes = ByteArray(modelSize).also { java.util.Random(seed.toLong()).nextBytes(it) }
    val tokensBytes = "<blk>\n你好\nhello\n".toByteArray(Charsets.UTF_8)
    val archiveFile = File(workDir, "$modelId-$version.tar.bz2")
    val archiveBytes: ByteArray = TestArchives.write(
        archiveFile,
        listOf(
            TestArchives.Item("top/", type = TarConstants.LF_DIR),
            TestArchives.Item("top/model.bin", modelBytes),
            TestArchives.Item("top/tokens.txt", tokensBytes),
            TestArchives.Item("top/README.md", "说明".toByteArray()),
            TestArchives.Item("top/test_wavs/0.wav", ByteArray(100) { 1 }),
        ),
    )
    val url = "https://models.test/$modelId/$version.tar.bz2"

    init {
        // 包太小的话，测试里“下载到 5 万字节时卡住”根本不会发生，整个测试就是空转
        check(archiveBytes.size > 100_000) { "测试包只有 ${archiveBytes.size} 字节，比测试用的卡住/断线偏移量还小" }
    }

    val manifestJson: String = """
        {
          "manifest_version": 1,
          "model_id": "$modelId",
          "model_version": "$version",
          "family": "test_family",
          "languages": ["zh", "en"],
          "sample_rate_hz": 16000,
          "limits": { "max_audio_duration_ms": 30000 },
          "engine": { "name": "sherpa-onnx", "version_range": "==2.0.1" },
          "license": {
            "name": "Test License",
            "source_url": "https://example.test/license",
            "redistribution": "confirmed",
            "note": "仅用于测试"
          },
          "archive": { "url": "$url", "size_bytes": ${archiveBytes.size}, "sha256": "${sha256Hex(archiveBytes)}" },
          "files": [
            { "role": "model", "path": "model.bin", "size_bytes": ${modelBytes.size}, "sha256": "${declaredModelSha256 ?: sha256Hex(modelBytes)}" },
            { "role": "tokens", "path": "tokens.txt", "size_bytes": ${tokensBytes.size}, "sha256": "${declaredTokensSha256 ?: sha256Hex(tokensBytes)}" }
          ]
        }
    """.trimIndent()

    /** 同样大小、不同字节的“被篡改的下载”：哈希对不上。 */
    fun tamperedArchiveBytes(): ByteArray = archiveBytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
}

/** 把清单里的占位地址换成本机假服务器的地址；其余原样交给真实的 HttpURLConnection 传输。 */
internal class RewritingTransport(private val delegate: HttpTransport, private val mapping: () -> Map<String, String>) : HttpTransport {
    val requestedUrls = java.util.concurrent.CopyOnWriteArrayList<String>()

    override fun get(url: String, headers: Map<String, String>): TransportResponse {
        requestedUrls += url
        return delegate.get(mapping()[url] ?: error("测试没有为 $url 配置服务器"), headers)
    }
}

/** 递归复制目录：在“崩溃点”给磁盘拍一张照，等于进程在那一刻被杀后磁盘上的样子。 */
internal fun File.snapshotTo(target: File): File {
    target.deleteRecursively()
    copyRecursively(target, overwrite = true)
    return target
}

private val folderCounter = AtomicInteger()

internal fun newFolderName(prefix: String) = "$prefix-${folderCounter.incrementAndGet()}"
