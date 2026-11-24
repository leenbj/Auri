package app.auri.sync.gateway

import app.auri.capture.contract.ErrorCode
import app.auri.capture.testing.Samples
import app.auri.sync.pairing.DeviceToken
import app.auri.sync.pairing.GatewayAddress
import app.auri.sync.testing.ScriptedExchange
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-TXT-005 / FR-SYNC-002：DELETE /v1/records/{record_id} 客户端合同测试。 */
class GatewayClientDeleteTest {
    private val address = GatewayAddress("127.0.0.1", 8443)
    private val token = checkNotNull(DeviceToken.parse("dev_fixture_token_0123456789abcdef"))
    private val recordId = Samples.uuid(1)
    private val deliveryId = Samples.uuid(90)

    @Test
    fun `DELETE 成功：解析 record_deleted 响应并核对 record_id 与取消的投递列表`() = runBlocking {
        val exchange = ScriptedExchange { _, _ ->
            ScriptedExchange.json(
                200,
                """
                {
                  "schema_version": "1.2",
                  "record_id": "$recordId",
                  "deleted_at": "2026-11-24T10:00:00Z",
                  "cancelled_delivery_ids": ["$deliveryId"]
                }
                """.trimIndent(),
            )
        }
        val client = GatewayClient(exchange)

        val result = client.deleteRecord(address, token, recordId)

        assertTrue(result is GatewayResult.Success<*>)
        val value = (result as GatewayResult.Success<RecordDeletedResponse>).value
        assertEquals(recordId, value.recordId)
        assertEquals(listOf(deliveryId), value.cancelledDeliveryIds)
        assertEquals("DELETE", exchange.calls.single().second.method)
        assertEquals("/v1/records/$recordId", exchange.calls.single().second.path)
    }

    @Test
    fun `响应里的 record_id 与请求不一致时判为 Garbled`() = runBlocking {
        val otherId = Samples.uuid(2)
        val exchange = ScriptedExchange { _, _ ->
            ScriptedExchange.json(
                200,
                """
                {
                  "schema_version": "1.2",
                  "record_id": "$otherId",
                  "deleted_at": "2026-11-24T10:00:00Z",
                  "cancelled_delivery_ids": []
                }
                """.trimIndent(),
            )
        }
        val client = GatewayClient(exchange)

        val result = client.deleteRecord(address, token, recordId)

        assertTrue("回答了别的 record_id 必须拒收：$result", result is GatewayResult.Garbled)
    }

    @Test
    fun `非来源设备删除返回 403 RECORD_NOT_ORIGIN_DEVICE`() = runBlocking {
        val exchange = ScriptedExchange { _, _ ->
            ScriptedExchange.json(
                403,
                """{"schema_version":"1.2","code":"RECORD_NOT_ORIGIN_DEVICE","message":"forbidden","retryable":false}""",
            )
        }
        val client = GatewayClient(exchange)

        val result = client.deleteRecord(address, token, recordId)

        assertTrue(result is GatewayResult.Rejected)
        val rejected = result as GatewayResult.Rejected
        assertEquals(403, rejected.status)
        assertEquals(ErrorCode.RECORD_NOT_ORIGIN_DEVICE, rejected.code)
        assertEquals(false, rejected.retryable)
    }
}
