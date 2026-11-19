package app.auri.sync.testing

import app.auri.sync.engine.BlockedReason
import app.auri.sync.engine.JobKey
import app.auri.sync.engine.NoSuchJobException
import app.auri.sync.engine.SyncJobStore
import app.auri.sync.machine.SyncState
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 同步任务表的行为合同。内存实现（JVM 单测）与 :syncplatform 的 SQLite 实现（模拟器上的仪器测试）都要通过同一套，
 * 这样 JVM 上验证过的引擎逻辑，换到真正的数据库上语义不变。
 */
abstract class SyncJobStoreContract {
    abstract fun newStore(): SyncJobStore

    private val t0 = Instant.parse("2026-11-18T08:00:00.123Z")
    private val record = "a4a7c08d-2767-50d6-9238-6fa277033a49"
    private val other = "b5b8d19e-3878-5ae7-a349-7fb388144b5a"

    @Test
    fun enqueue_creates_a_pending_job_and_is_idempotent() = runBlocking {
        val store = newStore()
        val key = JobKey(record, 1)

        assertTrue(store.enqueue(key, t0))
        assertFalse("同一个键再入队什么都不做", store.enqueue(key, t0.plusSeconds(60)))

        val job = store.get(key)!!
        assertEquals(SyncState.PENDING, job.state)
        assertEquals(0, job.attempts)
        assertFalse(job.uncertain)
        assertNull(job.retryAt)
        assertNull(job.blockedReason)
        assertEquals(t0, job.createdAt)
        assertEquals("第二次入队不能改动已有任务", t0, job.updatedAt)
    }

    @Test
    fun jobs_are_keyed_by_record_and_revision() = runBlocking {
        val store = newStore()
        assertTrue(store.enqueue(JobKey(record, 1), t0))
        assertTrue(store.enqueue(JobKey(record, 2), t0.plusMillis(1)))
        assertTrue(store.enqueue(JobKey(other, 1), t0.plusMillis(2)))

        assertEquals(listOf(JobKey(record, 1), JobKey(record, 2), JobKey(other, 1)), store.all().map { it.key })
        assertNull(store.get(JobKey(other, 2)))
    }

    @Test
    fun all_returns_oldest_first() = runBlocking {
        val store = newStore()
        store.enqueue(JobKey(other, 1), t0.plusSeconds(5))
        store.enqueue(JobKey(record, 1), t0)

        assertEquals(listOf(JobKey(record, 1), JobKey(other, 1)), store.all().map { it.key })
    }

    @Test
    fun update_round_trips_every_field() = runBlocking {
        val store = newStore()
        val key = JobKey(record, 3)
        store.enqueue(key, t0)
        val changed = store.get(key)!!.copy(
            state = SyncState.BLOCKED,
            attempts = 7,
            uncertain = true,
            retryAt = t0.plusSeconds(90),
            blockedReason = BlockedReason.NOT_ORIGIN_DEVICE,
            updatedAt = t0.plusSeconds(10),
        )

        store.update(changed)

        assertEquals(changed, store.get(key))
        assertEquals(listOf(changed), store.all())
        store.update(changed.copy(retryAt = null, blockedReason = null, uncertain = false, state = SyncState.PENDING))
        val cleared = store.get(key)!!
        assertNull(cleared.retryAt)
        assertNull(cleared.blockedReason)
        assertFalse(cleared.uncertain)
    }

    @Test
    fun update_of_an_unknown_job_fails_loudly() = runBlocking {
        val store = newStore()
        val key = JobKey(record, 1)
        store.enqueue(key, t0)
        val job = store.get(key)!!

        try {
            store.update(job.copy(key = JobKey(record, 99)))
            fail("更新不存在的任务必须报错")
        } catch (expected: NoSuchJobException) {
            assertEquals(JobKey(record, 99), expected.key)
        }
        assertEquals(listOf(job), store.all())
    }

    @Test
    fun observe_emits_the_latest_list_after_every_change() = runBlocking {
        val store = newStore()
        assertEquals(emptyList<Any>(), store.observe().first())

        store.enqueue(JobKey(record, 1), t0)
        assertEquals(1, store.observe().first().size)

        store.update(store.get(JobKey(record, 1))!!.copy(state = SyncState.STORED))
        assertEquals(SyncState.STORED, store.observe().first().single().state)
    }
}
