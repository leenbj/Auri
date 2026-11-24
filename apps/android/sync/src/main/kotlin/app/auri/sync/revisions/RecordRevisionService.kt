package app.auri.sync.revisions

import app.auri.capture.contract.ExecutionLocation
import app.auri.capture.contract.RevisionSource
import app.auri.capture.contract.ReviewState
import app.auri.capture.coordinator.Clock
import app.auri.capture.library.NoSuchRecordException
import app.auri.capture.library.RecordDeletedException
import app.auri.capture.library.RecordLibrary
import app.auri.capture.library.Revision
import app.auri.capture.library.RevisionDraft
import app.auri.sync.SyncLog
import app.auri.sync.destinations.DeliveryView
import app.auri.sync.destinations.DispatchStore
import app.auri.sync.destinations.GatewayDelivery
import app.auri.sync.destinations.ResendResult
import app.auri.sync.destinations.TargetDecision
import app.auri.sync.destinations.UnknownReason
import app.auri.sync.discovery.GatewayLocator
import app.auri.sync.discovery.Located
import app.auri.sync.engine.JobKey
import app.auri.sync.gateway.GatewayClient
import app.auri.sync.gateway.GatewayResult
import app.auri.sync.gateway.Intent
import app.auri.sync.http.HttpExchangeFactory
import app.auri.sync.pairing.PairingState
import app.auri.sync.pairing.PairingStore
import app.auri.sync.policy.DataFlowPolicy
import app.auri.sync.policy.PolicyGeneration
import app.auri.sync.policy.current

enum class EditError {
    EMPTY_TEXT,
    TEXT_TOO_LONG,
}

sealed interface EditOutcome {
    data class Saved(val revision: Revision) : EditOutcome

    data class Invalid(val error: EditError) : EditOutcome

    data object RecordNotFound : EditOutcome

    data object RecordDeleted : EditOutcome
}

enum class ResendState {
    NOT_REQUESTED,
    PENDING_RESEND,
    SENT_TO_GATEWAY,
    NOT_ELIGIBLE,
}

/** 收到过旧版本的某个 Destination，及其对最新 Revision 的重发状态。 */
data class OlderRecipient(
    val destinationId: String,
    val destinationName: String,
    val receivedRevision: Int,
    val olderDeliveryView: DeliveryView,
    val resendState: ResendState,
)

/**
 * 修订、重发与删除的领域服务（Ticket 23，FR-TXT-002 / FR-TXT-005 / ADR-0009 / SEC-003）：
 * - 每次保存编辑产生一条新的不可变 Revision（`source = user_edit`、`review_state = reviewed`），旧版本保留可回看；
 * - 新 Revision **默认不自动重发**：[olderVersionRecipients] 列出收到过旧版本的 Destination，由用户逐个调用 [confirmResend]；
 * - 支持单独删除本地音频而保留文字（[deleteAudioOnly]），或整条删除 Record 并留下墓碑（[deleteRecord]）；
 * - 日志只写事件名与枚举，绝不记录正文、音频路径、目的地名或凭证。
 */
