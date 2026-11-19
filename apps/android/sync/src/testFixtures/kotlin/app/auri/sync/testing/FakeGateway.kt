package app.auri.sync.testing

import app.auri.sync.engine.JobKey
import app.auri.sync.http.HttpReply
import app.auri.sync.http.HttpRequest
import app.auri.sync.http.TransportFailure
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 网关服务端逻辑的小模型（只含同步引擎用到的三个调用：健康检查、PUT 入库、查询 Record 状态），响应体的形状照合同样本。
 * 语义取自真网关：同一（Record, Revision）再提交相同正文 = 重复（duplicate=true），不同正文 = 409；没见过的 Record 查询 = 404 REQUEST_INVALID。
 * 它是系统边界上的替身；与真网关的对照由 realgateway 测试负责。
 */
class FakeGateway(private val now: () -> Instant, originDeviceId: String = SyncSamples.DEVICE_ID) {
    /** 网关上这些 Record 的来源设备（状态查询回答它）；测试改成别的设备，就是“别的手机录的”。 */
    @Volatile
    var originDeviceId: String = originDeviceId

    /** 网关上这些 Record 已被删除（状态查询回答 deleted=true；PUT 仍按原样处理，这里只模拟状态查询的说法）。 */
    @Volatile
    var deleted: Boolean = false

    sealed interface Fault {
        /** 连接层失败：请求没有到达服务器逻辑。 */
        data class Transport(val failure: TransportFailure) : Fault

        /** 服务器已经入库，响应在路上丢了：客户端只看到断线。 */
        data object StoreThenLoseResponse : Fault

        /** 请求在路上丢了：服务器什么都没收到，客户端只看到断线。 */
        data object LoseRequest : Fault

        /** 服务器回错误响应，什么都没入库。 */
        data class Reject(val status: Int, val code: String, val retryable: Boolean) : Fault
    }

    /** 服务器上记着的 Revision：键 → 正文哈希。 */
    val stored = LinkedHashMap<JobKey, String>()

    /** 到达服务器逻辑的 PUT / 状态查询，按顺序："PUT <record>#<n>"、"GET status <record>"（健康检查不记）。 */
    val calls = CopyOnWriteArrayList<String>()

    val putCount: Int get() = calls.count { it.startsWith("PUT ") }
    val statusCount: Int get() = calls.count { it.startsWith("GET status ") }

    /** 依次用在后面的 PUT / 状态查询上（一个请求用一个）。 */
    val faults = ConcurrentLinkedQueue<Fault>()

    /** 下一个 PUT / 状态查询在这里停住，直到测试给出结果（null = 正常处理）。用来让“发送中”这个状态可以被观察。 */
    @Volatile
    var holdNext: CompletableDeferred<Fault?>? = null

    private var operations = 0

    suspend fun handle(request: HttpRequest): HttpReply = when {
        request.method == "GET" && request.path == "/health" -> ScriptedExchange.json(200, """{"status":"ok"}""")
        request.method == "PUT" && PUT_PATH.matches(request.path) -> put(request)
        request.method == "GET" && STATUS_PATH.matches(request.path) -> status(request)
        else -> error(404, "REQUEST_INVALID", false)
    }

    private suspend fun nextFault(): Fault? {
        val gate = holdNext
        if (gate != null) {
            holdNext = null
            return gate.await()
        }
        return faults.poll()
    }

    private suspend fun put(request: HttpRequest): HttpReply {
        val (recordId, revision) = PUT_PATH.matchEntire(request.path)!!.destructured
        val key = JobKey(recordId, revision.toInt())
        calls += "PUT $recordId#$revision"
        val sha = Json.parseToJsonElement(request.body!!.toString(Charsets.UTF_8)).jsonObject.getValue("content").jsonObject.getValue("text_sha256").jsonPrimitive.content
        when (val fault = nextFault()) {
            is Fault.Transport -> throw fault.failure
            Fault.LoseRequest -> throw TransportFailure.Interrupted()
            is Fault.Reject -> return error(fault.status, fault.code, fault.retryable)
            Fault.StoreThenLoseResponse -> {
                stored.putIfAbsent(key, sha)
                throw TransportFailure.Interrupted()
            }
            null -> Unit
        }
        val existing = stored[key]
        if (existing != null && existing != sha) return error(409, "RECORD_VERSION_CONFLICT", false)
        stored.putIfAbsent(key, sha)
        operations += 1
        return ScriptedExchange.json(
            200,
            """{"schema_version":"1.1","operation_id":"${operationId()}","record_id":"$recordId","revision":$revision,"text_sha256":"$sha",""" +
                """"stored_at":"${now().toString().substringBefore('.').removeSuffix("Z")}Z","duplicate":${existing != null},"superseded_revisions":[],"deliveries":[]}""",
        )
    }

    private suspend fun status(request: HttpRequest): HttpReply {
        val recordId = STATUS_PATH.matchEntire(request.path)!!.groupValues[1]
        calls += "GET status $recordId"
        when (val fault = nextFault()) {
            is Fault.Transport -> throw fault.failure
            Fault.LoseRequest, Fault.StoreThenLoseResponse -> throw TransportFailure.Interrupted()
            is Fault.Reject -> return error(fault.status, fault.code, fault.retryable)
            null -> Unit
        }
        val revisions = stored.filterKeys { it.recordId == recordId }
        if (revisions.isEmpty()) return error(404, "REQUEST_INVALID", false)
        val list = revisions.entries.joinToString(",") { (key, sha) -> """{"revision":${key.revision},"state":"PUBLISHED","text_sha256":"$sha","stored_at":"2026-11-18T08:00:00Z","intent":"context"}""" }
        return ScriptedExchange.json(
            200,
            """{"schema_version":"1.1","record_id":"$recordId","origin_device_id":"$originDeviceId","deleted":$deleted,"deleted_at":${if (deleted) "\"2026-11-18T08:30:00Z\"" else "null"},""" +
                """"latest_revision":${revisions.keys.maxOf { it.revision }},"revisions":[$list],"destinations":[]}""",
        )
    }

    private fun error(status: Int, code: String, retryable: Boolean) = ScriptedExchange.json(
        status,
        """{"schema_version":"1.1","code":"$code","message":"x","retryable":$retryable,"required_action":null,"operation_id":null}""",
    )

    private fun operationId() = "00000000-0000-4000-8000-%012x".format(operations)

    private companion object {
        val PUT_PATH = Regex("^/v1/records/([0-9a-f-]{36})/revisions/([0-9]+)$")
        val STATUS_PATH = Regex("^/v1/records/([0-9a-f-]{36})/status$")
    }
}
