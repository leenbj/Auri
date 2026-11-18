package app.auri.platform.control

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 耳机的麦克风正在（或即将）录音时，向蓝牙通路播放任何声音（提示音、振铃、语音播报、假装在播放的媒体会话）都会
 * 进入录音、也违反“不向蓝牙通路播放任何声音”的约束。开始、停止、没能开始的提示只用振动、屏幕文字与静音通知。
 *
 * 这里扫四个生产模块（含 debug 源集）的源码：没有任何播放声音的接口，通知渠道只许 setSound(null, null)（关掉声音），
 * 媒体会话从不声明“正在播放”（它只为收按键而存在，不是保活手段）。真正的通知渠道属性在设备上由
 * FeedbackPlayerOnDeviceTest 核对。
 */
class NoSoundIntoBluetoothTest {
    private fun code(text: String): String = text
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private val sources: Map<String, String> = listOf("capture", "asr", "platform", "app").flatMap { module ->
        listOf("main", "debug").map { File("../$module/src/$it") }.filter { it.isDirectory }
    }.flatMap { root ->
        root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.map { it.path to code(it.readText()) }.toList()
    }.toMap()

    /** 播放声音的接口，以及把声音塞进通知的写法。`setSound(null, null)` 是关声音，唯一允许（见下面的 soundArgument）。 */
    private val forbidden = listOf(
        "MediaPlayer", "SoundPool", "ToneGenerator", "AudioTrack", "Ringtone", "TextToSpeech",
        "playSoundEffect", "SoundEffectConstants", "DEFAULT_SOUND", "DEFAULT_ALL", "setDefaults", "STATE_PLAYING",
    ).map { Regex("\\b$it") }

    private val soundArgument = Regex("""setSound\((?!\s*null\s*,\s*null\s*\))""")

    private fun violations(files: Map<String, String>): List<String> = files.flatMap { (path, text) ->
        forbidden.filter { it.containsMatchIn(text) }.map { "$path：${it.pattern}" } +
            (if (soundArgument.containsMatchIn(text)) listOf("$path：setSound 的参数不是 null, null") else emptyList())
    }.sorted()

    @Test
    fun `四个生产模块里没有任何播放声音的写法，也没有把媒体会话设成正在播放`() {
        assertTrue("应当扫到源码", sources.size > 30)
        assertEquals(emptyList<String>(), violations(sources))
    }

    @Test
    fun `守卫自己能抓到：每一种被禁的写法注入后都被报出，setSound(null, null) 不被报`() {
        val bad = listOf(
            "val p = MediaPlayer()", "SoundPool.Builder()", "ToneGenerator(0, 100)", "AudioTrack.Builder()", "RingtoneManager.getRingtone()",
            "TextToSpeech(context, listener)", "audioManager.playSoundEffect(0)", "SoundEffectConstants.CLICK", "Notification.DEFAULT_SOUND",
            "Notification.DEFAULT_ALL", "builder.setDefaults(1)", "PlaybackState.STATE_PLAYING", "channel.setSound(uri, attrs)",
            "channel.setSound(defaultUri, null)",
        )
        bad.forEach { snippet ->
            assertTrue("没抓到：$snippet", violations(mapOf("Injected.kt" to code(snippet))).isNotEmpty())
        }
        assertEquals(emptyList<String>(), violations(mapOf("Ok.kt" to "channel.setSound(null, null)")))
        assertEquals("注释里写到这些词不算代码", emptyList<String>(), violations(mapOf("Doc.kt" to code("// 不用 MediaPlayer\n/* ToneGenerator */"))))
    }
}
