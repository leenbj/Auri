package app.auri.sync.pairing

/**
 * 网关发给这台设备的令牌。只有两个出口：[bearer]（请求头）与 [reveal]（交给安全存储加密）；
 * toString 永远不含内容，日志、异常消息、调试输出里即使不小心带上它也不会泄露。
 */
class DeviceToken private constructor(private val value: String) {
    /** `Authorization` 请求头的值。 */
    fun bearer(): String = "Bearer $value"

    /** 仅供安全存储在加密前取出明文；别处不要调用。 */
    fun reveal(): String = value

    override fun toString() = "DeviceToken(redacted)"

    override fun equals(other: Any?) = other is DeviceToken && other.value == value

    override fun hashCode() = value.hashCode()

    companion object {
        // 合同：32..512 个字符。再收紧到“可见 ASCII、没有空白”，令牌才不可能夹带换行去改写请求头
        private val FORMAT = Regex("^[\\x21-\\x7e]{32,512}$")

        fun parse(raw: String): DeviceToken? = if (FORMAT.matches(raw)) DeviceToken(raw) else null
    }
}
