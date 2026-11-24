package app.auri.sync.engine

import app.auri.capture.library.RecordEntry
import app.auri.capture.library.RecordLibrary
import app.auri.capture.library.Revision
import app.auri.sync.gateway.Publication
import app.auri.sync.gateway.RevisionSubmission
import app.auri.sync.gateway.SubmissionBuilder

/**
 * 一条文字**发送那一刻**的 publication：意图加要为哪些 Destination 创建 Delivery。由发送账（DispatchStore）回答。
 * 引擎每次尝试发送都重新问一次，所以用户在排队期间撤销了某个 Destination，这次发出去的就已经不含它了。
 */
fun interface PublicationSource {
    suspend fun publicationFor(key: JobKey): Publication

    /** [publicationFor] 冻结了标签、请求却没有离开手机：放开冻结（见 [SubmissionSource.unfreezeUnsent]）。没有冻结的来源什么也不做。 */
    suspend fun unfreezeUnsent(key: JobKey) = Unit

    companion object {
        /** 只入库、不投递：Ticket 21 的行为，也是没有接发送账时的样子。 */
        val StoreOnly = PublicationSource { Publication.StoreOnly }
    }
}

/**
 * 引擎读“要发的那条 Revision”的地方：本地 Record 库里的这一条，经 [SubmissionBuilder] 组成请求。库里没有或不合规就是 null。
 * publication（意图与目标）每次都经 [publications] 现问；不给就是只入库。
 *
 * 问 [publications] 就是冻结意图标签（评审 M3），所以只有 [submissionFor] 问它；[textSha256For] 不问（复评 R13）。
 */
class RecordLibrarySubmissionSource(
    private val library: RecordLibrary,
    private val builder: SubmissionBuilder,
    private val publications: PublicationSource = PublicationSource.StoreOnly,
) : SubmissionSource {
    override suspend fun submissionFor(key: JobKey): RevisionSubmission? {
        val (entry, revision) = find(key) ?: return null
        return builder.build(entry, revision, publications.publicationFor(key))
    }

    override suspend fun textSha256For(key: JobKey): String? {
        val (entry, revision) = find(key) ?: return null
        return builder.build(entry, revision)?.textSha256
    }

    override suspend fun unfreezeUnsent(key: JobKey) = publications.unfreezeUnsent(key)

    private suspend fun find(key: JobKey): Pair<RecordEntry, Revision>? {
        val entry = library.get(key.recordId) ?: return null
        val revision = entry.revisions.firstOrNull { it.number == key.revision } ?: return null
        return entry to revision
    }
}
