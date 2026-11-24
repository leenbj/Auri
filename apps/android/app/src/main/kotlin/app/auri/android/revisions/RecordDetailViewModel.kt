package app.auri.android.revisions

import androidx.lifecycle.ViewModel
import app.auri.capture.coordinator.Clock
import app.auri.capture.library.RecordEntry
import app.auri.capture.library.RecordLibrary
import app.auri.capture.library.Revision
import app.auri.sync.revisions.EditError
import app.auri.sync.revisions.EditOutcome
import app.auri.sync.revisions.ExportFormat
import app.auri.sync.revisions.OlderRecipient
import app.auri.sync.revisions.RecordExporter
import app.auri.sync.revisions.RecordRevisionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RecordDetailUiState(
    val loaded: Boolean = false,
    val record: RecordEntry? = null,
    val deleted: Boolean = false,
    val saving: Boolean = false,
    val revisions: List<Revision> = emptyList(),
    val draftText: String = "",
    val editError: EditError? = null,
    val olderRecipients: List<OlderRecipient> = emptyList(),
    val deleteRecordNotice: String = RevisionDescriptions.DELETE_RECORD_NOTICE,
    val noAutoResendNotice: String = RevisionDescriptions.NO_AUTO_RESEND_NOTICE,
    val pendingExport: PreparedExport? = null,
    val notice: String? = null,
)

/**
 * 单条 Record 详情、修订历史、编辑保存、逐个确认重发、仅删音频与删除整条 Record 的视图模型（FR-TXT-001/002/005）。
 */
class RecordDetailViewModel(
    private val recordId: String,
    private val library: RecordLibrary,
    private val service: RecordRevisionService,
    private val clock: Clock,
    private val scope: CoroutineScope,
) : ViewModel() {
    private data class LocalState(
        val loaded: Boolean = false,
        val saving: Boolean = false,
        val draftText: String = "",
        val draftInitialized: Boolean = false,
        val editError: EditError? = null,
        val olderRecipients: List<OlderRecipient> = emptyList(),
        val deleted: Boolean = false,
        val pendingExport: PreparedExport? = null,
        val notice: String? = null,
    )

    private val local = MutableStateFlow(LocalState())

    val state: StateFlow<RecordDetailUiState> = combine(library.observe(), local) { list, ui ->
        val entry = list.firstOrNull { it.recordId == recordId }
        val revisions = entry?.revisions.orEmpty()
        val draft = if (ui.draftInitialized) ui.draftText else (revisions.lastOrNull()?.text.orEmpty())
        RecordDetailUiState(
            loaded = ui.loaded || entry != null,
            record = entry,
            deleted = ui.deleted,
            saving = ui.saving,
            revisions = revisions,
            draftText = draft,
            editError = ui.editError,
            olderRecipients = ui.olderRecipients,
            pendingExport = ui.pendingExport,
            notice = ui.notice,
        )
    }.stateIn(scope, SharingStarted.Eagerly, RecordDetailUiState())

    init {
        scope.launch {
            val isDeleted = library.isDeleted(recordId)
            val entry = library.get(recordId)
            val latest = entry?.revisions?.lastOrNull()
            val cur = local.value
            // OCR #5：若用户在初始化完成前已开始输入草稿，不得用旧正文覆盖用户输入
            local.value = cur.copy(
                loaded = true,
                deleted = isDeleted,
                draftText = if (cur.draftInitialized) cur.draftText else latest?.text.orEmpty(),
                draftInitialized = true,
            )
            if (latest != null && latest.number > 1) {
                refreshRecipients(automatic = true)
            }
        }
    }

    /** 编辑框草稿变化：未保存的内容绝不产生 Revision（FR-TXT-002）。 */
    fun updateDraft(text: String) {
        local.value = local.value.copy(draftText = text, draftInitialized = true, editError = null)
    }

    /** 保存修订：产生新的不可变 Revision，默认不自动重发，并刷新收到过旧版本的目的地列表。带保存中防重入（OCR #26）。 */
    fun saveEdit() {
        if (local.value.saving) return
        val text = state.value.draftText
        local.value = local.value.copy(saving = true, editError = null)
        scope.launch {
            try {
                when (val outcome = service.saveEdit(recordId, text)) {
                    is EditOutcome.Saved -> {
                        local.value = local.value.copy(
                            saving = false,
                            draftText = outcome.revision.text,
                            draftInitialized = true,
                            editError = null,
                            notice = "已保存为第 ${outcome.revision.number} 版（未自动重发）",
                        )
                        refreshRecipients(automatic = true)
                    }

                    is EditOutcome.Invalid -> {
                        local.value = local.value.copy(saving = false, editError = outcome.error)
                    }

                    EditOutcome.RecordDeleted -> {
                        local.value = local.value.copy(saving = false, deleted = true, notice = "这条记录已被删除，无法保存修订")
                    }

                    EditOutcome.RecordNotFound -> {
                        local.value = local.value.copy(saving = false, notice = "找不到这条记录")
                    }
                }
            } catch (_: Exception) {
                local.value = local.value.copy(saving = false, notice = "保存失败，请重试")
            }
        }
    }

    fun refreshRecipients(automatic: Boolean = false) {
        scope.launch {
            val latestNum = library.get(recordId)?.revisions?.lastOrNull()?.number ?: return@launch
            val recipients = service.olderVersionRecipients(recordId, latestNum, automatic = automatic)
            local.value = local.value.copy(olderRecipients = recipients)
        }
    }

    /** 用户逐个确认把当前最新 Revision 重发给指定 Destination。 */
    fun confirmResend(destinationId: String) {
        scope.launch {
            val latestNum = library.get(recordId)?.revisions?.lastOrNull()?.number ?: return@launch
            val result = service.confirmResend(recordId, latestNum, destinationId)
            local.value = local.value.copy(notice = RevisionDescriptions.describeResendResult(result))
            refreshRecipients(automatic = true)
        }
    }

    /** 单独删除本地音频而保留文字与全部 Revision。 */
    fun deleteAudioOnly() {
        scope.launch {
            val removed = service.deleteAudioOnly(recordId)
            local.value = local.value.copy(
                notice = if (removed) "已删除本地音频，文字与历史版本已保留" else "该记录当前没有本地音频",
            )
        }
    }

    /** 删除整条 Record（本地墓碑 + 同步删除网关副本）。 */
    fun deleteRecord() {
        scope.launch {
            val deleted = service.deleteRecord(recordId)
            local.value = local.value.copy(
                deleted = true,
                notice = if (deleted) "已删除记录，将同步删除网关副本" else "记录已删除",
            )
        }
    }

    fun prepareExport(format: ExportFormat) {
        val entry = state.value.record ?: return
        val content = RecordExporter.format(listOf(entry), format)
        val fileName = RecordExporter.safeFileName(format, clock.now(), singleRecordId = entry.recordId)
        local.value = local.value.copy(pendingExport = PreparedExport(fileName, format.mimeType, content))
    }

    fun consumeExport() {
        local.value = local.value.copy(pendingExport = null)
    }

    fun reportExportFailed() {
        local.value = local.value.copy(pendingExport = null, notice = "导出文件写入失败，请重试")
    }

    fun dismissNotice() {
        local.value = local.value.copy(notice = null)
    }

    override fun onCleared() {
        scope.cancel()
    }
}
