package app.auri.capture.testing

import app.auri.capture.contract.ErrorCode
import app.auri.capture.library.NoSuchRecordException
import app.auri.capture.library.RecordEntry
import app.auri.capture.library.RecordLibrary
import app.auri.capture.library.Revision
import app.auri.capture.library.RevisionDraft
import app.auri.capture.library.TranscriptionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 内存里的 Record 库，只给 JVM 测试用（SQLite 库在 `:platform`，两者过同一套 RecordLibraryContract，行为才有保证）。
 * [publicationStep] 在发布 Revision 的“事务”里、写入之前执行；抛异常就什么都不留。
 */
class InMemoryRecordLibrary(private val publicationStep: (Revision) -> Unit = {}) : RecordLibrary {
    private val records = MutableStateFlow<List<RecordEntry>>(emptyList())

    override suspend fun addRecord(entry: RecordEntry): Boolean = synchronized(this) {
        if (records.value.any { it.recordId == entry.recordId }) return@synchronized false
        records.value = records.value + entry
        true
    }

    override suspend fun publishRevision(draft: RevisionDraft): Revision = synchronized(this) {
        val current = records.value.firstOrNull { it.recordId == draft.recordId } ?: throw NoSuchRecordException(draft.recordId)
        val existing = draft.publicationKey?.let { key -> records.value.flatMap { it.revisions }.firstOrNull { it.publicationKey == key } }
        if (existing != null) {
            require(existing.recordId == draft.recordId)
            return@synchronized existing
        }
        val revision = draft.toRevision(current.revisions.size + 1)
        publicationStep(revision) // 失败就在这里抛出：下面的写入不会发生
        replace(current.copy(revisions = current.revisions + revision, transcription = TranscriptionStatus.DONE, failureCode = null))
        revision
    }

    override suspend fun markTranscription(recordId: String, status: TranscriptionStatus, failureCode: ErrorCode?) = synchronized(this) {
        val current = records.value.firstOrNull { it.recordId == recordId } ?: throw NoSuchRecordException(recordId)
        check(current.transcription != TranscriptionStatus.DONE) { "已经有 Revision 的 Record 不能再改转录状态" }
        require(status != TranscriptionStatus.DONE) { "DONE 只能通过发布 Revision 得到" }
        replace(current.copy(transcription = status, failureCode = failureCode))
    }

    override suspend fun get(recordId: String): RecordEntry? = records.value.firstOrNull { it.recordId == recordId }

    override fun observe(): Flow<List<RecordEntry>> = records.map { list -> list.sortedWith(NEWEST_FIRST) }

    override suspend fun incomplete(): List<RecordEntry> = records.value.filter {
        it.audio != null && (it.transcription == TranscriptionStatus.PENDING || it.transcription == TranscriptionStatus.WAITING_MODEL)
    }

    private fun replace(updated: RecordEntry) {
        records.value = records.value.map { if (it.recordId == updated.recordId) updated else it }
    }

    private companion object {
        val NEWEST_FIRST: Comparator<RecordEntry> = compareByDescending { it.startedAt }
    }
}
