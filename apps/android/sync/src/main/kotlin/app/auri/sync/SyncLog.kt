package app.auri.sync

/**
 * 同步与配对的日志出口。只允许写事件名、枚举与数字：不写正文、令牌、配对码、公钥指纹、主机名或地址。
 * 测试里换成收集器，逐行核对“机密没有进过日志”。
 */
fun interface SyncLog {
    fun event(line: String)

    companion object {
        val None = SyncLog { }
    }
}
