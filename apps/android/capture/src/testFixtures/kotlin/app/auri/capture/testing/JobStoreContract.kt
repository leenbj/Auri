package app.auri.capture.testing

import app.auri.capture.contract.ErrorCode
import app.auri.capture.machine.TranscriptionState
import app.auri.capture.scheduler.JobKind
import app.auri.capture.scheduler.JobStore
import app.auri.capture.scheduler.ResourceReason
import app.auri.capture.scheduler.TranscriptionJob
import app.auri.capture.scheduler.WaitReason
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务库的行为合同：内存实现与 Android 上的 SQLite 实现（instrumented 测试）跑同一套。
 * 任务库就是“重启之后任务还在”的那一层：存进去的每一个字段读出来都必须一模一样，同一个任务再存是更新而不是新增。
 * 时间只保证到毫秒（`Clock.now()` 给的就是毫秒精度）。
 */
abstract class JobStoreContract {
    /** 一个全新、空的任务库。 */
    protected abstract fun create(): JobStore

    /** 模拟进程重启：关掉 [previous] 再打开同一份持久数据。内存实现没有“重启”，返回同一个对象。 */
    protected abstract fun reopen(previous: JobStore): JobStore

    protected fun job(
        n: Int,
        state: TranscriptionState = TranscriptionState.QUEUED,
        seq: Long = n.toLong(),
    ) = TranscriptionJob(
        jobId = "%08x-0000-4000-8000-%012x".format(0x77, n),
        recordId = Samples.uuid(n),
        kind = JobKind.INITIAL,
        seq = seq,
        state = state,
        createdAt = Instant.ofEpochMilli(1_800_000_000_000L + n),
        updatedAt = Instant.ofEpochMilli(1_800_000_001_000L + n),
    )

    @Test
    fun `空的任务库什么都没有`() = runTest {
        assertTrue(create().all().isEmpty())
    }

    @Test
    fun `存进去的每一个字段读出来一模一样，重启之后也是`() = runTest {
        val full = job(1, TranscriptionState.WAITING_RESOURCES).copy(
            kind = JobKind.RETRANSCRIBE,
            attempts = 2,
            baseRevisions = 3,
            wait = WaitReason.Resources(ResourceReason.THERMAL),
            waitChecks = 4,
            needsUser = true,
            retryAt = Instant.ofEpochMilli(1_800_000_090_000L),
            failure = ErrorCode.ASR_RESOURCE_LIMIT,
            resultRevision = 5,
            modelId = "sensevoice-small-int8",
            modelVersion = "2024-07-17+7d1efa2138a6",
        )
        val minimal = job(2)
        val store = create()

        store.put(full)
        store.put(minimal)

        assertEquals(listOf(full, minimal), store.all())
        assertEquals("重启之后还在", listOf(full, minimal), reopen(store).all())
    }

    @Test
    fun `等待原因的每一种取值都能存取`() = runTest {
        val store = create()
        val waits: List<WaitReason?> =
            listOf(null, WaitReason.Model(ErrorCode.MODEL_NOT_INSTALLED), WaitReason.Model(ErrorCode.MODEL_INTEGRITY_FAILED)) +
                ResourceReason.entries.map { WaitReason.Resources(it) }
        waits.forEachIndexed { index, wait -> store.put(job(index + 1).copy(wait = wait)) }

        assertEquals(waits, reopen(store).all().map { it.wait })
    }

    @Test
    fun `同一个任务再存一次是更新，不会多出一行`() = runTest {
        val store = create()
        store.put(job(1))

        store.put(job(1, TranscriptionState.RUNNING).copy(attempts = 1))
        store.put(job(1, TranscriptionState.SUCCEEDED).copy(attempts = 1, resultRevision = 1))

        val all = reopen(store).all()
        assertEquals(1, all.size)
        assertEquals(TranscriptionState.SUCCEEDED, all.single().state)
        assertEquals(1, all.single().resultRevision)
    }

    @Test
    fun `读出来按任务的先后序号排，和存的顺序无关`() = runTest {
        val store = create()
        store.put(job(1, seq = 30))
        store.put(job(2, seq = 10))
        store.put(job(3, seq = 20))

        assertEquals(listOf(10L, 20L, 30L), reopen(store).all().map { it.seq })
    }

    @Test
    fun `每一种状态都能存取（含合同里预留的）`() = runTest {
        val store = create()
        TranscriptionState.entries.forEachIndexed { index, state -> store.put(job(index + 1, state)) }

        assertEquals(TranscriptionState.entries, reopen(store).all().map { it.state })
    }

    /** 任务库不依赖 Record 库（两个库各自落盘，对账由调度器做）：一个库里没有的 Record 标识也能存。 */
    @Test
    fun `任务库不要求 Record 存在`() = runTest {
        val store = create()
        store.put(job(1).copy(recordId = Samples.uuid(999)))

        assertEquals(Samples.uuid(999), reopen(store).all().single().recordId)
    }
}
