package app.auri.sync.policy

import app.auri.sync.discovery.GatewayLocator
import app.auri.sync.engine.JobKey
import app.auri.sync.engine.SendResult
import app.auri.sync.engine.SubmissionSource
import app.auri.sync.engine.SyncEngine
import app.auri.sync.gateway.RevisionSubmission
import app.auri.sync.http.HttpExchange
import app.auri.sync.http.HttpExchangeFactory
import app.auri.sync.http.HttpReply
import app.auri.sync.http.HttpRequest
import app.auri.sync.http.TransportFailure
import app.auri.sync.pairing.GatewayAddress
import app.auri.sync.testing.SyncRig
import app.auri.sync.testing.SyncSamples
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyHandoffRaceTest {
    @Test
    fun revocationDuringTheFirstProbeStopsTheNextProbe() = runTest {
        val rig = SyncRig(this)
        val key = rig.publish()
        val firstProbe = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val http = HttpExchangeFactory { pin ->
            val inner = rig.lan.factory.forPin(pin)
            object : HttpExchange {
                override suspend fun execute(address: GatewayAddress, request: HttpRequest): HttpReply {
                    attempts.incrementAndGet()
                    if (address == SyncSamples.NAS_ADDRESS) {
                        firstProbe.complete(Unit)
                        release.await()
                        throw TransportFailure.Unreachable()
                    }
                    return inner.execute(address, request)
                }
            }
        }
        val engine = SyncEngine(
            jobs = rig.jobs,
            pairing = rig.pairing,
            source = { rig.submissions[it] },
            http = http,
            locator = GatewayLocator(http, rig.discovery, rig.log),
            clock = rig.clock,
            policy = rig.policy,
        )
        val send = async { engine.send(key) }
        firstProbe.await()
        rig.policy.revokeGrant()
        release.complete(Unit)
        assertEquals("the job waits; nothing else was sent", SendResult.WAITING_GATEWAY, send.await())
        assertEquals("a revoked policy must prevent the second health request", 1, attempts.get())
        assertTrue("no request may reach the LAN after revocation", rig.lan.delivered.isEmpty())
    }

    /** Finding the gateway is several requests (one probe per address, then an mDNS query): each is its own handoff. */
    @Test
    fun revocationDuringTheLastStoredProbeStopsTheMdnsQuery() = runTest {
        val rig = SyncRig(this, gateway = SyncSamples.pairedGateway(addresses = listOf(SyncSamples.NAS_ADDRESS)))
        val key = rig.publish()
        val probing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val http = HttpExchangeFactory { _ ->
            object : HttpExchange {
                override suspend fun execute(address: GatewayAddress, request: HttpRequest): HttpReply {
                    attempts.incrementAndGet()
                    probing.complete(Unit)
                    release.await()
                    throw TransportFailure.Unreachable()
                }
            }
        }
        val engine = SyncEngine(
            jobs = rig.jobs,
            pairing = rig.pairing,
            source = { rig.submissions[it] },
            http = http,
            locator = GatewayLocator(http, rig.discovery, rig.log),
            clock = rig.clock,
            policy = rig.policy,
        )
        val send = async { engine.send(key) }
        probing.await()
        rig.policy.revokeGrant()
        release.complete(Unit)
        assertEquals("the job waits; nothing else was sent", SendResult.WAITING_GATEWAY, send.await())
        assertEquals(1, attempts.get())
        assertTrue("a revoked policy must prevent the mDNS query", rig.discovery.asked.isEmpty())
    }

    @Test
    fun aSuspendedRequestDoesNotHoldUpPolicyChanges() = runTest {
        val generation = PolicyGeneration()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val changed = CountDownLatch(1)
        val request = async(Dispatchers.Default) {
            PolicyGeneration.handoff({ true }) {
                entered.complete(Unit)
                release.await()
                1
            }
        }
        entered.await()
        val change = async(Dispatchers.Default) { generation.changing(); changed.countDown() }
        try {
            assertTrue("policy changes must not wait for the network response", withContext(Dispatchers.IO) { changed.await(5, TimeUnit.SECONDS) })
        } finally {
            release.complete(Unit)
        }
        change.await()
        assertEquals(1, request.await())
    }

    @Test
    fun revokedBetweenGenerationReadAndClientHandoffMustNotSendText() = runTest {
        val rig = SyncRig(this)
        val key = rig.publish()
        val armed = AtomicBoolean(false)
        val generationRead = CountDownLatch(1)
        val continueHandoff = CountDownLatch(1)
        val revocationDone = CountDownLatch(1)
        val sentAfterRevocation = AtomicBoolean(false)
        val http = HttpExchangeFactory { pin ->
            val inner = rig.lan.factory.forPin(pin)
            object : HttpExchange {
                override suspend fun execute(address: GatewayAddress, request: HttpRequest): HttpReply {
                    if (request.method == "PUT" && revocationDone.count == 0L) sentAfterRevocation.set(true)
                    return inner.execute(address, request)
                }
            }
        }
        val policy = object : DataFlowPolicy by rig.policy {
            override val generation: Long
                get() {
                    val observed = rig.policy.generation
                    if (armed.compareAndSet(true, false)) {
                        generationRead.countDown()
                        check(continueHandoff.await(10, TimeUnit.SECONDS))
                    }
                    return observed
                }
        }
        val source = object : SubmissionSource {
            override suspend fun textSha256For(key: JobKey): String? = rig.submissions[key]?.textSha256
            override suspend fun submissionFor(key: JobKey): RevisionSubmission? {
                armed.set(true)
                return rig.submissions[key]
            }
        }
        val engine = SyncEngine(
            jobs = rig.jobs,
            pairing = rig.pairing,
            source = source,
            http = http,
            locator = GatewayLocator(http, rig.discovery, rig.log),
            clock = rig.clock,
            policy = policy,
        )
        val send = async(Dispatchers.Default) { engine.send(key) }
        try {
            assertTrue(withContext(Dispatchers.IO) { generationRead.await(10, TimeUnit.SECONDS) })
            val revoke = async(Dispatchers.IO) {
                rig.policy.revokeGrant()
                revocationDone.countDown()
            }
            val revokedBeforeHandoff = withContext(Dispatchers.IO) { revocationDone.await(1, TimeUnit.SECONDS) }
            continueHandoff.countDown()
            revoke.await()
            send.await()
            assertTrue("a PUT must not be handed to the client after revocation", !sentAfterRevocation.get())
            if (revokedBeforeHandoff) {
                assertEquals("revocation completed before handoff; the PUT must not leave", 0,
                    rig.lan.delivered.count { it.second.method == "PUT" })
            }
        } finally {
            continueHandoff.countDown()
        }
    }
}
