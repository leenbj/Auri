package app.auri.modelmgmt

import app.auri.asr.manifest.ModelManifest
import app.auri.asr.manifest.Redistribution

/** 清单里的一个文件：用户能看到的“会得到什么”，以及校验它的固定哈希。 */
data class ModelFileInfo(val path: String, val sizeBytes: Long, val sha256: String)

/**
 * 模型列表里一行要显示的全部信息，全部来自清单；没有一项是临时编出来的。
 * 许可是一个条目（名称 + 能打开的地址 + 能否随包再分发的结论），不是一句“已同意”。
 */
data class ModelInfo(
    val modelId: String,
    val modelVersion: String,
    val languages: List<String>,
    /** 要下载的压缩包大小。 */
    val archiveSizeBytes: Long,
    /** 解出来的文件合计大小（清单 files 的大小之和）。 */
    val unpackedSizeBytes: Long,
    /** 从零开始安装需要的可用空间：下载 + 解包 + 余量（[SpaceRule]）。 */
    val spaceNeededBytes: Long,
    val licenseName: String,
    /** https 地址，界面用它打开许可全文。 */
    val licenseUrl: String,
    val redistribution: Redistribution,
    val licenseNote: String,
    /** 压缩包的固定哈希：下载的字节必须与它一致才会被解开。 */
    val archiveSha256: String,
    /** 每个文件的固定哈希：解出的文件必须逐个一致才会被启用。 */
    val files: List<ModelFileInfo>,
) {
    companion object {
        fun of(manifest: ModelManifest) = ModelInfo(
            modelId = manifest.modelId,
            modelVersion = manifest.modelVersion,
            languages = manifest.languages,
            archiveSizeBytes = manifest.archive.sizeBytes,
            unpackedSizeBytes = manifest.files.sumOf { it.sizeBytes },
            spaceNeededBytes = SpaceRule.needed(manifest.archive.sizeBytes, manifest.files.sumOf { it.sizeBytes }),
            licenseName = manifest.license.name,
            licenseUrl = manifest.license.sourceUrl,
            redistribution = manifest.license.redistribution,
            licenseNote = manifest.license.note,
            archiveSha256 = manifest.archive.sha256,
            files = manifest.files.map { ModelFileInfo(it.path, it.sizeBytes, it.sha256) },
        )
    }
}

/**
 * 安装所需空间的规则（FR-ASR-004）：压缩包 + 解出的文件 + 余量。两份同时存在：压缩包要等解包、校验、启用都成功才删。
 * 余量覆盖文件系统的块取整、同时写盘的别的应用、SQLite 日志等；取总量的 10%，但不少于 64 MiB。
 * 已经下载的部分不必再给空间，余量仍按完整大小算（这样续传前后的判断是同一把尺子）。
 * 旧版本模型在新版本启用之前一直保留（失败的更新不能让旧模型失效），它已经占着空间，不计入这里。
 */
object SpaceRule {
    const val MIN_MARGIN_BYTES: Long = 64L * 1024 * 1024

    fun margin(archiveBytes: Long, unpackedBytes: Long): Long = maxOf(MIN_MARGIN_BYTES, (archiveBytes + unpackedBytes) / 10)

    fun needed(archiveBytes: Long, unpackedBytes: Long, alreadyDownloaded: Long = 0): Long {
        val remainingDownload = archiveBytes - alreadyDownloaded.coerceIn(0, archiveBytes)
        return remainingDownload + unpackedBytes + margin(archiveBytes, unpackedBytes)
    }
}

/** 目录里的一项：清单原文（启用时与模型一起存下来，将来仍能按它校验）、解析后的清单与列表信息。 */
class CatalogEntry internal constructor(val manifestJson: String, val manifest: ModelManifest) {
    val info: ModelInfo = ModelInfo.of(manifest)
    val modelId: String get() = manifest.modelId
}

/**
 * 应用知道的所有模型清单。支持多份清单；默认用哪一份由 Ticket 15 评测后决定，现在只随包带一份替身清单。
 * 清单不合格就整个目录拒绝（[app.auri.asr.manifest.ModelRefusedException]）：悄悄跳过一份坏清单，用户只会看到“少了一个模型”。
 */
class ModelCatalog private constructor(val entries: List<CatalogEntry>, val defaultModelId: String) {
    val default: CatalogEntry get() = entry(defaultModelId)!!

    fun entry(modelId: String): CatalogEntry? = entries.firstOrNull { it.modelId == modelId }

    companion object {
        fun fromJson(manifests: List<String>, defaultModelId: String? = null): ModelCatalog {
            require(manifests.isNotEmpty()) { "模型目录至少要有一份清单" }
            val entries = manifests.map { CatalogEntry(it, ModelManifest.parse(it)) }
            val ids = entries.map { it.modelId }
            require(ids.toSet().size == ids.size) { "模型目录里有重复的 model_id" }
            val default = defaultModelId ?: ids.first()
            require(default in ids) { "默认模型 $default 不在目录里" }
            return ModelCatalog(entries, default)
        }
    }
}
