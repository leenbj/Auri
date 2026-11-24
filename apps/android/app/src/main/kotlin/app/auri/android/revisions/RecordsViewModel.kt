package app.auri.android.revisions

import androidx.lifecycle.ViewModel
import app.auri.capture.coordinator.Clock
import app.auri.capture.library.AudioRetentionCleaner
import app.auri.capture.library.RecordEntry
import app.auri.capture.library.RecordLibrary
import app.auri.platform.storage.AudioRetentionSetting
import app.auri.sync.destinations.DispatchStore
import app.auri.sync.engine.JobKey
import app.auri.sync.engine.SyncJob
import app.auri.sync.engine.SyncJobStore
import app.auri.sync.revisions.ExportFormat
import app.auri.sync.revisions.RecordExporter
import app.auri.sync.revisions.RecordFilter
import app.auri.sync.revisions.RecordQuery
import app.auri.sync.revisions.SendStatusFilter
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class TimeRangePreset { ALL, TODAY, LAST_7_DAYS, LAST_30_DAYS }

data class PreparedExport(val fileName: String, val mimeType: String, val content: String)

data class RecordsUiState(
    val records: List<RecordEntry> = emptyList(),
    val keyword: String = "",
    val timePreset: TimeRangePreset = TimeRangePreset.ALL,
    val sendStatus: SendStatusFilter = SendStatusFilter.ALL,
    val retentionDays: Int = AudioRetentionCleaner.DEFAULT_RETENTION_DAYS,
    val retentionError: String? = null,
    val pendingExport: PreparedExport? = null,
    val notice: String? = null,
)

/**
 * 记录库列表、筛选、音频保留期设置与批量导出（TXT/Markdown）的视图模型（FR-TXT-004 / FR-TXT-005）。
 */
class RecordsViewModel(
    library: RecordLibrary,
    jobs: SyncJobStore,
    private val dispatch: DispatchStore,
    private val retentionSetting: AudioRetentionSetting,
    private val retentionCleaner: AudioRetentionCleaner,
    private val clock: Clock,
    private val scope: CoroutineScope,
) : ViewModel() {
    private data class FilterInputs(
        val keyword: String = "",
        val timePreset: TimeRangePreset = TimeRangePreset.ALL,
        val sendStatus: SendStatusFilter = SendStatusFilter.ALL,
    )

    private data class UiExtras(
        val retentionError: String? = null,
        val pendingExport: PreparedExport? = null,
        val notice: String? = null,
    )

    private val filters = MutableStateFlow(FilterInputs())
    private val extras = MutableStateFlow(UiExtras())
    private val awaitingKeys = MutableStateFlow<Set<JobKey>>(emptySet())

    init {
        scope.launch {
            dispatch.changes().onStart { emit(0L) }.collect {
                awaitingKeys.value = try {
                    dispatch.awaiting().mapTo(HashSet()) { it.key }
                } catch (_: Exception) {
                    emptySet()
                }
            }
        }
    }

    val state: StateFlow<RecordsUiState> = combine(
        library.observe(),
        jobs.observe(),
        awaitingKeys,
        filters,
        retentionSetting.days,
    ) { records, syncJobs, awaiting, filter, days ->
        val query = buildQuery(filter, clock.now())
        val filtered = RecordFilter.apply(records, syncJobs, awaiting, query)
        RecordsUiState(
            records = filtered,
            keyword = filter.keyword,
            timePreset = filter.timePreset,
            sendStatus = filter.sendStatus,
            retentionDays = days,
        )
    }.combine(extras) { base, extra ->
        base.copy(
            retentionError = extra.retentionError,
            pendingExport = extra.pendingExport,
            notice = extra.notice,
        )
    }.stateIn(scope, SharingStarted.Eagerly, RecordsUiState(retentionDays = retentionSetting.days.value))

    fun setKeyword(keyword: String) {
        filters.value = filters.value.copy(keyword = keyword)
    }

    fun setTimePreset(preset: TimeRangePreset) {
        filters.value = filters.value.copy(timePreset = preset)
    }

    fun setSendStatus(status: SendStatusFilter) {
        filters.value = filters.value.copy(sendStatus = status)
    }

    fun setRetentionDays(input: String) {
        val days = input.trim().toIntOrNull()
        if (days == null || days !in AudioRetentionCleaner.MIN_RETENTION_DAYS..AudioRetentionCleaner.MAX_RETENTION_DAYS) {
            extras.value = extras.value.copy(
                retentionError = "请输入 ${AudioRetentionCleaner.MIN_RETENTION_DAYS}–${AudioRetentionCleaner.MAX_RETENTION_DAYS} 天",
            )
            return
        }
        scope.launch {
            try {
                retentionSetting.save(days)
                extras.value = extras.value.copy(retentionError = null, notice = "音频保留期已设为 $days 天")
                val report = retentionCleaner.purgeExpired(clock.now(), days)
                if (report.purgedCount > 0 || report.failedCount > 0) {
                    val msg = if (report.failedCount > 0) {
                        "已清理 ${report.purgedCount} 条过期音频，另有 ${report.failedCount} 条未能清理（将在下次重试）"
                    } else {
                        "已按保留期清理 ${report.purgedCount} 条过期音频，文字均已保留"
                    }
                    extras.value = extras.value.copy(notice = msg)
                }
            } catch (_: Exception) {
                extras.value = extras.value.copy(retentionError = "保留期保存失败，请重试")
            }
        }
    }

    fun purgeExpiredAudioNow() {
        scope.launch {
            val report = retentionCleaner.purgeExpired(clock.now(), retentionSetting.days.value)
            if (report.purgedCount > 0 || report.failedCount > 0) {
                val msg = if (report.failedCount > 0) {
                    "已清理 ${report.purgedCount} 条过期音频，另有 ${report.failedCount} 条未能清理（将在下次重试）"
                } else {
                    "已按保留期清理 ${report.purgedCount} 条过期音频，文字均已保留"
                }
                extras.value = extras.value.copy(notice = msg)
            }
        }
    }

    /** 由用户主动触发导出当前筛选列表为 TXT 或 Markdown（文件名不含正文敏感信息）。 */
    fun prepareExport(format: ExportFormat) {
        val current = state.value.records
        val content = RecordExporter.format(current, format)
        val fileName = RecordExporter.safeFileName(format, clock.now())
        extras.value = extras.value.copy(pendingExport = PreparedExport(fileName, format.mimeType, content))
    }

    fun consumeExport() {
        extras.value = extras.value.copy(pendingExport = null)
    }

    fun reportExportFailed() {
        extras.value = extras.value.copy(pendingExport = null, notice = "导出文件写入失败，请重试")
    }

    fun dismissNotice() {
        extras.value = extras.value.copy(notice = null)
    }

    override fun onCleared() {
        scope.cancel()
    }

    private fun buildQuery(inputs: FilterInputs, now: Instant): RecordQuery {
        val from = when (inputs.timePreset) {
            TimeRangePreset.ALL -> null
            TimeRangePreset.TODAY -> now.atZone(ZoneId.systemDefault()).toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant()
            TimeRangePreset.LAST_7_DAYS -> now.minus(Duration.ofDays(7))
            TimeRangePreset.LAST_30_DAYS -> now.minus(Duration.ofDays(30))
        }
        return RecordQuery(from = from, to = null, keyword = inputs.keyword, sendStatus = inputs.sendStatus)
    }
}
