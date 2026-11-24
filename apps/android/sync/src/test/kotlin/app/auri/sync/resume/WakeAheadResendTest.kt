package app.auri.sync.resume

import app.auri.capture.contract.RevisionSource
import app.auri.capture.testing.Samples
import app.auri.sync.destinations.ResendResult
import app.auri.sync.engine.JobKey
import app.auri.sync.gateway.Destination
import app.auri.sync.gateway.Intent
import app.auri.sync.gateway.SendMode
import app.auri.sync.testing.InMemoryDispatchFixture
import app.auri.sync.testing.SyncSamples
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** OCR #35：WakeAheadDispatchStore.requestResend 必须先登记叫醒再落账，系统拒绝叫醒时不落账。 */
class WakeAheadResendTest {
    private val now = Instant.parse("2026-11-24T12:00:00Z")

    @Test
    fun `系统允许叫醒时先登记叫醒再落重发账；系统拒绝时返回 WakeUnavailable 且不落账`() = runBlocking {
        val gateway = SyncSamples.pairedGateway()
        val fixture = InMemoryDispatchFixture()
        val destId = Samples.uuid(10)
        fixture.store.applyDestinations(
            gateway.identity,
            listOf(Destination(destId, "代理", SendMode.AUTO_SEND, setOf(Intent.CONTEXT), now, Samples.uuid(99), true, null, true)),
            authoritative = true,
            now = now,
        )
        val key = JobKey(Samples.uuid(1), 2)
        fixture.publish(key, RevisionSource.USER_EDIT, now)

        var scheduled = 0
        var shouldThrow = true
        val scheduler = object : ResumeScheduler {
            override fun ensureScheduled() {
                if (shouldThrow) throw IllegalStateException("os refused")
                scheduled += 1
            }

            override fun cancel() = Unit
        }
        val wrapped = WakeAheadDispatchStore(fixture.store, WakeGate(scheduler))

        assertEquals(ResendResult.WakeUnavailable, wrapped.requestResend(key, destId, Intent.CONTEXT, now))
        assertNull("系统拒绝叫醒时绝不落账", fixture.store.item(key))

        shouldThrow = false
        assertEquals(ResendResult.Requested, wrapped.requestResend(key, destId, Intent.CONTEXT, now))
        assertEquals(1, scheduled)
        assertEquals(listOf(destId), fixture.store.item(key)!!.targets.map { it.destinationId })
    }
}
