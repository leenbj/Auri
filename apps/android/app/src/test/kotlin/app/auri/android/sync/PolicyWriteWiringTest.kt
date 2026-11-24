package app.auri.android.sync

import app.auri.android.netguard.SourceText
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第四轮 1 的接线守卫：SQLite 的授权设置要 Android 的 SQLiteDatabase，JVM 跑不了；它的写入协议（取凭据、开窗口、写块在数据库线程上做完再关、
 * 外层 finally 再关一次）在 :sync 的 PolicyWriteProtocolTest 里用**同形**的协议摆过每种“写块没按常理走完”的情形。
 * 这里钉住生产源码确实是那个形状——少了外层 finally，被取消的写会让窗口永远开着（之后每个请求都被拦）；
 * 少了数据库线程上的 finally，窗口会在写提交之前就关（请求带着旧授权出去）。做法同 PolicyGateWiringTest：读生产源码（去掉注释与字符串字面量）。
 */
class PolicyWriteWiringTest {
    private val source = SourceText.withoutCommentsAndStrings(File("../syncplatform/src/main/kotlin/app/auri/syncplatform/policy/SqliteDataFlowPolicy.kt").readText())
        .replace(Regex("""\s+"""), " ")

    @Test
    fun `SqliteDataFlowPolicy 的 write 取一张凭据：数据库线程上的 finally 与外层 finally 各关一次窗口（凭据保证只算一次）`() {
        val write = source.substringAfter("private suspend fun write(").substringBefore("changes.value += 1")

        assertTrue(
            "write 必须是：取凭据，try { 开窗口；在数据库线程上 try 写、finally 关 } finally { 再关一次 }：$write",
            "val generations = this.generations.write() try { generations.changing() library.withDatabase { db -> try { DataFlowPolicySql(AndroidSqlDb(db)).change() } finally { generations.changed() } } } finally { generations.changed() }" in write,
        )
    }

    @Test
    fun `世代的开窗口与关窗口只经过 write 里的这张凭据：别处直接 changing 或 changed 会让窗口开了没人关`() {
        val outsideWrite = source.replace(source.substringAfter("private suspend fun write(").substringBefore("changes.value += 1"), "")

        assertTrue("write 之外不许碰窗口：$outsideWrite", Regex("""\.(changing|changed)\(""").find(outsideWrite) == null)
    }
}
