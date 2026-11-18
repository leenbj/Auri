package app.auri.capture.library

import app.auri.capture.adapter.SealedAudio
import app.auri.capture.contract.CaptureStatus
import app.auri.capture.contract.EndReason
import app.auri.capture.contract.ErrorCode
import app.auri.capture.contract.ExecutionLocation
import app.auri.capture.contract.ObservedInputRouteType
import app.auri.capture.contract.RevisionSource
import app.auri.capture.contract.ReviewState
import app.auri.capture.contract.TimeRange
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.flow.Flow

// Record 库：一次已确认路由的录音 = 一个 Record（与它的音频文件共用 record_id），
// 它的文字是一条只增不改的 Revision 链（GLOSSARY：Record / Revision）。
// 字段名、枚举值照 contracts/schemas/revision.schema.json 的 capture / content 两段，
// 这样 Ticket 21 做同步时能直接组装 submission，不需要再改本地库的结构。

/** 一个 Record 的转录进度。[DONE] 当且仅当它至少有一条 Revision。 */
enum class TranscriptionStatus {
    /** 音频已封存，还没转（或正在转）。重启后仍是它的 Record 会被重新转录。 */
    PENDING,

    DONE,

    /** 转录引擎判定没有语音：Record 保留，没有 Revision，也不编造任何文字。 */
    NO_SPEECH,

    /** 转录模型没装好（缺失或校验没过）；装好之后会被重新转录。 */
    WAITING_MODEL,

    /** 转录失败（原因见 [RecordEntry.failureCode]）；音频保留。 */
    FAILED,
}

/** 一条 Revision：不可变，发布之后永远不会被改写；修订是追加新的 Revision。 */
data class Revision(
    val recordId: String,
    /** 从 1 递增（revision.schema.json#/$defs/submission.revision）。 */
    val number: Int,
    val text: String,
    /** [text] 按 UTF-8 编码后的原始字节的 SHA-256，不做任何规范化。 */
    val textSha256: String,
    val source: RevisionSource,
    val language: String,
    val reviewState: ReviewState,
    val executionLocation: ExecutionLocation,
    /** 产出这段文字的引擎与模型：Revision 必须写明用了哪个模型。 */
    val engine: String?,
    val modelId: String?,
    val modelVersion: String?,
    val confidence: Double?,
    val createdAt: Instant,
    /** 本地发布幂等键，不属于网络合同。 */
    val publicationKey: String? = null,
) {
    init {
        require(number >= 1) { "revision 至少为 1" }
        require(textSha256 == sha256Hex(text)) { "text_sha256 与正文不符" }
    }
}

/** 待发布的 Revision：没有 [Revision.number]，由库在发布的那个事务里分配。 */
data class RevisionDraft(
    val recordId: String,
    val text: String,
    val source: RevisionSource,
    val language: String,
    val reviewState: ReviewState,
    val executionLocation: ExecutionLocation,
    val engine: String?,
    val modelId: String?,
    val modelVersion: String?,
    val confidence: Double?,
    val createdAt: Instant,
    val publicationKey: String? = null,
) {
    val textSha256: String get() = sha256Hex(text)

    /** 库实现在事务里分配好 [number] 之后调用。 */
    fun toRevision(number: Int) = Revision(
        recordId, number, text, textSha256, source, language, reviewState, executionLocation, engine, modelId, modelVersion, confidence, createdAt, publicationKey,
    )
}

/**
 * 一个 Record 的全部内容。录音的事实（时间、时长、结束原因、路由证据）在 [addRecord][RecordLibrary.addRecord] 之后不再改变；
 * 只有 [transcription]/[failureCode] 与 [revisions] 会随转录推进。
 */
data class RecordEntry(
    /** 与音频文件同一个标识。 */
    val recordId: String,
    val captureSessionId: String,
    val startedAt: Instant,
    val status: CaptureStatus,
    val endReason: EndReason,
    val captureDurationMs: Long,
    val speechDurationMs: Long,
    val speechSegments: List<TimeRange>,
    /** 封存后的音频；确认了路由却没收到任何采样时为 null。 */
    val audio: SealedAudio?,
    val routeEvidenceId: String,
    val inputRouteType: ObservedInputRouteType,
    val headsetDisplayName: String?,
    val transcription: TranscriptionStatus = TranscriptionStatus.PENDING,
    val failureCode: ErrorCode? = null,
    /** 按 number 递增；只读。 */
    val revisions: List<Revision> = emptyList(),
) {
    /** 最新一条 Revision 的正文；没有 Revision 时为 null。 */
    val latestText: String? get() = revisions.lastOrNull()?.text
}

/** 库里没有这个 Record。 */
class NoSuchRecordException(val recordId: String) : IllegalArgumentException("没有这个 Record：$recordId")

/**
 * 本地 Record 库。实现：测试里的内存库（testFixtures）与 `:platform` 的 SQLite 库；两者通过同一套契约测试（RecordLibraryContract）。
 *
 * 发布一条 Revision 是**一个本地事务**：分配 number、写入 Revision、把 Record 标为 DONE、执行实现各自的 PublicationStep，
 * 要么全部成功要么全部不留痕迹。Ticket 21 的“待同步任务”就在这个事务里的 PublicationStep 中入队，保证“已发布”与“待同步”不会分家。
 */
interface RecordLibrary {
    /** 登记一个新 Record。同一个 [RecordEntry.recordId] 已存在时什么都不做，返回 false（幂等）。 */
    suspend fun addRecord(entry: RecordEntry): Boolean

    /**
     * 发布 Revision（一个事务）。返回库里实际写下的那条。Record 不存在抛 [NoSuchRecordException]；
     * 发布步骤失败则整个事务回滚，Record 保持原样，number 不会被占用。
     */
    suspend fun publishRevision(draft: RevisionDraft): Revision

    /** 更新没有产出 Revision 的转录状态。对已经 [TranscriptionStatus.DONE] 的 Record 不允许（IllegalStateException）。 */
    suspend fun markTranscription(recordId: String, status: TranscriptionStatus, failureCode: ErrorCode? = null)

    suspend fun get(recordId: String): RecordEntry?

    /** 全部 Record，最新的在前；每次变化发出新的列表。 */
    fun observe(): Flow<List<RecordEntry>>

    /** 还要转录的 Record（PENDING、WAITING_MODEL，且有音频）：进程重启后据此恢复。 */
    suspend fun incomplete(): List<RecordEntry>
}

internal fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
