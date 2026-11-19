package app.auri.sync.testing

import app.auri.capture.contract.ContractJson
import app.auri.capture.contract.TimeRange
import app.auri.capture.library.RecordEntry
import app.auri.capture.library.Revision
import app.auri.capture.testing.Samples
import java.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * 把合同样本里的 Revision submission 还原成“手机本地数据”（一条 Record 加它的一条 Revision）。
 * 样本说有效的，用它组出来的请求体必须与样本逐字段相同（SubmissionBuilderTest），并且真实网关必须收下（realgateway 测试）。
 */
object FixtureRecords {
    fun localDataFrom(instance: JsonObject): Pair<RecordEntry, Revision> {
        val capture = instance.getValue("capture").jsonObject
        val content = instance.getValue("content").jsonObject
        val recordId = instance.getValue("record_id").jsonPrimitive.content
        val entry = RecordEntry(
            recordId = recordId,
            captureSessionId = Samples.uuid(1),
            startedAt = Instant.parse(capture.getValue("started_at").jsonPrimitive.content),
            status = capture.getValue("status").asEnum(),
            endReason = capture.getValue("end_reason").asEnum(),
            captureDurationMs = capture.getValue("capture_duration_ms").jsonPrimitive.long,
            speechDurationMs = capture.getValue("speech_duration_ms").jsonPrimitive.long,
            speechSegments = capture.getValue("speech_segments").jsonArray.map {
                TimeRange(it.jsonObject.getValue("start_ms").jsonPrimitive.long, it.jsonObject.getValue("end_ms").jsonPrimitive.long)
            },
            audio = Samples.audio(Samples.uuid(1)),
            routeEvidenceId = capture.getValue("route_evidence_id").jsonPrimitive.content,
            inputRouteType = capture.getValue("input_route_type").asEnum(),
            headsetDisplayName = capture.getValue("headset_display_name").nullableString(),
        )
        val revision = Revision(
            recordId = recordId,
            number = instance.getValue("revision").jsonPrimitive.long.toInt(),
            text = content.getValue("text").jsonPrimitive.content,
            textSha256 = SyncSamples.sha256Hex(content.getValue("text").jsonPrimitive.content),
            source = content.getValue("source").asEnum(),
            language = content.getValue("language").jsonPrimitive.content,
            reviewState = content.getValue("review_state").asEnum(),
            executionLocation = content.getValue("execution_location").asEnum(),
            engine = content.getValue("engine").nullableString(),
            modelId = content.getValue("model_id").nullableString(),
            modelVersion = content.getValue("model_version").nullableString(),
            confidence = content.getValue("confidence").let { if (it is JsonNull) null else it.jsonPrimitive.double },
            createdAt = Instant.parse("2026-11-15T02:31:00Z"),
        )
        return entry to revision
    }

    private inline fun <reified E : Enum<E>> JsonElement.asEnum(): E = ContractJson.json.decodeFromString<E>("\"${jsonPrimitive.content}\"")

    private fun JsonElement.nullableString(): String? = if (this is JsonNull) null else jsonPrimitive.content
}
