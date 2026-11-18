package app.auri.modelmgmt

import app.auri.asr.manifest.ModelManifest
import app.auri.asr.manifest.ModelRefusedException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 当前启用的是哪一个目录，以及校验它时用的那份清单原文。清单随模型一起存下来：目录里的文件将来仍按“装它时的清单”校验，
 * 即使随后应用更新带来了新版本的清单（新版本装好之前旧模型一直靠这份清单保持可用）。
 */
internal class ActivePointer(val directory: String, val manifestJson: String)

/**
 * 应用私有目录下的磁盘布局（root 是应用的 filesDir；全部在同一个文件系统上，所以目录改名是原子的）：
 *
 *     model-store/<model_id>/active.json          启用指针：{"v":1,"directory":"<目录名>","manifest_json":"<清单原文>"}
 *     model-store/<model_id>/<版本>-<随机后缀>/    启用目录：只含校验过的清单文件。每次安装都用全新的目录名，所以不会覆盖任何正在用的东西
 *     model-downloads/<model_id>/<版本>/           下载区：archive.part 与 download.json（续传用，装好后删）
 *     model-staging/<model_id>-<随机后缀>/         解包区：每次安装一个新目录，进程重启时整个清空
 *
 * 与 Ticket 14 的调试布局（files/models/<id>/<版本>/，由验收脚本手工放入）互不相干：这里不碰 files/models。
 * 目录名里的版本经 [safeName] 清洗：清单只保证版本是不含控制字符的短文本，不能把它原样当路径。
 */
internal class ModelStore(root: File) {
    val stagingRoot = File(root, "model-staging")
    private val storeRoot = File(root, "model-store")
    private val downloadsRoot = File(root, "model-downloads")

    fun storeDir(modelId: String) = File(storeRoot, safeName(modelId))

    fun downloadDir(modelId: String, version: String) = File(File(downloadsRoot, safeName(modelId)), safeName(version))

    fun stagingDir(modelId: String, nonce: String) = File(stagingRoot, "${safeName(modelId)}-$nonce")

    fun activeDir(modelId: String, pointer: ActivePointer) = File(storeDir(modelId), pointer.directory)

    /** 指针缺失、损坏、目录名不安全、目录不存在、清单不合格，一律当作“没装”（返回 null），不抛异常。 */
    fun readPointer(modelId: String): Pair<ActivePointer, ModelManifest>? {
        val file = File(storeDir(modelId), POINTER_FILE)
        val parsed = try {
            if (file.isFile) json.decodeFromString<PointerFile>(file.readText()) else return null
        } catch (e: IllegalArgumentException) {
            return null
        } catch (e: IOException) {
            return null
        }
        if (parsed.version != 1 || !SAFE_DIRECTORY.matches(parsed.directory) || parsed.directory.startsWith(".")) return null
        val pointer = ActivePointer(parsed.directory, parsed.manifestJson)
        if (!activeDir(modelId, pointer).isDirectory) return null
        val manifest = try {
            ModelManifest.parse(parsed.manifestJson)
        } catch (e: ModelRefusedException) {
            return null
        }
        return pointer to manifest
    }

    /** 先写临时文件并落盘，再原子改名：指针要么是旧的完整版本，要么是新的完整版本。 */
    fun writePointer(modelId: String, pointer: ActivePointer) {
        val dir = storeDir(modelId).apply { mkdirs() }
        val temp = File(dir, "$POINTER_FILE.tmp")
        FileOutputStream(temp).use {
            it.write(json.encodeToString(PointerFile.serializer(), PointerFile(1, pointer.directory, pointer.manifestJson)).toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        Files.move(temp.toPath(), File(dir, POINTER_FILE).toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    /** 删掉 model-store/<id>/ 下除 [keep] 之外的所有目录：旧版本、没写上指针的半成品。指针文件本身不动。 */
    fun collectGarbage(modelId: String, keep: Set<String>) {
        storeDir(modelId).listFiles()?.filter { it.isDirectory && it.name !in keep }?.forEach { it.deleteRecursively() }
    }

    /** 删掉下载区里除 [keepVersionDir] 之外的东西（别的版本的残片，或已装好的版本的残余）。 */
    fun cleanDownloads(modelId: String, keepVersionDir: File?) {
        File(downloadsRoot, safeName(modelId)).listFiles()?.filter { it != keepVersionDir }?.forEach { it.deleteRecursively() }
    }

    @Serializable
    private data class PointerFile(
        @SerialName("v") val version: Int,
        val directory: String,
        @SerialName("manifest_json") val manifestJson: String,
    )

    companion object {
        const val POINTER_FILE = "active.json"
        private val SAFE_DIRECTORY = Regex("^[A-Za-z0-9._+-]{1,250}$")
        private val json = Json { ignoreUnknownKeys = false }

        /** 把清单里的名字变成安全的单段文件名：只留字母数字与 `._+-`，其余换成下划线，不以点开头。 */
        fun safeName(name: String): String =
            name.map { if (it.code < 128 && (it.isLetterOrDigit() || it in "._+-")) it else '_' }
                .joinToString("").trimStart('.').take(100).ifEmpty { "v" }
    }
}
