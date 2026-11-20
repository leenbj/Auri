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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 网关服务端逻辑的小模型：健康检查、PUT 入库、查询 Record 状态（Ticket 21），以及 Destination 的创建/列表/撤销/签发凭证与投递（Ticket 22），
 * 响应体的形状照合同样本。
 * 语义取自真网关：同一（Record, Revision）再提交相同正文 = 重复（duplicate=true），不同正文 = 409；没见过的 Record 查询 = 404 REQUEST_INVALID；
 * **投递（Delivery）在这一版第一次入库时按 publication.destination_ids 创建**，重复提交什么都不补建；已入库的 Revision 之后要给更多
 * Destination 建投递，走追加端点（POST …/revisions/{n}/deliveries，评审 B2）：已有的原样回答、缺的才验证并创建、一个不合格就整个 422；
 * 第一次入库时 publication 里有不存在、已撤销或不接收这种意图的 Destination，整个 PUT 以 422 REQUEST_INVALID 拒绝（重复提交在这之前就返回了，和真网关一样）。
 * PUT 与追加的响应里的 deliveries 都是这一版上的投递（编号、Destination、版本号、状态）。
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

    /** 网关上的一个 Destination。[credential] 只在签发的响应里给出过，留着是为了让测试能核对“旧凭证作废”。 */
    class FakeDestination(
        val id: String,
        val displayName: String,
        val sendMode: String,
        val intents: List<String>,
        val createdAt: Instant,
        var credentialIssued: Boolean = false,
        var revokedAt: Instant? = null,
        var credential: String? = null,
        /** 现在有没有一条有效凭证：签发置真，网关管理员撤销凭证（[revokeCredential]）置假。 */
        var credentialActive: Boolean = false,
    ) {
        /** 暂停（评审 B3）：签发过、凭证被撤销、还没有新的，Destination 本身没被撤销。与真网关同一个定义。 */
        val suspended: Boolean get() = revokedAt == null && credentialIssued && !credentialActive
    }

    /** 网关上的一次投递；[state] 是合同的 delivery_state。 */
    class FakeDelivery(val destinationId: String, val recordId: String, val revision: Int, var state: String, val storedAt: Instant, var acknowledgedAt: Instant? = null)

    val destinations = LinkedHashMap<String, FakeDestination>()
    val deliveries = CopyOnWriteArrayList<FakeDelivery>()

    /** 收到并入库的 PUT 请求体（解析后），按到达顺序：测试据此核对手机到底发了什么。 */
    val putBodies = CopyOnWriteArrayList<JsonObject>()
    val createBodies = CopyOnWriteArrayList<JsonObject>()
    val credentialRequests = CopyOnWriteArrayList<String>()

    /** 收到的追加请求体（解析后），按到达顺序。 */
    val additionBodies = CopyOnWriteArrayList<JsonObject>()

    /** 追加时网关“答复成功却没有为它建”的 Destination（真网关不会这样；用来验证手机不假设追加一定建成）。 */
    val dropFromAdditions = HashSet<String>()

    /** 到达服务器逻辑的 PUT / 追加 / 状态查询，按顺序："PUT <record>#<n>"、"POST deliveries <record>#<n>"、"GET status <record>"（健康检查不记）。 */
    val calls = CopyOnWriteArrayList<String>()

    /** Destination 管理请求，按顺序："GET destinations"、"POST destinations"、"DELETE destination"、"POST credential"。 */
    val adminCalls = CopyOnWriteArrayList<String>()

    val putCount: Int get() = calls.count { it.startsWith("PUT ") }
    val statusCount: Int get() = calls.count { it.startsWith("GET status ") }
    val additionCount: Int get() = calls.count { it.startsWith("POST deliveries ") }

    /** 依次用在后面的 PUT / 状态查询上（一个请求用一个）。 */
    val faults = ConcurrentLinkedQueue<Fault>()

    /** 下一个 PUT / 状态查询在这里停住，直到测试给出结果（null = 正常处理）。用来让“发送中”这个状态可以被观察。 */
    @Volatile
    var holdNext: CompletableDeferred<Fault?>? = null

    private var operations = 0
    private var destinationCounter = 0
    private var deliveryCounter = 0
    private var credentialCounter = 0
    private val deliveryIds = HashMap<FakeDelivery, String>()

    /**
     * 模拟网关管理员在网关机器上撤销这个 Destination 的凭证（命令行 revoke-credential，评审 B3）：
     * 没有新凭证时 Destination 暂停——排着的投递取消，之后的文字不再为它建投递（不报错、悄悄跳过），签发新凭证才恢复。
     */
    fun revokeCredential(destinationId: String) {
        val destination = destinations.getValue(destinationId)
        destination.credentialActive = false
        if (destination.suspended) {
            deliveries.filter { it.destinationId == destinationId && (it.state == "PENDING" || it.state == "LEASED") }.forEach { it.state = "CANCELLED" }
        }
    }

    private fun receiving(ids: List<String>): List<String> = ids.filter { destinations[it]?.suspended != true }

    /** 模拟 Agent 经 MCP 领取：这条 Record 在这个 Destination 上的 PENDING 投递变成 LEASED。 */
    fun agentClaims(destinationId: String, recordId: String) {
        deliveries.filter { it.destinationId == destinationId && it.recordId == recordId && it.state == "PENDING" }.forEach { it.state = "LEASED" }
    }

    /** 模拟 Agent 确认：LEASED 变成 ACKNOWLEDGED。 */
    fun agentAcknowledges(destinationId: String, recordId: String) {
        deliveries.filter { it.destinationId == destinationId && it.recordId == recordId && it.state == "LEASED" }.forEach {
            it.state = "ACKNOWLEDGED"
            it.acknowledgedAt = now()
        }
    }

    suspend fun handle(request: HttpRequest): HttpReply = when {
        request.method == "GET" && request.path == "/health" -> ScriptedExchange.json(200, """{"status":"ok"}""")
        request.method == "PUT" && PUT_PATH.matches(request.path) -> put(request)
        request.method == "POST" && ADDITION_PATH.matches(request.path) -> addDeliveries(request)
        request.method == "GET" && STATUS_PATH.matches(request.path) -> status(request)
        request.method == "GET" && request.path == "/v1/destinations" -> listDestinations()
        request.method == "POST" && request.path == "/v1/destinations" -> createDestination(request)
        request.method == "DELETE" && DESTINATION_PATH.matches(request.path) -> revokeDestination(DESTINATION_PATH.matchEntire(request.path)!!.groupValues[1])
        request.method == "POST" && CREDENTIAL_PATH.matches(request.path) -> issueCredential(request, CREDENTIAL_PATH.matchEntire(request.path)!!.groupValues[1])
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
        val body = Json.parseToJsonElement(request.body!!.toString(Charsets.UTF_8)).jsonObject
        val sha = body.getValue("content").jsonObject.getValue("text_sha256").jsonPrimitive.content
        val publication = body["publication"]?.jsonObject
        val intent = publication?.get("intent")?.jsonPrimitive?.content ?: "context"
        val targets = publication?.get("destination_ids")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        when (val fault = nextFault()) {
            is Fault.Transport -> throw fault.failure
            Fault.LoseRequest -> throw TransportFailure.Interrupted()
            is Fault.Reject -> return error(fault.status, fault.code, fault.retryable)
            Fault.StoreThenLoseResponse -> {
                if (key !in stored && !publicationAcceptable(targets, intent)) return error(422, "REQUEST_INVALID", false)
                firstStore(key, sha, targets, body)
                throw TransportFailure.Interrupted()
            }
            null -> Unit
        }
        val existing = stored[key]
        if (existing != null && existing != sha) return error(409, "RECORD_VERSION_CONFLICT", false)
        if (existing == null && !publicationAcceptable(targets, intent)) return error(422, "REQUEST_INVALID", false)
        firstStore(key, sha, targets, body)
        operations += 1
        return ScriptedExchange.json(
            200,
            """{"schema_version":"1.1","operation_id":"${operationId()}","record_id":"$recordId","revision":$revision,"text_sha256":"$sha",""" +
                """"stored_at":"${iso(now())}","duplicate":${existing != null},"superseded_revisions":[],"deliveries":${deliveriesOn(key)}}""",
        )
    }

    private fun deliveriesOn(key: JobKey, only: List<String>? = null): JsonArray = buildJsonArray {
        deliveries.filter { it.recordId == key.recordId && it.revision == key.revision && (only == null || it.destinationId in only) }
            .sortedBy { d -> only?.indexOf(d.destinationId) ?: 0 }
            .forEach { delivery ->
                add(
                    buildJsonObject {
                        put("delivery_id", deliveryId(delivery))
                        put("destination_id", delivery.destinationId)
                        put("revision", delivery.revision)
                        put("state", delivery.state)
                    },
                )
            }
    }

    /** 追加：已有的原样回答，缺的才验证并创建；一个不合格就整个 422、什么都不建。 */
    private suspend fun addDeliveries(request: HttpRequest): HttpReply {
        val (recordId, revision) = ADDITION_PATH.matchEntire(request.path)!!.destructured
        val key = JobKey(recordId, revision.toInt())
        calls += "POST deliveries $recordId#$revision"
        val body = Json.parseToJsonElement(request.body!!.toString(Charsets.UTF_8)).jsonObject
        additionBodies += body
        val intent = body.getValue("intent").jsonPrimitive.content
        val ids = body.getValue("destination_ids").jsonArray.map { it.jsonPrimitive.content }
        when (val fault = nextFault()) {
            is Fault.Transport -> throw fault.failure
            Fault.LoseRequest -> throw TransportFailure.Interrupted()
            is Fault.Reject -> return error(fault.status, fault.code, fault.retryable)
            Fault.StoreThenLoseResponse -> {
                if (key in stored && missing(key, ids).let { publicationAcceptable(it, intent) }) create(key, missing(key, ids))
                throw TransportFailure.Interrupted()
            }
            null -> Unit
        }
        if (key !in stored) return error(404, "REQUEST_INVALID", false)
        val absent = missing(key, ids)
        if (!publicationAcceptable(absent, intent)) return error(422, "REQUEST_INVALID", false)
        create(key, absent.filter { it !in dropFromAdditions })
        operations += 1
        return ScriptedExchange.json(
            200,
            """{"schema_version":"1.1","record_id":"$recordId","revision":$revision,"deliveries":${deliveriesOn(key, ids)}}""",
        )
    }

    private fun missing(key: JobKey, ids: List<String>): List<String> =
        ids.filter { id -> deliveries.none { it.destinationId == id && it.recordId == key.recordId && it.revision == key.revision } }

    private fun create(key: JobKey, ids: List<String>) {
        receiving(ids).forEach { deliveries += FakeDelivery(it, key.recordId, key.revision, "PENDING", now()) }
    }

    /** 第一次入库：记下这一版，并按 publication 为每个目标创建投递。已经入库的（重复提交）什么都不动。 */
    private fun firstStore(key: JobKey, sha: String, targets: List<String>, body: JsonObject) {
        if (stored.putIfAbsent(key, sha) != null) return
        putBodies += body
        receiving(targets).forEach { deliveries += FakeDelivery(it, key.recordId, key.revision, "PENDING", now()) }
    }

    private fun publicationAcceptable(targets: List<String>, intent: String): Boolean = targets.all { id ->
        val destination = destinations[id]
        destination != null && destination.revokedAt == null && intent in destination.intents
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
        val delivered = deliveries.filter { it.recordId == recordId }.map { delivery ->
            buildJsonObject {
                put("destination_id", delivery.destinationId)
                put("destination_display_name", destinations.getValue(delivery.destinationId).displayName)
                put("delivery_id", deliveryId(delivery))
                put("revision", delivery.revision)
                put("delivery_state", delivery.state)
                put("stored_at", iso(delivery.storedAt))
                put("lease_expires_at", if (delivery.state == "LEASED") iso(delivery.storedAt.plusSeconds(120)) else null)
                put("acknowledged_at", delivery.acknowledgedAt?.let(::iso))
                put("rejected_at", null as String?)
                put("cancelled_at", null as String?)
            }
        }
        return ScriptedExchange.json(
            200,
            """{"schema_version":"1.1","record_id":"$recordId","origin_device_id":"$originDeviceId","deleted":$deleted,"deleted_at":${if (deleted) "\"2026-11-18T08:30:00Z\"" else "null"},""" +
                """"latest_revision":${revisions.keys.maxOf { it.revision }},"revisions":[$list],"destinations":${JsonArray(delivered)}}""",
        )
    }

    // ---- Destination 管理（Ticket 22） ----

    private fun listDestinations(): HttpReply {
        adminCalls += "GET destinations"
        val body = buildJsonObject {
            put("schema_version", "1.1")
            put("destinations", buildJsonArray { destinations.values.forEach { add(resource(it)) } })
        }
        return ScriptedExchange.json(200, body.toString())
    }

    private fun createDestination(request: HttpRequest): HttpReply {
        adminCalls += "POST destinations"
        val body = runCatching { Json.parseToJsonElement(request.body!!.toString(Charsets.UTF_8)).jsonObject }.getOrNull() ?: return error(422, "REQUEST_INVALID", false)
        createBodies += body
        val name = (body["display_name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val mode = (body["send_mode"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val intents = (body["allowed_intents"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        // 与真网关同样严格：send_mode 必填且只能是两个值之一（没有默认），名字 1..100 个字符，意图非空、不重复、取值合法
        if (name == null || name.codePointCount(0, name.length) !in 1..100) return error(422, "REQUEST_INVALID", false)
        if (mode != "auto_send" && mode != "preview_confirm") return error(422, "REQUEST_INVALID", false)
        if (intents.isNullOrEmpty() || intents.toSet().size != intents.size || !intents.all { it == "context" || it == "action_request" }) return error(422, "REQUEST_INVALID", false)
        val destination = FakeDestination("d0000000-0000-4000-8000-%012x".format(++destinationCounter), name, mode, intents, now())
        destinations[destination.id] = destination
        return ScriptedExchange.json(200, resource(destination).toString())
    }

    private fun revokeDestination(id: String): HttpReply {
        adminCalls += "DELETE destination"
        val destination = destinations[id] ?: return error(404, "REQUEST_INVALID", false)
        if (destination.revokedAt == null) {
            destination.revokedAt = now()
            // 撤销会取消还没确认的投递（已确认的保持原样）
            deliveries.filter { it.destinationId == id && (it.state == "PENDING" || it.state == "LEASED") }.forEach { it.state = "CANCELLED" }
        }
        return ScriptedExchange.json(200, resource(destination).toString())
    }

    private fun issueCredential(request: HttpRequest, id: String): HttpReply {
        adminCalls += "POST credential"
        credentialRequests += request.body?.toString(Charsets.UTF_8).orEmpty()
        val destination = destinations[id] ?: return error(404, "REQUEST_INVALID", false)
        if (destination.revokedAt != null) return error(409, "REQUEST_INVALID", false)
        val replaced = destination.credentialIssued
        destination.credential = "auri_dst_fake_%032x".format(++credentialCounter)
        destination.credentialIssued = true
        destination.credentialActive = true
        val body = buildJsonObject {
            put("schema_version", "1.1")
            put("destination_id", id)
            put("credential", destination.credential)
            put("issued_at", iso(now()))
            put("replaced_previous", replaced)
        }
        return ScriptedExchange.json(200, body.toString())
    }

    private fun resource(destination: FakeDestination) = buildJsonObject {
        put("schema_version", "1.1")
        put("destination_id", destination.id)
        put("display_name", destination.displayName)
        put("send_mode", destination.sendMode)
        putJsonArray("allowed_intents") { destination.intents.forEach { add(JsonPrimitive(it)) } }
        put("created_at", iso(destination.createdAt))
        put("created_by_device_id", SyncSamples.DEVICE_ID)
        put("credential_issued", destination.credentialIssued)
        put("credential_active", destination.credentialActive)
        put("revoked_at", destination.revokedAt?.let(::iso))
    }

    private fun deliveryId(delivery: FakeDelivery): String = deliveryIds.getOrPut(delivery) { "e0000000-0000-4000-8000-%012x".format(++deliveryCounter) }

    private fun iso(instant: Instant) = instant.toString().substringBefore('.').removeSuffix("Z") + "Z"

    private fun error(status: Int, code: String, retryable: Boolean) = ScriptedExchange.json(
        status,
        """{"schema_version":"1.1","code":"$code","message":"x","retryable":$retryable,"required_action":null,"operation_id":null}""",
    )

    private fun operationId() = "00000000-0000-4000-8000-%012x".format(operations)

    private companion object {
        val PUT_PATH = Regex("^/v1/records/([0-9a-f-]{36})/revisions/([0-9]+)$")
        val ADDITION_PATH = Regex("^/v1/records/([0-9a-f-]{36})/revisions/([0-9]+)/deliveries$")
        val STATUS_PATH = Regex("^/v1/records/([0-9a-f-]{36})/status$")
        val DESTINATION_PATH = Regex("^/v1/destinations/([0-9a-f-]{36})$")
        val CREDENTIAL_PATH = Regex("^/v1/destinations/([0-9a-f-]{36})/credential$")
    }
}
