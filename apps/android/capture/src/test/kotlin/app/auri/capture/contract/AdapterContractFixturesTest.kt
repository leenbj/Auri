package app.auri.capture.contract

import app.auri.capture.testing.RepoContracts
import java.io.File
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 适配器八个类型必须“逐字段、逐取值”镜像 contracts/schemas/adapter.schema.json。
 * 证据：合同自带的样本（contracts/fixtures/messages/）——有效样本必须能解码、再编码后与原样本完全一致
 * （字段名、枚举取值、显式 null 都对得上）；无效样本必须被拒绝；语义无效样本通过结构校验，但被语义规则（SR-xx）点名。
 * 样本是从仓库里直接读的，不是拷贝；新增了某个适配器类型的样本而这里没有对应的 Kotlin 类型，会失败。
 */
class AdapterContractFixturesTest {
    private class Fixture(val file: File, val type: String, val instance: JsonElement, val rule: String?)

    private val decoders: Map<String, (String) -> AdapterMessage> = mapOf(
        "normalized_control_event" to { ContractJson.decode<NormalizedControlEvent>(it) },
        "capture_eligibility" to { ContractJson.decode<CaptureEligibility>(it) },
        "prepared_input" to { ContractJson.decode<PreparedInput>(it) },
        "capture_handle" to { ContractJson.decode<CaptureHandle>(it) },
        "route_change_event" to { ContractJson.decode<RouteChangeEvent>(it) },
        "recording_result" to { ContractJson.decode<RecordingResult>(it) },
        "asr_capabilities" to { ContractJson.decode<AsrCapabilities>(it) },
        "asr_task_handle" to { ContractJson.decode<AsrTaskHandle>(it) },
    )

    private fun fixtures(kind: String): List<Fixture> {
        val dir = RepoContracts.contractsDir.resolve("fixtures/messages/$kind")
        return dir.listFiles { f -> f.name.startsWith("adapter.") && f.name.endsWith(".json") }!!.sorted().map { file ->
            val json = Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
            val schema = json.getValue("schema").jsonPrimitive.content
            assertTrue("${file.name}：不是 adapter.schema.json 的类型：$schema", schema.startsWith("adapter.schema.json#/\$defs/"))
            Fixture(file, schema.substringAfterLast('/'), json.getValue("instance"), json["rule"]?.jsonPrimitive?.content)
        }
    }

    private fun decode(fixture: Fixture): AdapterMessage {
        val decoder = decoders[fixture.type] ?: error("${fixture.file.name}：合同里有 ${fixture.type} 的样本，但没有对应的 Kotlin 类型")
        return decoder(fixture.instance.toString())
    }

    @Test
    fun `八个适配器类型各自都有有效样本和无效样本，没有漏掉的类型`() {
        val valid = fixtures("valid").map { it.type }.toSet()
        val invalid = (fixtures("schema-invalid") + fixtures("semantic-invalid")).map { it.type }.toSet()

        assertEquals(decoders.keys, valid)
        assertEquals(decoders.keys, invalid)
    }

    @Test
    fun `有效样本能解码，语义规则不报错，再编码与原样本完全一致`() {
        val all = fixtures("valid")
        assertTrue(all.isNotEmpty())
        all.forEach { fixture ->
            val decoded = decode(fixture)
            assertEquals("${fixture.file.name}：语义规则", emptyList<String>(), ContractRules.violations(decoded))
            assertEquals("${fixture.file.name}：再编码", fixture.instance, Json.parseToJsonElement(ContractJson.encode(decoded)))
        }
    }

    @Test
    fun `无效样本在解码时被拒绝`() {
        val all = fixtures("schema-invalid")
        assertTrue(all.isNotEmpty())
        all.forEach { fixture ->
            try {
                decode(fixture)
            } catch (expected: IllegalArgumentException) {
                return@forEach // kotlinx.serialization 的 SerializationException 与构造时的校验都是 IllegalArgumentException
            }
            fail("${fixture.file.name} 本应被拒绝，却解码成功了")
        }
    }

    @Test
    fun `语义无效样本通过结构校验，被语义规则点名`() {
        val all = fixtures("semantic-invalid")
        assertTrue(all.isNotEmpty())
        all.forEach { fixture ->
            val rule = fixture.rule ?: error("${fixture.file.name} 没有声明 rule")
            if (rule in UNREPRESENTABLE_RULES) {
                // 这些规则违反的是“V1 手机端根本写不出这个取值”（云端执行位置，SR-05）：类型里没有它，解码即被拒绝
                try {
                    decode(fixture)
                    fail("${fixture.file.name}：类型系统应当写不出这个取值")
                } catch (expected: IllegalArgumentException) {
                    return@forEach
                }
            }
            assertEquals("${fixture.file.name}", listOf(rule), ContractRules.violations(decode(fixture)))
        }
    }

    private companion object {
        val UNREPRESENTABLE_RULES = setOf("SR-05")
    }
}
