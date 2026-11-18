package app.auri.capture.testing

/** 系统会话列表里的一条（只取设备测试要断言的三样）。[state] 是 PlaybackState 的名字（PAUSED / PLAYING …），没声明过则为 null。 */
data class MediaSessionInfo(val packageName: String, val tag: String, val active: Boolean, val state: String?)

/**
 * `adb shell dumpsys media_session` 的输出，只留设备测试要的两样：系统里有哪些会话，以及系统选了哪个会话当“媒体键会话”
 * （没选则为 null——这时按下的媒体键不会送到任何会话）。
 *
 * 只给设备测试用：读系统的会话列表，才能证明“会话只在控制模式开着时存在”，而不是靠“没收到按键”这种空转的证据
 * （在没有音频播放的设备上，任何静音会话本来就收不到按键）。格式取自 Android 15（API 35）的实测输出，
 * 每个会话块是一行表头 `<tag> <package>/<tag>/<id> (userId=N)`，后面跟缩进更深的字段行；按缩进切块，不依赖字段顺序。
 */
data class MediaSessionDump(val sessions: List<MediaSessionInfo>, val mediaButtonSession: String?) {
    fun find(packageName: String, tag: String): MediaSessionInfo? =
        sessions.firstOrNull { it.packageName == packageName && it.tag == tag }

    companion object {
        // 表头里 tag 出现两次；“Media button session is pkg/tag/id (userId=0)”那一行只出现一次，靠这个区分
        private val HEADER = Regex("""^(\s*)(.+) ([A-Za-z0-9_.]+)/(.+)/\d+ \(userId=\d+\)\s*$""")
        private val STATE = Regex("""state=PlaybackState \{state=([A-Z_]+)\(""")
        private val MEDIA_BUTTON = Regex("""^\s*Media button session is (\S+)(?: \(userId=\d+\))?\s*$""")

        fun parse(dump: String): MediaSessionDump {
            val lines = dump.lines()
            val sessions = mutableListOf<MediaSessionInfo>()
            var i = 0
            while (i < lines.size) {
                val header = HEADER.matchEntire(lines[i])
                if (header == null || header.groupValues[2] != header.groupValues[4]) {
                    i++
                    continue
                }
                val headerIndent = header.groupValues[1].length
                val fields = lines.drop(i + 1).takeWhile { it.isNotBlank() && indentOf(it) > headerIndent }
                sessions += MediaSessionInfo(
                    packageName = header.groupValues[3],
                    tag = header.groupValues[2],
                    active = fields.any { it.trim() == "active=true" },
                    state = fields.firstNotNullOfOrNull { STATE.find(it)?.groupValues?.get(1) },
                )
                i += 1 + fields.size
            }
            val mediaButton = lines.firstNotNullOfOrNull { MEDIA_BUTTON.matchEntire(it)?.groupValues?.get(1) }
            return MediaSessionDump(sessions, mediaButton?.takeUnless { it == "null" })
        }

        private fun indentOf(line: String): Int = line.length - line.trimStart().length
    }
}
