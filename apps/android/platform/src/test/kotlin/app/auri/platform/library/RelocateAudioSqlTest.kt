package app.auri.platform.library

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ticket 19 评审 H1 的 SQL 层：改音频位置的那一条语句只许改“以老目录开头”的行、只改那个开头、改过的不再被改。
 * 语句错了的后果是静默的——要么没改（转录打开不存在的文件），要么改到不该改的位置（指向别的文件）。
 */
class RelocateAudioSqlTest {
    private val rows = RecordRows()
    private val from = "/data/user/0/app.auri.android/files/recordings/"
    private val to = "/data/user/0/app.auri.android/no_backup/recordings/"

    @After
    fun close() = rows.close()

    @Test
    fun `老目录下的位置换成新目录，文件名原样保留，返回改了几行`() {
        rows.insertLocation("a", "${from}0000abc-1.wav")
        rows.insertLocation("b", "${from}0000abc-2.wav")

        val changed = rows.relocate(from, to)

        assertEquals(2, changed)
        assertEquals("${to}0000abc-1.wav", rows.locationOf("a"))
        assertEquals("${to}0000abc-2.wav", rows.locationOf("b"))
    }

    @Test
    fun `不以老目录开头的行一律不动：别的目录、名字相似的目录、没有音频、不是文件路径`() {
        val untouched = mapOf(
            "other" to "/sdcard/Music/0000abc-1.wav",
            "similar" to "/data/user/0/app.auri.android/files/recordings-old/0000abc-1.wav", // 老目录名只是它的前缀
            "sibling" to "/data/user/0/app.auri.android/files/recordings2/0000abc-1.wav",
            "inside" to "/elsewhere$from" + "0000abc-1.wav", // 老前缀出现在中间不算
            "fake" to "fake://0000abc-1.wav",
            "already" to "${to}0000abc-1.wav",
            "none" to null,
        )
        untouched.forEach { (id, location) -> rows.insertLocation(id, location) }
        rows.insertLocation("moved", "${from}0000abc-1.wav")

        val changed = rows.relocate(from, to)

        assertEquals("只有老目录下的那一行被改", 1, changed)
        untouched.forEach { (id, location) -> assertEquals("$id 不该被改", location, rows.locationOf(id)) }
        assertEquals("${to}0000abc-1.wav", rows.locationOf("moved"))
        assertNull(rows.locationOf("none"))
    }

    @Test
    fun `重复执行什么也不再改（幂等）`() {
        rows.insertLocation("a", "${from}0000abc-1.wav")

        assertEquals(1, rows.relocate(from, to))
        assertEquals("第二次：没有行再以老目录开头", 0, rows.relocate(from, to))
        assertEquals("${to}0000abc-1.wav", rows.locationOf("a"))
    }

    @Test
    fun `路径里的百分号与下划线不是通配符`() {
        val odd = "/data/odd%dir_x/recordings/"
        rows.insertLocation("literal", "${odd}0000abc-1.wav")
        rows.insertLocation("wildcard-percent", "/data/oddXYZdir_x/recordings/0000abc-1.wav") // % 若被当通配符会匹配这一行
        rows.insertLocation("wildcard-underscore", "/data/odd%dirAx/recordings/0000abc-1.wav") // _ 若被当通配符会匹配这一行

        val changed = rows.relocate(odd, to)

        assertEquals(1, changed)
        assertEquals("${to}0000abc-1.wav", rows.locationOf("literal"))
        assertEquals("/data/oddXYZdir_x/recordings/0000abc-1.wav", rows.locationOf("wildcard-percent"))
        assertEquals("/data/odd%dirAx/recordings/0000abc-1.wav", rows.locationOf("wildcard-underscore"))
    }

    @Test
    fun `前缀写法带结尾的斜杠，目录名只是另一个目录名的前缀时不会误伤`() {
        assertEquals("/a/b/recordings/", audioDirPrefix(File("/a/b/recordings")))
        assertEquals("结尾已经有斜杠也只留一个", "/a/b/recordings/", audioDirPrefix(File("/a/b/recordings/")))
    }
}
