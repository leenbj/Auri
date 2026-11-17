package app.auri.asr.manifest

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** 只有 [ModelVerifier] 能造出它：拿到它就等于“这些文件刚刚按清单校验过”。 */
class VerifiedModel internal constructor(val manifest: ModelManifest, private val byRole: Map<String, File>) {
    fun file(role: String): File = byRole[role] ?: throw IllegalArgumentException("清单里没有角色为 '$role' 的文件")
}

/**
 * 按清单逐个核对模型文件：存在、在模型目录之内（符号链接逃逸也算不在）、大小一致、SHA-256 一致。
 * 全部通过才给出 [VerifiedModel]；任何一项不过都抛 [ModelRefusedException]，调用方不应继续加载。
 *
 * 已知限制：校验的是路径，引擎随后按路径重新打开文件。模型目录必须是应用私有目录（Ticket 20 的产品存储），
 * 否则校验与加载之间文件可被替换。调试宿主把模型放在外部应用目录，只用于验收，不用于产品。
 */
class ModelVerifier(private val hash: (File) -> String = ::sha256HexOf) {
    fun verify(manifest: ModelManifest, modelDir: File): VerifiedModel {
        val root = try {
            modelDir.canonicalFile
        } catch (e: IOException) {
            throw refuse(ModelRefusal.NOT_INSTALLED, "模型目录无法解析")
        }
        if (!root.isDirectory) throw refuse(ModelRefusal.NOT_INSTALLED, "模型目录不存在")

        val resolved = LinkedHashMap<String, File>()
        for (entry in manifest.files) {
            val file = try {
                File(root, entry.path).canonicalFile
            } catch (e: IOException) {
                throw refuse(ModelRefusal.UNSAFE_PATH, "文件 ${entry.role}（${entry.path}）的路径无法解析")
            }
            if (!file.toPath().startsWith(root.toPath())) {
                throw refuse(ModelRefusal.UNSAFE_PATH, "文件 ${entry.role}（${entry.path}）解析后落在模型目录之外")
            }
            if (!file.isFile) throw refuse(ModelRefusal.NOT_INSTALLED, "文件 ${entry.role}（${entry.path}）不存在")

            val size = file.length()
            if (size != entry.sizeBytes) {
                throw refuse(
                    ModelRefusal.SIZE_MISMATCH,
                    "文件 ${entry.role}（${entry.path}）大小 $size 字节，清单要求 ${entry.sizeBytes} 字节",
                )
            }
            val actual = try {
                hash(file)
            } catch (e: IOException) {
                // 不把底层异常的文字带进消息：它常带完整路径
                throw refuse(ModelRefusal.UNREADABLE, "文件 ${entry.role}（${entry.path}）读取失败，无法校验")
            }
            if (actual != entry.sha256) {
                throw refuse(
                    ModelRefusal.HASH_MISMATCH,
                    "文件 ${entry.role}（${entry.path}）SHA-256 与清单不符（清单 ${entry.sha256.take(12)}…，实际 ${actual.take(12)}…）",
                )
            }
            resolved[entry.role] = file
        }
        return VerifiedModel(manifest, resolved)
    }

    private fun refuse(refusal: ModelRefusal, message: String) = ModelRefusedException(refusal, message)
}

internal fun sha256HexOf(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1 shl 20)
    file.inputStream().use { input ->
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
