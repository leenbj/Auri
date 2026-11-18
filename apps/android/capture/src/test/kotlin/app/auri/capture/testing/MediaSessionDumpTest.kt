package app.auri.capture.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备测试靠它读系统的会话列表：“会话只在控制模式开着时存在、已激活、声明的是暂停而不是播放”这几条断言，
 * 全部压在这个解析器上。解析错了会让“关掉之后会话没了”这类断言空转（什么都找不到 = 永远“没了”），
 * 所以每一种形状都要有一条会失败的测试。
 *
 * 样本的字段、顺序与缩进规则取自 Android 15（API 35）模拟器上 `dumpsys media_session` 的实测输出
 * （缩进按 AOSP 的 MediaSessionStack.dump：每层多两个空格）；设备测试每次都用真的输出再验一遍这个格式。
 */
class MediaSessionDumpTest {
    private val btSession = """
    BluetoothMediaBrowserService com.google.android.bluetooth/BluetoothMediaBrowserService/2 (userId=0)
      ownerPid=1108, ownerUid=1002, userId=0
      package=com.google.android.bluetooth
      launchIntent=null
      mediaButtonReceiver=null
      active=false
      flags=3
      rating type=0
      controllers: 0
      state=PlaybackState {state=ERROR(7), position=0, buffered position=0, speed=0.0, updated=20393, actions=0, custom actions=[], active item id=-1, error=Bluetooth audio disconnected}
      audioAttrs=AudioAttributes: usage=USAGE_MEDIA content=CONTENT_TYPE_UNKNOWN flags=0x800 tags= bundle=null
      metadata: null
      queueTitle=Now Playing, size=0
    """.blockOnly()

    // 去掉原始字符串开头的换行：真实输出里相邻的会话块之间没有空行，块的边界只能靠缩进判断
    private fun String.blockOnly(): String = removePrefix("\n").trimEnd()

    private fun session(tag: String, id: Int, pkg: String, active: Boolean, stateLine: String) = """
    $tag $pkg/$tag/$id (userId=0)
      ownerPid=2635, ownerUid=10209, userId=0
      package=$pkg
      launchIntent=null
      mediaButtonReceiver=null
      active=$active
      flags=0
      rating type=0
      controllers: 1
      $stateLine
      audioAttrs=AudioAttributes: usage=USAGE_MEDIA content=CONTENT_TYPE_UNKNOWN flags=0x800 tags= bundle=null
      metadata: null
      queueTitle=null, size=0
    """.blockOnly()

    private fun dump(mediaButtonLine: String, vararg sessions: String): String = buildString {
        appendLine("MEDIA SESSION SERVICE (dumpsys media_session)")
        appendLine()
        appendLine("User Records:")
        appendLine("Record for full_user=0")
        appendLine("  Last MediaButtonReceiver: null")
        appendLine("  $mediaButtonLine")
        appendLine("  Sessions Stack - have ${sessions.size} sessions:")
        sessions.forEach { appendLine(it) }
        appendLine("  Audio playback (lastly played comes first)")
        appendLine("  Media session config:")
        appendLine("    media_button_receiver_fgs_allowlist_duration_ms: [cur: 10000, def: 10000]")
        appendLine()
    }

    private val paused = "state=PlaybackState {state=PAUSED(2), position=-1, buffered position=0, speed=0.0, updated=58469, actions=518, custom actions=[], active item id=-1, error=null}"
    private val playing = "state=PlaybackState {state=PLAYING(3), position=-1, buffered position=0, speed=0.0, updated=63299, actions=518, custom actions=[], active item id=-1, error=null}"

    @Test
    fun findsTheActivePausedSessionByPackageAndTag() {
        val parsed = MediaSessionDump.parse(
            dump("Media button session is null", session("AuriHeadsetControl", 10, "app.auri.platform.test", true, paused), btSession),
        )

        val ours = parsed.find("app.auri.platform.test", "AuriHeadsetControl")

        assertNotNull(ours)
        assertTrue("active=true 要读成已激活", ours!!.active)
        assertEquals("PAUSED", ours.state)
        assertEquals(2, parsed.sessions.size)
    }