class RecordRevisionService(
    private val library: RecordLibrary,
    private val dispatch: DispatchStore,
    private val pairing: PairingStore,
    private val http: HttpExchangeFactory,
    private val locator: GatewayLocator,
    private val policy: DataFlowPolicy,
    private val clock: Clock,
    private val log: SyncLog = SyncLog.None,
    private val deleteFile: (location: String) -> Boolean = { true },
    private val onSyncNeeded: () -> Unit = {},
) {
    /** 保存用户修订，产生新的不可变 Revision。不自动创建任何 Delivery。 */
    suspend fun saveEdit(recordId: String, newText: String): EditOutcome {
        if (newText.isEmpty()) return EditOutcome.Invalid(EditError.EMPTY_TEXT)
        if (newText.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) return EditOutcome.Invalid(EditError.TEXT_TOO_LONG)
        if (library.isDeleted(recordId)) return EditOutcome.RecordDeleted
        val entry = library.get(recordId) ?: return EditOutcome.RecordNotFound
        val language = entry.revisions.lastOrNull()?.language ?: DEFAULT_LANGUAGE
        val draft = RevisionDraft(
            recordId = recordId,
            text = newText,
            source = RevisionSource.USER_EDIT,
            language = language,
            reviewState = ReviewState.REVIEWED,
            executionLocation = ExecutionLocation.ON_DEVICE,
            engine = null,
            modelId = null,
            modelVersion = null,
            confidence = null,
            createdAt = clock.now(),
        )
        return try {
            val saved = library.publishRevision(draft)
            log.event("revision.edited number=${saved.number}")
            onSyncNeeded()
            EditOutcome.Saved(saved)
        } catch (_: RecordDeletedException) {
            EditOutcome.RecordDeleted
        } catch (_: NoSuchRecordException) {
            EditOutcome.RecordNotFound
        }
    }

    /**
     * 列出收到过比 [latestRevision] 更旧版本的 Destination（合并网关状态与本机发送账）。
     * [automatic] = true 时遵守数据流策略（未授权或严格仅本地时不联网，只看本机账）。
     */
    suspend fun olderVersionRecipients(
        recordId: String,
        latestRevision: Int,
        automatic: Boolean = false,
    ): List<OlderRecipient> {
        if (latestRevision <= 1) return emptyList()
        val cachedDestinations = dispatch.destinations().associateBy { it.destinationId }
        val latestItem = dispatch.item(JobKey(recordId, latestRevision))
        val effectiveIntent = existingIntentFor(recordId, latestRevision)

        val olderFromGateway = queryGatewayDeliveries(recordId, automatic)
            ?.filter { it.revision < latestRevision }
            ?.groupBy { it.destinationId }
            ?.mapValues { (_, list) -> list.maxBy { it.revision } }
            .orEmpty()

        // 兜底：离线或查不到网关时，从本机发送账里找旧版本（1 until latestRevision）里已建或会接收的目标（排除已拒收/不可接收的目标，OCR #29）
        val olderFromLocal = linkedMapOf<String, Pair<Int, String>>()
        for (rev in 1 until latestRevision) {
            val item = dispatch.item(JobKey(recordId, rev)) ?: continue
            for (target in item.targets) {
                if (target.delivery == GatewayDelivery.CREATED || target.willReceive) {
                    val name = target.destinationName ?: cachedDestinations[target.destinationId]?.displayName ?: "未知目的地"
                    olderFromLocal[target.destinationId] = rev to name
                }
            }
        }

        val allIds = (olderFromGateway.keys + olderFromLocal.keys).toList()
        return allIds.map { destId ->
            val gw = olderFromGateway[destId]
            val local = olderFromLocal[destId]
            val dest = cachedDestinations[destId]
            val name = gw?.destinationDisplayName ?: dest?.displayName ?: local?.second ?: "未知目的地"
            val receivedRev = gw?.revision ?: local!!.first
            val olderView = if (gw != null) {
                app.auri.sync.destinations.GatewayStage.fromGateway(gw.state)
                    ?.let { DeliveryView.OnGateway(it) }
                    ?: DeliveryView.Unknown(UnknownReason.UNRECOGNIZED_STATE)
            } else {
                DeliveryView.Unknown(if (automatic) UnknownReason.NOT_ASKED else UnknownReason.GATEWAY_UNREACHABLE)
            }
            val latestTarget = latestItem?.targets?.firstOrNull { it.destinationId == destId }
            val resendState = when {
                latestTarget?.delivery == GatewayDelivery.CREATED -> ResendState.SENT_TO_GATEWAY
                latestTarget?.delivery == GatewayDelivery.REFUSED -> ResendState.NOT_ELIGIBLE
                latestTarget != null && (latestTarget.decision == TargetDecision.CONFIRMED || latestTarget.decision == TargetDecision.AUTO) -> ResendState.PENDING_RESEND
                dest == null || !dest.receivesNewText || !dest.allows(effectiveIntent) -> ResendState.NOT_ELIGIBLE
                else -> ResendState.NOT_REQUESTED
            }
            OlderRecipient(
                destinationId = destId,
                destinationName = name,
                receivedRevision = receivedRev,
                olderDeliveryView = olderView,
                resendState = resendState,
            )
        }
    }

    /** 用户逐个确认把指定 Revision 重发给指定 Destination。默认沿用该 Record 既有版本意图，无记录时再回退到默认意图（OCR #32）。 */
    suspend fun confirmResend(recordId: String, revision: Int, destinationId: String, intent: Intent? = null): ResendResult {
        val chosenIntent = intent ?: existingIntentFor(recordId, revision)
        val result = dispatch.requestResend(JobKey(recordId, revision), destinationId, chosenIntent, clock.now())
        log.event("revision.resend result=${result::class.simpleName}")
        if (result == ResendResult.Requested) onSyncNeeded()
        return result
    }

    private suspend fun existingIntentFor(recordId: String, revision: Int): Intent {
        for (rev in revision downTo 1) {
            dispatch.item(JobKey(recordId, rev))?.let { return it.intent }
        }
        return dispatch.defaultIntent()
    }

    /** 单独删除本地音频而保留文字与全部 Revision（FR-TXT-005）。文件删除失败时不清空库元数据，避免孤儿文件泄漏（OCR #30）。 */
    suspend fun deleteAudioOnly(recordId: String): Boolean {
        val entry = library.get(recordId) ?: return false
        val audio = entry.audio ?: return false
        val removed = try {
            deleteFile(audio.location)
        } catch (_: Exception) {
            false
        }
        if (!removed) {
            log.event("record.audio-delete-failed")
            return false
        }
        val cleared = library.deleteAudio(recordId)
        if (cleared) log.event("record.audio-deleted")
        return cleared
    }

    /** 删除整条 Record：写下本地墓碑、清理本地音频与待同步任务，并触发同步把墓碑送到网关。 */
    suspend fun deleteRecord(recordId: String): Boolean {
        val audioLocation = library.get(recordId)?.audio?.location
        val deleted = library.deleteRecord(recordId, clock.now())
        if (audioLocation != null) {
            try {
                deleteFile(audioLocation)
            } catch (_: Exception) {
                // 磁盘文件已不存在时忽略
            }
        }
        if (deleted) {
            log.event("record.deleted")
            onSyncNeeded()
        }
        return deleted
    }

    private suspend fun queryGatewayDeliveries(recordId: String, automatic: Boolean) = try {
        val gateway = (pairing.load() as? PairingState.Paired)?.gateway?.takeIf { it.needsUser == null }
        if (gateway == null) {
            null
        } else {
            val generation = policy.generation
            if (automatic && !policy.current(gateway.identity).textMayEnterGateway) {
                null
            } else {
                val gate = if (automatic) {
                    object : app.auri.sync.policy.RequestGate {
                        override suspend fun <T : Any> gated(request: suspend () -> T): T? =
                            PolicyGeneration.handoff({ policy.generation == generation }, request)
                    }
                } else {
                    app.auri.sync.policy.RequestGate.Open
                }
                val located = locator.locate(gateway, gate)
                val address = (located as? Located.Reachable)?.address
                if (address == null) {
                    null
                } else {
                    val client = GatewayClient(http.forPin(gateway.pin))
                    val result = gate.gated { client.recordStatus(address, gateway.token, recordId) }
                    (result as? GatewayResult.Success)?.value?.destinations
                }
            }
        }
    } catch (_: Exception) {
        log.event("revision.recipients-query-failed")
        null
    }

    private companion object {
        const val MAX_TEXT_BYTES = 1_048_576
        const val DEFAULT_LANGUAGE = "zh"
    }
}
