package app.auri.sync.engine

import app.auri.capture.library.RecordLibrary
import app.auri.sync.gateway.Publication
import app.auri.sync.gateway.RevisionSubmission
import app.auri.sync.gateway.SubmissionBuilder

/**
 * 一条文字**发送那一刻**的 publication：意图加要为哪些 Destination 创建 Delivery。由发送账（DispatchStore）回答。
 * 引擎每次尝试发送都重新问一次，所以用户在排队期间撤销了某个 Destination，这次发出去的就已经不含它了。
 */
fun interface PublicationSource {
    suspend fun publicationFor(key: JobKey): Publication

    companion object {
        /** 只入库、不投递：Ticket 21 的行为，也是没有接发送账时的样子。 */
        val StoreOnly = PublicationSource { Publication.StoreOnly }
    }
}

/**
 * 引擎读“要发的那条 Revision”的地方：本地 Record 库里的这一条，经 [SubmissionBuilder] 组成请求。库里没有或不合规就是 null。
 * publication（意图与目标）每次都经 [publications] 现问；不给就是只入库。
 */
class RecordLibrarySubmissionSource(
    private val library: RecordLibrary,
    private val builder: SubmissionBuilder,
    private val publications: PublicationSource = PublicationSource.StoreOnly,
) : SubmissionSource {
    override suspend fun submissionFor(key: JobKey): RevisionSubmission? {
        val entry = library.get(key.recordId) ?: return null
        val revision = entry.revisions.firstOrNull { it.number == key.revision } ?: return null
        return builder.build(entry, revision, publications.publicationFor(key))
    }
}
