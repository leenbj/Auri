package app.auri.sync.engine

import app.auri.capture.testing.Samples
import app.auri.sync.discovery.GatewayDiscovery
import app.auri.sync.discovery.GatewayLocator
import app.auri.sync.http.HttpExchangeFactory
import app.auri.sync.http.TransportFailure
import app.auri.sync.pairing.GatewayAddress
import app.auri.sync.testing.InMemoryDataFlowPolicy
import app.auri.sync.testing.InMemoryPairingStore
import app.auri.sync.testing.InMemorySyncJobStore
import app.auri.sync.testing.ScriptedExchange
import app.auri.sync.testing.SyncSamples
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-TXT-005 / FR-SYNC-002 / SEC-002：SyncEngine 同步删除墓碑到网关。 */
class SyncEngineDeletionTest {
    private class FakePendingDeletions(initial: List<String> = emptyList()) : PendingDeletions {
        private val items = linkedSetOf<String>().apply { addAll(initial) }
        private val signal = MutableStateFlow(0L)

        override suspend fun pending(): List<String> = items.toList()

        override suspend fun markSynced(recordId: String) {
            if (items.remove(recordId)) signal.value += 1
        }

        override fun changes(): Flow<Long> = signal
    }

    private object NoDiscovery : GatewayDiscovery {
        override suspend fun find(gatewayId: String): List<GatewayAddress> = emptyList()
    }

    private val clock = object : app.auri.capture.coordinator.Clock {
        private var now = Instant.parse("2026-11-24T10:00:00Z")
        override fun monotonicNs() = 0L
        override fun now() = now
    }

    private val capabilitiesReply = ScriptedExchange.json(
        200,
        """{"schema_version":"1.2","gateway_version":"0.1.0","supported_schema_versions":["1.2"],"features":{"cloud_asr":false,"record_read_tools":true,"delivery_tools":true,"mdns_discovery":true,"lan_only_tls":true},"limits":{"max_text_bytes":1048576,"max_destinations_per_publication":50,"record_list_default_limit":50,"record_list_max_limit":200,"record_search_default_limit":20,"record_search_max_limit":100,"delivery_claim_default_limit":10,"delivery_claim_max_limit":50,"delivery_lease_default_seconds":120,"delivery_lease_min_seconds":10,"delivery_lease_max_seconds":600,"pairing_ttl_max_seconds":300}}""",
    )

    @Test
    fun `待同步的删除墓碑会向网关发 DELETE 并标记已同步`() = runBlocking {
        val recordId = Samples.uuid(1)
        val gateway = SyncSamples.pairedGateway()
        val pairing = InMemoryPairingStore(gateway)
        val policy = InMemoryDataFlowPolicy(gateway = gateway.identity).apply { grantTo(gateway.identity) }
        val deletions = FakePendingDeletions(listOf(recordId))
        val requests = mutableListOf<String>()
        val http = HttpExchangeFactory { _ ->
            ScriptedExchange { _, req ->
                requests += "${req.method} ${req.path}"
                when {
                    req.path == "/health" -> ScriptedExchange.json(200, """{"status":"ok"}""")
                    req.path == "/v1/capabilities" -> capabilitiesReply
                    req.method == "DELETE" && req.path == "/v1/records/$recordId" -> ScriptedExchange.json(
                        200,
                        """{"schema_version":"1.2","record_id":"$recordId","deleted_at":"2026-11-24T10:00:00Z","cancelled_delivery_ids":[]}""",
                    )
                    else -> error("unexpected request: ${req.method} ${req.path}")
                }
            }
        }
        val engine = SyncEngine(
            jobs = InMemorySyncJobStore(),
            pairing = pairing,
            source = SubmissionSource { null },
            http = http,
            locator = GatewayLocator(http, NoDiscovery),
            clock = clock,
            policy = policy,
            deletions = deletions,
        )

        engine.runOnce()

        assertTrue("必须发 DELETE /v1/records/$recordId：$requests", "DELETE /v1/records/$recordId" in requests)
        assertTrue("同步成功后墓碑已标为已同步", deletions.pending().isEmpty())
    }

    @Test
    fun `严格仅本地开启时不发任何 DELETE 请求，关闭后自动补发`() = runBlocking {
        val recordId = Samples.uuid(2)
        val gateway = SyncSamples.pairedGateway()
        val pairing = InMemoryPairingStore(gateway)
        val policy = InMemoryDataFlowPolicy(gateway = gateway.identity).apply {
            grantTo(gateway.identity)
            setStrictLocal(true)
        }
        val deletions = FakePendingDeletions(listOf(recordId))
        val requests = mutableListOf<String>()
        val http = HttpExchangeFactory { _ ->
            ScriptedExchange { _, req ->
                requests += "${req.method} ${req.path}"
                when {
                    req.path == "/health" -> ScriptedExchange.json(200, """{"status":"ok"}""")
                    req.path == "/v1/capabilities" -> capabilitiesReply
                    req.method == "DELETE" && req.path == "/v1/records/$recordId" -> ScriptedExchange.json(
                        200,
                        """{"schema_version":"1.2","record_id":"$recordId","deleted_at":"2026-11-24T10:00:00Z","cancelled_delivery_ids":[]}""",
                    )
                    else -> error("unexpected request: ${req.method} ${req.path}")
                }
            }
        }
        val engine = SyncEngine(
            jobs = InMemorySyncJobStore(),
            pairing = pairing,
            source = SubmissionSource { null },
            http = http,
            locator = GatewayLocator(http, NoDiscovery),
            clock = clock,
            policy = policy,
            deletions = deletions,
        )

        engine.runOnce()
        assertTrue("严格仅本地开启时零请求", requests.isEmpty())
        assertEquals(listOf(recordId), deletions.pending())

        policy.setStrictLocal(false)
        engine.runOnce()
        assertTrue("关闭严格仅本地后补发 DELETE", "DELETE /v1/records/$recordId" in requests)
        assertTrue(deletions.pending().isEmpty())
    }

    @Test
    fun `离线时删除墓碑保持待同步，恢复联网后补发成功`() = runBlocking {
        val recordId = Samples.uuid(3)
        val gateway = SyncSamples.pairedGateway()
        val pairing = InMemoryPairingStore(gateway)
        val policy = InMemoryDataFlowPolicy(gateway = gateway.identity).apply { grantTo(gateway.identity) }
        val deletions = FakePendingDeletions(listOf(recordId))
        var online = false
        val http = HttpExchangeFactory { _ ->
            ScriptedExchange { _, req ->
                if (!online) throw TransportFailure.Unreachable(null)
                when {
                    req.path == "/health" -> ScriptedExchange.json(200, """{"status":"ok"}""")
                    req.path == "/v1/capabilities" -> capabilitiesReply
                    req.method == "DELETE" && req.path == "/v1/records/$recordId" -> ScriptedExchange.json(
                        200,
                        """{"schema_version":"1.2","record_id":"$recordId","deleted_at":"2026-11-24T10:00:00Z","cancelled_delivery_ids":[]}""",
                    )
                    else -> error("unexpected request: ${req.method} ${req.path}")
                }
            }
        }
        val engine = SyncEngine(
            jobs = InMemorySyncJobStore(),
            pairing = pairing,
            source = SubmissionSource { null },
            http = http,
            locator = GatewayLocator(http, NoDiscovery),
            clock = clock,
            policy = policy,
            deletions = deletions,
        )

        engine.runOnce()
        assertEquals("离线时仍待同步", listOf(recordId), deletions.pending())

        online = true
        engine.runOnce()
        assertTrue("恢复在线后补发成功", deletions.pending().isEmpty())
    }
}
