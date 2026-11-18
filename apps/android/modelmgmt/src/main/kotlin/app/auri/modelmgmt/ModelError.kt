package app.auri.modelmgmt

/**
 * 下载与安装失败的原因，每一种在界面上都有一句能照着做的话（没有网络 → 联网后继续；空间不够 → 腾出空间；哈希不符 → 重新下载）。
 * 只有类别与数字，没有地址、没有文件内容：日志默认不含正文、音频或凭证，也不含可能带签名的下载地址。
 */
enum class ModelErrorKind {
    /** 连不上、连接中断或超时；已经下载的部分保留，联网后可以继续。 */
    NO_NETWORK,

    /** 开始前可用空间不够（[ModelError.neededBytes] / [ModelError.availableBytes]），或写盘时系统报空间已满。 */
    NOT_ENOUGH_SPACE,

    /** 下载的字节或解出的文件与清单固定的大小、SHA-256 不符：已丢弃，不会被启用。 */
    HASH_MISMATCH,

    /** 服务器给了错误状态（[ModelError.httpStatus]）或不合协议的响应。 */
    SERVER_ERROR,

    /** 本机存储读写失败（不是空间问题）。 */
    STORAGE_ERROR,

    /** 压缩包下载与校验都通过，但解不开或缺清单要求的文件。 */
    ARCHIVE_INVALID,
}

data class ModelError(
    val kind: ModelErrorKind,
    val httpStatus: Int? = null,
    val neededBytes: Long? = null,
    val availableBytes: Long? = null,
)

/** 下载或安装的失败。消息只有类别名。 */
class ModelException(val error: ModelError) : Exception(error.kind.name)
