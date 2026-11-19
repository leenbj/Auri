package app.auri.modelmgmt

import app.auri.asr.manifest.ModelManifest
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * 从 .tar.bz2 里解出清单列的文件。规则与 execution/acceptance/asr-android/prepare_model.py 一致：
 *  - 只写清单里列的路径（去掉包的顶层目录；没有顶层目录也认）；写到哪里由清单里的路径决定，绝不取自条目名，所以没有路径逃逸；
 *  - 只认普通文件：目录、符号链接、硬链接、设备一概不写，也不跟随；
 *  - 条目大小必须等于清单大小（写盘之前就查）、同一路径只能出现一次、清单里的文件一个都不能缺；
 *  - 说明文件、示例音频等其余条目一个字节都不落盘。
 * 解出的文件是否可信由 [app.auri.asr.manifest.ModelVerifier] 按大小与 SHA-256 再核对一遍（在 [ModelManager] 里，启用之前）。
 */
internal object ArchiveInstaller {
    /**
     * 把 [archive] 里清单要求的文件解到已存在的空目录 [into]。每读一块调一次 [checkpoint]，它抛什么就原样往外抛（暂停靠它停下）。
     * 压缩包本身有问题抛 [ModelException]（ARCHIVE_INVALID）；写盘失败抛 NOT_ENOUGH_SPACE 或 STORAGE_ERROR。
     */
    fun extract(archive: File, manifest: ModelManifest, into: File, checkpoint: () -> Unit = {}) {
        val wanted = manifest.files.associateBy { it.path }
        val found = HashSet<String>()
        try {
            TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive), 1 shl 16), /* decompressConcatenated = */ true)).use { tar ->
                while (true) {
                    checkpoint()
                    val entry = tar.nextEntry ?: break
                    if (!entry.isFile) continue
                    val relative = relativeName(entry.name, wanted.keys) ?: continue
                    val expected = wanted.getValue(relative)
                    if (!found.add(relative)) throw invalid()
                    if (entry.size != expected.sizeBytes) throw invalid()
                    copyExactly(tar, File(into, relative), expected.sizeBytes, checkpoint)
                }
            }
        } catch (e: ModelException) {
            throw e
        } catch (e: IOException) {
            // 写盘失败在 copyExactly 里已经分了类；到这里的是读压缩包失败：不是 bzip2、被截断、tar 结构坏了
            throw invalid()
        }
        if (found.size != wanted.size) throw invalid()
    }

    /** 条目名对应清单里的哪个路径：先按原样（没有顶层目录），再去掉第一段（有顶层目录）。都不在清单里就是 null。 */
    private fun relativeName(name: String, wanted: Set<String>): String? {
        val plain = name.removePrefix("./")
        if (plain in wanted) return plain
        val stripped = plain.substringAfter('/', missingDelimiterValue = "")
        return stripped.takeIf { it in wanted }
    }

    private fun copyExactly(tar: InputStream, target: File, size: Long, checkpoint: () -> Unit) {
        try {
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { out ->
                val buffer = ByteArray(1 shl 16)
                var remaining = size
                while (remaining > 0) {
                    checkpoint()
                    val n = try {
                        tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    } catch (e: IOException) {
                        throw invalid()
                    }
                    if (n < 0) throw invalid() // 包在这个文件中间结束了
                    out.write(buffer, 0, n)
                    remaining -= n
                }
                out.fd.sync() // 启用之前文件内容要真的落盘，掉电后不能是一个“看起来完整”的空壳
            }
        } catch (e: ModelException) {
            throw e
        } catch (e: IOException) {
            throw ModelException(ResumableDownloader.storageError(e))
        }
    }

    private fun invalid() = ModelException(ModelError(ModelErrorKind.ARCHIVE_INVALID))
}
