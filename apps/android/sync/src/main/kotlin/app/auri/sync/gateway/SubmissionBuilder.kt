package app.auri.sync.gateway

import app.auri.capture.contract.CaptureStatus
import app.auri.capture.contract.ContractJson
import app.auri.capture.contract.RevisionSource
import app.auri.capture.library.RecordEntry
import app.auri.capture.library.Revision
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * 把本地的一条 Record 与它的一条 Revision 组成网关要的请求体（revision.schema.json#/$defs/submission）。
 *
 * 本地数据不符合合同（路由不是蓝牙、没有语音片段、状态与结束原因对不上、转录没写模型……）时返回 null，**不去“修”它**：
 * 发出去只会被网关永久拒绝，而悄悄改数据（比如补一个蓝牙路由类型）等于对网关撒谎。引擎把 null 记成“本机数据读不到”，停下来等用户。
 * 规则与 tests/contract/semantic_rules.py、services/gateway/auri_gateway/contracts.py 的 SR-01…06 同一套，真正的网关校验在 realgateway 测试里跑。
 *
 * 几处固定值，不是遗漏：
 * - `capture.input_policy = bluetooth_only`、`input_verification = verified`：只有路由已确认的录音才会成为 Record（ADR-0014、FR-REC-001）。
 * - `content.segments = []`：本地 Revision 没有保留转录引擎的逐段时间戳（合同允许为空）。
 * - `publication`：由调用方给（Ticket 22）。不给就是 [Publication.StoreOnly]（`{intent: context, destination_ids: []}`：只入库、不创建 Delivery）——
 *   “忘了传”只会少发，绝不会多发。意图与目标由发送账（DispatchStore）在**发送那一刻**定，不是在这里猜。
 * - `headset_firmware = null`：系统不暴露，合同要求不得猜。
 */
class SubmissionBuilder(private val appVersion: String) {
    init {
        require(appVersion.length in 1..64) { "app_version 长度必须在 1..64" }
    }

    fun build(entry: RecordEntry, revision: Revision, publication: Publication = Publication.StoreOnly): RevisionSubmission? {
        if (!consistent(entry, revision) || !timelineIsValid(entry) || !contentIsValid(revision)) return null
        val body = buildJsonObject {
            put("schema_version", Wire.SCHEMA_VERSION)
            put("record_id", entry.recordId)
            put("revision", revision.number)
            putJsonObject("capture") {
                put("platform", "android")
                put("app_version", appVersion)
                put("started_at", entry.startedAt.toString())
                put("capture_duration_ms", entry.captureDurationMs)
                put("speech_duration_ms", entry.speechDurationMs)
                putJsonArray("speech_segments") {
                    entry.speechSegments.forEach { segment ->
                        add(buildJsonObject {
                            put("start_ms", segment.startMs)
                            put("end_ms", segment.endMs)
                        })
                    }
                }
                put("end_reason", wire(entry.endReason))
                put("status", wire(entry.status))
                put("input_policy", "bluetooth_only")
                put("input_verification", "verified")
                // 耳机名只是给人看的；太长就不报，不截断（截断可能切开代理对）
                put("headset_display_name", entry.headsetDisplayName?.takeIf { it.characterCount() <= MAX_HEADSET_NAME })
                put("headset_firmware", JsonNull)
                put("input_route_type", wire(entry.inputRouteType))
                put("route_evidence_id", entry.routeEvidenceId)
            }
            putJsonObject("content") {
                put("text", revision.text)
                put("text_sha256", revision.textSha256)
                put("source", wire(revision.source))
                put("language", revision.language)
                put("review_state", wire(revision.reviewState))
                put("execution_location", wire(revision.executionLocation))
                put("engine", revision.engine)
                put("model_id", revision.modelId)
                put("model_version", revision.modelVersion)
                put("confidence", revision.confidence)
                putJsonArray("segments") {}
            }
            putJsonObject("publication") {
                put("intent", publication.intent.wire)
                putJsonArray("destination_ids") { publication.destinationIds.forEach { add(JsonPrimitive(it)) } }
            }
        }
        return RevisionSubmission(entry.recordId, revision.number, revision.textSha256, body.toString().toByteArray(Charsets.UTF_8))
    }

    /** 标识格式、来路（必须是蓝牙）、状态与结束原因的一致性。 */
    private fun consistent(entry: RecordEntry, revision: Revision): Boolean =
        entry.recordId == revision.recordId &&
            Wire.UUID.matches(entry.recordId) &&
            Wire.UUID.matches(entry.routeEvidenceId) &&
            entry.inputRouteType.isBluetooth &&
            // 合同：由用户命令或时长上限结束的是 complete，被截断的是 partial
            entry.status == (if (entry.endReason.truncated) CaptureStatus.PARTIAL else CaptureStatus.COMPLETE)

    /** SR-02、SR-03、SR-04，以及“有 Revision 就至少有一个语音片段”。 */
    private fun timelineIsValid(entry: RecordEntry): Boolean {
        val segments = entry.speechSegments
        return segments.isNotEmpty() &&
            entry.captureDurationMs >= 0 &&
            entry.speechDurationMs >= 0 &&
            segments.all { it.startMs < it.endMs } &&
            segments.zipWithNext().all { (previous, next) -> previous.endMs <= next.startMs } &&
            segments.all { it.endMs <= entry.captureDurationMs } &&
            segments.sumOf { it.endMs - it.startMs } == entry.speechDurationMs
    }

    /** 正文（非空、不超过 1 MiB 的 UTF-8 字节，SR-06）、语言、置信度、引擎与模型字段。SR-01、SR-05 由 Revision 的类型保证。 */
    private fun contentIsValid(revision: Revision): Boolean {
        val confidence = revision.confidence
        return revision.text.isNotEmpty() &&
            revision.text.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES &&
            LANGUAGE.matches(revision.language) &&
            (confidence == null || (confidence.isFinite() && confidence in 0.0..1.0)) &&
            (revision.source != RevisionSource.ASR || (revision.engine.isNotNullOrEmpty() && revision.modelId.isNotNullOrEmpty() && revision.modelVersion.isNotNullOrEmpty())) &&
            (revision.engine?.characterCount() ?: 0) <= MAX_ENGINE &&
            (revision.modelId?.characterCount() ?: 0) <= MAX_MODEL_FIELD &&
            (revision.modelVersion?.characterCount() ?: 0) <= MAX_MODEL_FIELD
    }

    private fun String?.isNotNullOrEmpty() = !isNullOrEmpty()

    /** JSON Schema 的 maxLength 按字符（Unicode 码点）计，不是 UTF-16 单元。 */
    private fun String.characterCount() = codePointCount(0, length)

    private inline fun <reified E : Enum<E>> wire(value: E): String = ContractJson.json.encodeToString(serializer<E>(), value).trim('"')

    private companion object {
        const val MAX_TEXT_BYTES = 1_048_576
        const val MAX_HEADSET_NAME = 200
        const val MAX_ENGINE = 100
        const val MAX_MODEL_FIELD = 200
        val LANGUAGE = Regex("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$")
    }
}
