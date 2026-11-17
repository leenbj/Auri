package app.auri.asr.manifest

import app.auri.asr.contract.ErrorCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 为什么拒绝加载；[errorCode] 取自合同 common.schema.json 的 error_code 枚举。 */
enum class ModelRefusal(val errorCode: ErrorCode) {
    /** 清单自己就不合格：无法据此判断任何文件是否可信，按完整性失败处理。 */
    MANIFEST_INVALID(ErrorCode.MODEL_INTEGRITY_FAILED),
    UNSAFE_PATH(ErrorCode.MODEL_INTEGRITY_FAILED),
    NOT_INSTALLED(ErrorCode.MODEL_NOT_INSTALLED),
    SIZE_MISMATCH(ErrorCode.MODEL_INTEGRITY_FAILED),
    HASH_MISMATCH(ErrorCode.MODEL_INTEGRITY_FAILED),
}

/** 消息只含清单里的名字与哈希前缀，不含文件内容（日志默认不含正文、音频或凭证）。 */
class ModelRefusedException(val refusal: ModelRefusal, message: String) : Exception(message) {
    val errorCode: ErrorCode get() = refusal.errorCode
}

@Serializable
enum class Redistribution {
    @SerialName("confirmed") CONFIRMED,
    @SerialName("unconfirmed") UNCONFIRMED,
}

@Serializable
data class ManifestLimits(@SerialName("max_audio_duration_ms") val maxAudioDurationMs: Long?)

@Serializable
data class ManifestEngine(val name: String, @SerialName("version_range") val versionRange: String) {
    /** 解析时已保证 [versionRange] 形如 ==X.Y.Z，所以“范围”就是一个精确版本。 */
    fun accepts(actualVersion: String): Boolean = versionRange == "==$actualVersion"
}

@Serializable
data class ManifestLicense(
    val name: String,
    @SerialName("source_url") val sourceUrl: String,
    val redistribution: Redistribution,
    val note: String,
)

/** 清单所描述的这批文件是从哪个压缩包解出来的；校验器不用它，取包脚本用它核对下载。 */
@Serializable
data class ManifestArchive(val url: String, @SerialName("size_bytes") val sizeBytes: Long, val sha256: String)

@Serializable
data class ManifestFile(
    val role: String,
    val path: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    val sha256: String,
)

/**
 * 本地转录模型清单（FR-ASR-004 的一部分）。字段名与取值以 apps/android/manifests/ 下的真实清单为准。
 * 解析是严格的：多一个字段、少一个字段、路径会逃出目录、哈希格式不对，一律拒绝。
 * 所需空间与建议设备能力（FR-ASR-004 的其余字段）留给 Ticket 20：那时才有人读它们。
 */
