package app.auri.syncplatform

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import app.auri.capture.testing.Samples
import app.auri.platform.library.SqliteRecordLibrary
import app.auri.sync.engine.JobKey
import app.auri.sync.machine.SyncState
import app.auri.syncplatform.jobs.SqliteSyncJobStore
import java.io.File
import java.time.Instant
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

/**
 * 测试专用：在独立进程（:writer）里往真实的 SQLite 写数据，写到指定的一步就把自己“卡住”并留下信号文件，
 * 测试进程读到信号后用 SIGKILL 杀掉这个进程。这样得到的是真正的“进程在这一刻被杀”，不是模拟的异常。
 * 信号文件内容：`<模式> pid=<进程号>`；出错写 `FAILED …`。
 */
class WriterService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val mode = intent.getStringExtra(EXTRA_MODE)!!
        val database = intent.getStringExtra(EXTRA_DATABASE)!!
        val signal = File(intent.getStringExtra(EXTRA_SIGNAL)!!)
        val recordIndex = intent.getIntExtra(EXTRA_RECORD, 1)
        thread(name = "writer-$mode") {
            try {
                write(mode, database, signal, recordIndex)
            } catch (failure: Throwable) {
                signal.writeText("FAILED ${failure::class.java.name}: ${failure.message}")
            }
        }
        return START_NOT_STICKY
    }

    private fun write(mode: String, database: String, signal: File, recordIndex: Int) {
        fun park(): Nothing {
            signal.writeText("$mode pid=${Process.myPid()}")
            Thread.sleep(Long.MAX_VALUE)
            error("不会走到这里")
        }
        val library = SqliteRecordLibrary(
            applicationContext,
            database,
            publicationStep = { db, revision ->
                SqliteSyncJobStore.enqueueIn(db, JobKey(revision.recordId, revision.number), NOW)
                if (mode == IN_TRANSACTION) park() // 任务已写进事务、事务还没提交
            },
            extraSchema = SqliteSyncJobStore.SCHEMA,
        )
        runBlocking {
            val entry = Samples.entry(recordIndex)
            library.addRecord(entry)
            val revision = library.publishRevision(Samples.draft(entry.recordId, text = TEXT))
            if (mode == AFTER_COMMIT) park() // 事务已提交
            if (mode == SENDING) {
                // 引擎在请求发出之前就把“发送中 + 结果不确定”落盘；这里模拟它刚落盘、请求刚发出就被杀
                val jobs = SqliteSyncJobStore(library)
                val key = JobKey(entry.recordId, revision.number)
                jobs.update(checkNotNull(jobs.get(key)).copy(state = SyncState.SENDING, attempts = 1, uncertain = true, updatedAt = NOW.plusSeconds(1)))
                park()
            }
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_DATABASE = "database"
        const val EXTRA_SIGNAL = "signal"
        const val EXTRA_RECORD = "record"

        /** 任务已入队、发布的事务还没提交时被杀。 */
        const val IN_TRANSACTION = "IN_TRANSACTION"

        /** 发布已提交（Revision 与任务都在）之后被杀。 */
        const val AFTER_COMMIT = "AFTER_COMMIT"

        /** 任务已落成“发送中 + 结果不确定”之后被杀。 */
        const val SENDING = "SENDING"

        const val TEXT = "进程被杀的那一刻，这段文字要么完整地在，要么完全不在"
        val NOW: Instant = Instant.parse("2026-11-18T10:00:00Z")
    }
}
