package app.auri.sync.revisions

import app.auri.capture.library.RecordEntry
import app.auri.sync.engine.JobKey
import app.auri.sync.engine.SyncJob
import app.auri.sync.machine.SyncState
import java.time.Instant

/** 发送状态筛选项（FR-TXT-004）。 */
enum class SendStatusFilter {
    ALL,
    STORED,
    WAITING_GATEWAY,
    NEEDS_USER,
    AWAITING_CONFIRM,
    LOCAL_ONLY,
}

/** Record 列表筛选条件：时间范围（闭区间）、关键词（不区分大小写，匹配任意 Revision 正文）、发送状态。 */
data class RecordQuery(
    val from: Instant? = null,
    val to: Instant? = null,
    val keyword: String = "",
    val sendStatus: SendStatusFilter = SendStatusFilter.ALL,
)

object RecordFilter {
    fun apply(
        records: List<RecordEntry>,
        jobs: List<SyncJob>,
        awaitingKeys: Set<JobKey>,
        query: RecordQuery,
    ): List<RecordEntry> {
        val jobsByKey = jobs.associateBy { it.key }
        val keyword = query.keyword.trim()
        return records.filter { entry ->
            matchesTime(entry, query.from, query.to) &&
                matchesKeyword(entry, keyword) &&
                matchesSendStatus(entry, jobsByKey, awaitingKeys, query.sendStatus)
        }
    }

    private fun matchesTime(entry: RecordEntry, from: Instant?, to: Instant?): Boolean {
        if (from != null && entry.startedAt.isBefore(from)) return false
        if (to != null && entry.startedAt.isAfter(to)) return false
        return true
    }

    private fun matchesKeyword(entry: RecordEntry, keyword: String): Boolean {
        if (keyword.isEmpty()) return true
        return entry.revisions.any { it.text.contains(keyword, ignoreCase = true) }
    }

    private fun matchesSendStatus(
        entry: RecordEntry,
        jobsByKey: Map<JobKey, SyncJob>,
        awaitingKeys: Set<JobKey>,
        filter: SendStatusFilter,
    ): Boolean {
        if (filter == SendStatusFilter.ALL) return true
        val latestRev = entry.revisions.lastOrNull()
        val latestJob = latestRev?.let { jobsByKey[JobKey(entry.recordId, it.number)] }
        return when (filter) {
            SendStatusFilter.ALL -> true
            SendStatusFilter.STORED -> latestJob?.state == SyncState.STORED
            SendStatusFilter.WAITING_GATEWAY -> latestJob?.state in WAITING_STATES
            SendStatusFilter.NEEDS_USER -> latestJob?.state == SyncState.BLOCKED
            SendStatusFilter.AWAITING_CONFIRM -> entry.revisions.any { JobKey(entry.recordId, it.number) in awaitingKeys }
            SendStatusFilter.LOCAL_ONLY -> latestRev == null || latestJob == null
        }
    }

    private val WAITING_STATES = setOf(
        SyncState.PENDING,
        SyncState.SENDING,
        SyncState.RETRY_SCHEDULED,
        SyncState.WAITING_GATEWAY,
    )
}
