package app.auri.syncplatform

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.auri.platform.library.SqliteRecordLibrary
import app.auri.platform.system.SystemClock
import app.auri.sync.SyncLog
import app.auri.sync.pairing.FilePairingStore
import app.auri.sync.pairing.SecretBox
import app.auri.sync.testing.FakeDiscovery
import app.auri.sync.testing.FakeNetwork
import app.auri.sync.testing.SyncSamples
import app.auri.sync.tls.SpkiPin
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同步出了故障不能带倒整个进程：录音与同步在同一个进程里，而 [SyncRuntime.start] 跑在整个应用共用的作用域上——
 * 那里没有异常处理器，协程里逃出来的异常在 Android 上就是应用崩溃（连正在录的音一起带走）。
 * 这里让启动时读任务表出故障（模拟数据库出了问题），要求 start 把故障记下来、同步循环照常起来，不留任何未捕获的异常。
 */
class SyncRuntimeStartupTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "sync-start-${UUID.randomUUID()}.db"
    private val pairingFile = File(context.filesDir, "start-test-${UUID.randomUUID()}.sealed")
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private val logLines = CopyOnWriteArrayList<String>()

    // 应用里的作用域没有异常处理器，未捕获就是崩溃；这里的处理器只是把“崩溃”记下来让测试看得到
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, failure -> uncaught += failure })

    // 这个测试不涉及加密（那是 KeystoreSecretBoxTest 的事）：原样进出的封存盒
    private val plain = object : SecretBox {
        override fun seal(plain: ByteArray): ByteArray = plain

        override fun open(sealed: ByteArray): ByteArray? = sealed
    }
    private var library: SqliteRecordLibrary? = null

    @After
    fun cleanUp() {
        scope.cancel()
        library?.close()
        context.deleteDatabase(databaseName)
        pairingFile.delete()
        File(pairingFile.path + ".tmp").delete()
    }

    @Test
    fun 启动时读任务表出了故障_不留未捕获的异常_同步循环照常起来() = runBlocking {
        val opened = SyncStorage.library(context.applicationContext as Application, databaseName).also { library = it }
        // 先配好对：没配对时启动那一步根本不碰任务表。指纹不用 SyncSamples 的默认值（那是 keytool 现场生成的证书，Android 上没有 keytool）
        FilePairingStore(pairingFile, plain).save(SyncSamples.pairedGateway(pin = SpkiPin.parse("1f".repeat(32))!!))
        // 任务表读不了：和磁盘、数据库出故障一样，读它会抛 SQLiteException
        opened.withDatabase { it.execSQL("DROP TABLE sync_jobs") }
        val runtime = SyncRuntime(opened, pairingFile, plain, scope, SystemClock, deviceInfo("test"), FakeDiscovery(), FakeNetwork(), log = SyncLog { logLines += it })

        val job = runtime.start()
        val ended = withTimeoutOrNull(3_000) { job.join() }

        assertNull("启动出了故障，同步循环也要照常起来：start 的协程不能就此结束", ended)
        assertTrue(job.isActive)
        assertEquals("不能有未捕获的异常（在应用里就是崩溃）", emptyList<Throwable>(), uncaught.toList())
        assertTrue("故障要记一条日志（只有异常类名）：$logLines", logLines.any { it.startsWith("start.failed ") })
    }
}