@Serializable
data class ModelManifest(
    @SerialName("manifest_version") val manifestVersion: Int,
    @SerialName("model_id") val modelId: String,
    @SerialName("model_version") val modelVersion: String,
    val family: String,
    val languages: List<String>,
    @SerialName("sample_rate_hz") val sampleRateHz: Int,
    val limits: ManifestLimits,
    val engine: ManifestEngine,
    val license: ManifestLicense,
    val archive: ManifestArchive,
    val files: List<ManifestFile>,
) {
    companion object {
        const val SUPPORTED_VERSION = 1

        private val strictJson = Json {
            ignoreUnknownKeys = false
            isLenient = false
            coerceInputValues = false
        }

        // 合同 adapter.schema.json 的 asr_capabilities.languages 与 sample_rate_hz 的取值范围
        private val languageTag = Regex("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$")
        private val sampleRateRange = 8000..192000
        private val identifier = Regex("^[a-z0-9][a-z0-9._-]{0,99}$")
        private val familyName = Regex("^[a-z][a-z0-9_]{0,49}$")
        private val roleName = Regex("^[a-z][a-z0-9_]{0,49}$")
        private val sha256Hex = Regex("^[0-9a-f]{64}$")

        // shortcut: 只认精确版本（锁定依赖）；真要支持区间时在这里加，并同步 SherpaOnnxEngineFactory 的比较。
        private val exactVersion = Regex("^==[0-9]+\\.[0-9]+\\.[0-9]+$")

        fun parse(json: String): ModelManifest {
            val manifest = try {
                strictJson.decodeFromString<ModelManifest>(json)
            } catch (e: IllegalArgumentException) {
                // SerializationException 是 IllegalArgumentException 的子类；消息截短，避免把整份输入原样回显
                invalid("清单不是合法的 JSON 或字段与格式不符（${e.javaClass.simpleName}）：${e.message.orEmpty().lineSequence().first().take(160)}")
            }
            manifest.validate()
            return manifest
        }

        private fun invalid(message: String): Nothing =
            throw ModelRefusedException(ModelRefusal.MANIFEST_INVALID, message)

        private fun ModelManifest.validate() {
            if (manifestVersion != SUPPORTED_VERSION) invalid("不支持的 manifest_version：$manifestVersion")
            if (!identifier.matches(modelId)) invalid("model_id 格式不合法")
            requireText("model_version", modelVersion, 200)
            if (!familyName.matches(family)) invalid("family 格式不合法")

            if (languages.isEmpty()) invalid("languages 不能为空")
            if (languages.toSet().size != languages.size) invalid("languages 有重复项")
            languages.forEach { if (!languageTag.matches(it)) invalid("语言标签格式不合法：'${it.take(20)}'") }

            if (sampleRateHz !in sampleRateRange) invalid("sample_rate_hz 超出合同范围：$sampleRateHz")
            limits.maxAudioDurationMs?.let { if (it < 1) invalid("max_audio_duration_ms 必须 >= 1 或为 null") }

            // 上限 64：任务句柄的 provider_id 是 "local-" 加引擎名，合同限制它不超过 100 个字符
            requireText("engine.name", engine.name, 64)
            if (!exactVersion.matches(engine.versionRange)) invalid("engine.version_range 只接受精确版本，形如 ==2.0.1")

            requireText("license.name", license.name, 200)
            requireText("license.note", license.note, 1000)
            requireHttps("license.source_url", license.sourceUrl)

            requireHttps("archive.url", archive.url)
            if (archive.sizeBytes <= 0) invalid("archive.size_bytes 必须为正")
            if (!sha256Hex.matches(archive.sha256)) invalid("archive.sha256 必须是 64 位小写十六进制")

            if (files.isEmpty()) invalid("files 不能为空")
            if (files.map { it.path }.toSet().size != files.size) invalid("files 里有重复路径")
            if (files.map { it.role }.toSet().size != files.size) invalid("files 里有重复角色")
            for (file in files) {
                if (!roleName.matches(file.role)) invalid("文件角色格式不合法")
                if (!isSafeRelativePath(file.path)) invalid("文件路径不安全：'${file.path.take(40)}'")
                if (file.sizeBytes <= 0) invalid("文件 ${file.role} 的 size_bytes 必须为正")
                if (!sha256Hex.matches(file.sha256)) invalid("文件 ${file.role} 的 sha256 必须是 64 位小写十六进制")
            }
        }

        private fun requireText(name: String, value: String, maxLength: Int) {
            if (value.isEmpty() || value.length > maxLength || value.any { Character.isISOControl(it) }) {
                invalid("$name 不能为空、不能超过 $maxLength 个字符、不能含控制字符")
            }
        }

        private fun requireHttps(name: String, url: String) {
            requireText(name, url, 500)
            if (!url.startsWith("https://")) invalid("$name 必须是 https 地址")
        }

        /** 相对路径、用 / 分隔、每一段都是普通名字；不含 `.`、`..`、空段、反斜杠、冒号、控制字符。 */
        internal fun isSafeRelativePath(path: String): Boolean {
            if (path.isEmpty() || path.startsWith("/")) return false
            if (path.any { Character.isISOControl(it) || it == '\\' || it == ':' }) return false
            return path.split('/').all { it.isNotEmpty() && it != "." && it != ".." && it.trim() == it }
        }
    }
}