    @Test
    fun anotherSessionsFieldsNeverLeakIntoThisOne() {
        // 蓝牙服务那条是 active=false、ERROR：如果块的边界划错，会把它的字段读成“我们的”（或反过来）
        val parsed = MediaSessionDump.parse(
            dump("Media button session is null", btSession, session("AuriHeadsetControl", 10, "app.auri.platform.test", true, paused)),
        )

        val bluetooth = parsed.find("com.google.android.bluetooth", "BluetoothMediaBrowserService")!!
        val ours = parsed.find("app.auri.platform.test", "AuriHeadsetControl")!!

        assertFalse(bluetooth.active)
        assertEquals("ERROR", bluetooth.state)
        assertTrue(ours.active)
        assertEquals("PAUSED", ours.state)
    }

    @Test
    fun noSessionWithThatTagMeansNull() {
        val parsed = MediaSessionDump.parse(dump("Media button session is null", btSession))

        assertNull(parsed.find("app.auri.platform.test", "AuriHeadsetControl"))
        assertEquals(1, parsed.sessions.size)
    }

    @Test
    fun aSessionWithTheSameTagFromAnotherPackageIsNotOurs() {
        val parsed = MediaSessionDump.parse(
            dump("Media button session is null", session("AuriHeadsetControl", 10, "com.other.app", true, paused)),
        )

        assertNull(parsed.find("app.auri.platform.test", "AuriHeadsetControl"))
        assertNotNull(parsed.find("com.other.app", "AuriHeadsetControl"))
    }

    @Test
    fun playingIsReadAsPlayingNotAsPaused() {
        val parsed = MediaSessionDump.parse(
            dump("Media button session is null", session("AuriHeadsetControl", 10, "app.auri.platform.test", true, playing)),
        )

        assertEquals("PLAYING", parsed.find("app.auri.platform.test", "AuriHeadsetControl")!!.state)
    }

    @Test
    fun aSessionThatNeverDeclaredAPlaybackStateHasNullState() {
        val parsed = MediaSessionDump.parse(
            dump("Media button session is null", session("AuriHeadsetControl", 10, "app.auri.platform.test", true, "state=null")),
        )

        assertNull(parsed.find("app.auri.platform.test", "AuriHeadsetControl")!!.state)
    }

    @Test
    fun inactiveIsReadAsInactive() {
        val parsed = MediaSessionDump.parse(
            dump("Media button session is null", session("AuriHeadsetControl", 10, "app.auri.platform.test", false, paused)),
        )

        assertFalse(parsed.find("app.auri.platform.test", "AuriHeadsetControl")!!.active)
    }

    @Test
    fun theMediaButtonSessionLineIsReadButNeverMistakenForASession() {
        val none = MediaSessionDump.parse(dump("Media button session is null", btSession))
        val some = MediaSessionDump.parse(
            dump(
                "Media button session is app.auri.platform.test/AuriHeadsetControl/11 (userId=0)",
                session("AuriHeadsetControl", 11, "app.auri.platform.test", true, playing),
            ),
        )

        assertNull("系统没选任何会话", none.mediaButtonSession)
        assertEquals("app.auri.platform.test/AuriHeadsetControl/11", some.mediaButtonSession)
        assertEquals("那一行不是会话块：会话数只算真正的块", 1, some.sessions.size)
    }

    @Test
    fun anEmptyOrUnrelatedDumpHasNoSessionsAndNoMediaButtonSession() {
        val parsed = MediaSessionDump.parse("Can't find service: media_session\n")

        assertEquals(emptyList<MediaSessionInfo>(), parsed.sessions)
        assertNull(parsed.mediaButtonSession)
    }
}
