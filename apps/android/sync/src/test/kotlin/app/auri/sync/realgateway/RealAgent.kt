package app.auri.sync.realgateway

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Agent 调一个 MCP 工具的结果：[isError] = 工具说自己失败了（带统一错误结构）；[payload] 是工具返回的结构化内容。 */
class ToolReply(val isError: Boolean, val payload: JsonObject) {
    /** claim 的结果：领到的每一条投递（合同 delivery.schema.json#/$defs/claimed_delivery）。 */
    val deliveries: List<JsonObject> get() = payload.getValue("deliveries").jsonArray.map { it.jsonObject }
}

/** 凭证在门口（HTTP 层）还被不被接受：401 + GATEWAY_AUTH_REVOKED = 已被轮换或撤销。 */
class DoorResult(val status: Int, val code: String?)

/**
 * 一个 Agent：拿着 Destination 凭证，经 MCP 向真实网关领取、确认投递。
 *
 * 不是在 Kotlin 里另写一套 MCP：它起 execution/acceptance/android-destinations/real_gateway_agent.py，用仓库 .venv 里官方 MCP SDK 的客户端
 * 和 packages/mcp-stdio-bridge 的固定公钥传输层——真实的 Agent 宿主（Hermes、Claude Code）就是这么连网关的。
 * 凭证经环境变量交给它（不上命令行：`ps` 看得到），脚本的输出里没有凭证。
 */
class RealAgent(private val gateway: RealGateway, private val credential: String) {
    private val repoRoot = File(requiredProperty("auri.repo.root"))
    private val python = File(requiredProperty("auri.python"))
    private val script = File(repoRoot, "execution/acceptance/android-destinations/real_gateway_agent.py")

    fun claim(leaseSeconds: Int? = null): ToolReply = tool(*listOfNotNull("claim", leaseSeconds?.toString()).toTypedArray())

    fun ack(deliveryId: String, leaseToken: String, note: String? = null): ToolReply = tool(*listOfNotNull("ack", deliveryId, leaseToken, note).toTypedArray())

    fun probe(): DoorResult {
        val reply = run("probe")
        return DoorResult(reply.getValue("status").jsonPrimitive.int, reply["code"]?.jsonPrimitive?.contentOrNull)
    }

    private fun tool(vararg args: String): ToolReply {
        val reply = run(*args)
        return ToolReply(reply.getValue("is_error").jsonPrimitive.booleanOrNull ?: error("is_error 不是布尔值"), reply.getValue("payload").jsonObject)
    }

    private fun run(vararg args: String): JsonObject {
        check(python.canExecute()) { "找不到仓库的 Python 环境 ${python.path}" }
        check(script.isFile) { "找不到 ${script.path}" }
        val out = File.createTempFile("auri-agent-out", ".txt", gateway.dataDir.parentFile)
        val err = File.createTempFile("auri-agent-err", ".txt", gateway.dataDir.parentFile)
        try {
            val builder = ProcessBuilder(listOf(python.path, script.path) + args)
            builder.environment().apply {
                put("AURI_GATEWAY_URL", "https://127.0.0.1:${gateway.port}/mcp")
                put("AURI_GATEWAY_SPKI_SHA256", gateway.pin.hex)
                put("AURI_DESTINATION_CREDENTIAL", credential)
                put("PYTHONIOENCODING", "utf-8")
            }
            builder.directory(repoRoot)
            builder.redirectOutput(out)
            builder.redirectError(err)
            val running = builder.start()
            if (!running.waitFor(90, TimeUnit.SECONDS)) {
                running.destroyForcibly()
                error("Agent 命令 ${args.first()} 超过 90 秒还没结束")
            }
            check(running.exitValue() == 0) { "Agent 命令 ${args.first()} 失败（退出码 ${running.exitValue()}）：${err.readText().take(300)}" }
            return Json.parseToJsonElement(out.readText().trim()).jsonObject
        } finally {
            out.delete()
            err.delete()
        }
    }

    private companion object {
        fun requiredProperty(name: String): String =
            System.getProperty(name) ?: error("缺少系统属性 $name：真实网关测试要用 ./gradlew :sync:realGatewayTest 跑，不能直接当普通单测跑")
    }
}
