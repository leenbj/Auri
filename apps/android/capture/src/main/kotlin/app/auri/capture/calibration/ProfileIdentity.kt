package app.auri.capture.calibration

private const val MAX_HINT = 100
private const val PREFIX = "android-"
private val NON_ID_CHARS = Regex("[^a-z0-9]+")

/** 用户输入的耳机型号 → 档案的 model_hint：去掉首尾空白，空的是 null（未知不猜），最长 100 个字符。 */
fun modelHintOf(text: String?): String? = text?.trim()?.take(MAX_HINT)?.trim()?.ifEmpty { null }

/**
 * 档案标识（profile_id）：小写字母、数字与连字符，不超过 100 个字符，与合同的正则一致。
 * 型号里不是小写字母或数字的部分（空格、中文、符号）都折成连字符；什么都不剩就用 android-headset。
 */
fun profileIdFor(modelHint: String?): String {
    val slug = modelHint.orEmpty().lowercase().replace(NON_ID_CHARS, "-").trim('-')
    return if (slug.isEmpty()) "${PREFIX}headset" else (PREFIX + slug).take(MAX_HINT).trimEnd('-')
}

/** 保存与读取校准产出的档案；每台手机只保留一份当前档案。读不出来（文件损坏、内容不合规）就是 null，不是异常。 */
interface ProfileStore {
    fun load(): HeadsetProfile?

    fun save(profile: HeadsetProfile)
}
