@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.sync.contract

import app.auri.sync.destinations.Choice
import app.auri.sync.gateway.CreateDestinationRequest
import app.auri.sync.gateway.Intent
import app.auri.sync.gateway.SendMode
import app.auri.sync.testing.DispatchRig
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/**
 * 把“手机实际发给网关的请求”写成合同形状的 JSON 文件，交给 Python 的 destination_contract_check.py 用真实的 contracts/schemas/ 校验
 * （execution/acceptance/android-destinations/test_destination_contract_output.py 会调用本测试再校验）。
 *
 * 写出的是真实服务与真实同步引擎发出的字节：创建 Destination 的请求体、PUT 入库的请求体（含 publication）、追加 Delivery 的请求体，
 * 加上网关侧的观察（收到几次 PUT、几次追加、有几个 Delivery）——“预览确认的文字确认前已经入库，但网关上没有它的 Delivery”要靠这个观察才说得清（评审 B2）。
 * 本测试自己不下断言（断言在 Python 检查里与 :app、:sync 的其它测试里）：它只负责把东西如实写出来。
 * 输出目录由 Gradle 的系统属性 auri.contract.out 给定（见 sync/build.gradle.kts）。
 */
class DestinationContractOutputTest {
    private val outRoot = File(checkNotNull(System.getProperty("auri.contract.out")) { "auri.contract.out 没有设置：请经 Gradle 运行" })

    private val json = Json { prettyPrint = true }

    private fun run(body: suspend TestScope.(DispatchRig) -> Unit) = runTest { body(DispatchRig(this)) }

    private fun scenarioDir(name: String): File = File(outRoot, name).also {
        it.deleteRecursively()
        it.mkdirs()
    }

    private fun File.write(name: String, value: JsonElement) = resolve(name).writeText(json.encodeToString(JsonElement.serializer(), value) + "\n", Charsets.UTF_8)

    /** 网关侧的观察：收到几次入库、几次追加、有几个 Delivery、场景涉及哪些目的地。 */
    private fun observed(rig: DispatchRig, destinationIds: List<String>): JsonObject = buildJsonObject {
        put("puts", rig.server.putCount)
        put("additions", rig.server.additionCount)
        put("deliveries", rig.server.deliveries.size)
        put("destination_ids", JsonArray(destinationIds.map(::JsonPrimitive)))
    }

    @Test
    fun `创建自动发送的 Destination 发出的请求体`() = run { rig ->
        rig.service.create(CreateDestinationRequest("写作助手", SendMode.AUTO_SEND, setOf(Intent.CONTEXT)))

        scenarioDir("create-auto-send").write("create-request.json", rig.server.createBodies.single())
    }

    @Test
    fun `创建预览确认的 Destination 发出的请求体`() = run { rig ->
        rig.service.create(CreateDestinationRequest("Claude Code", SendMode.PREVIEW_CONFIRM, setOf(Intent.CONTEXT, Intent.ACTION_REQUEST)))

        scenarioDir("create-preview-confirm").write("create-request.json", rig.server.createBodies.single())
    }

    @Test
    fun `自动发送：一条新转录的文字发给自动发送的目的地，请求体是未审核的初稿`() = run { rig ->
        val auto = rig.createDestination("写作助手", SendMode.AUTO_SEND)
        rig.transcribed("明天检查小说记忆模块 😀")

        rig.engine.runOnce()

        val dir = scenarioDir("auto-send-unreviewed")
        dir.write("submission.json", rig.server.putBodies.single())
        dir.write("scenario.json", observed(rig, listOf(auto.destinationId)))
    }

    @Test
    fun `预览确认的文字在用户确认前：文字已经入库，但入库请求没有点名预览目的地，网关上没有任何 Delivery`() = run { rig ->
        rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        rig.transcribed("还没决定要不要发")

        repeat(3) { rig.engine.runOnce() }

        // 如果没有 PUT 就不写 submission.json，让 Python 检查报“这个场景必须有入库请求”，而不是让本测试自己崩
        val dir = scenarioDir("preview-held-before-confirm")
        rig.server.putBodies.firstOrNull()?.let { dir.write("submission.json", it) }
        dir.write("scenario.json", observed(rig, emptyList()))
    }

    @Test
    fun `预览确认：用户确认之后才追加它的 Delivery，Revision 不重发，恰好一个 Delivery`() = run { rig ->
        val preview = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = rig.transcribed("确认后才发")
        repeat(2) { rig.engine.runOnce() }

        rig.dispatch.decide(key, preview.destinationId, Choice.CONFIRM, rig.clock.now())
        rig.dispatch.decide(key, preview.destinationId, Choice.CONFIRM, rig.clock.now()) // 重复确认
        repeat(2) { rig.engine.runOnce() }

        val dir = scenarioDir("preview-after-confirm")
        dir.write("submission.json", rig.server.putBodies.single())
        // 追加请求的数量由 scenario.json 的 additions 记下，Python 检查要求恰好一次；这里只写第一个
        rig.server.additionBodies.firstOrNull()?.let { dir.write("additions-request.json", it) }
        dir.write("scenario.json", observed(rig, listOf(preview.destinationId)))
    }

    @Test
    fun `标成请求执行的内容：publication 里的意图是 action_request，其余不变`() = run { rig ->
        val preview = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM, setOf(Intent.CONTEXT, Intent.ACTION_REQUEST))
        val key = rig.transcribed("把周报发给我")
        rig.dispatch.setIntent(key, Intent.ACTION_REQUEST, rig.clock.now())
        rig.dispatch.decide(key, preview.destinationId, Choice.CONFIRM, rig.clock.now())

        rig.engine.runOnce()

        val dir = scenarioDir("action-request-tag")
        dir.write("submission.json", rig.server.putBodies.single())
        dir.write("scenario.json", observed(rig, listOf(preview.destinationId)))
    }
}
