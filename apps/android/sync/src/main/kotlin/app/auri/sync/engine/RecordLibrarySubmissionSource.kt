package app.auri.sync.engine

import app.auri.capture.library.RecordLibrary
import app.auri.sync.gateway.RevisionSubmission
import app.auri.sync.gateway.SubmissionBuilder

/** 引擎读“要发的那条 Revision”的地方：本地 Record 库里的这一条，经 [SubmissionBuilder] 组成请求。库里没有或不合规就是 null。 */
class RecordLibrarySubmissionSource(private val library: RecordLibrary, private val builder: SubmissionBuilder) : SubmissionSource {
    override suspend fun submissionFor(key: JobKey): RevisionSubmission? {
        val entry = library.get(key.recordId) ?: return null
        val revision = entry.revisions.firstOrNull { it.number == key.revision } ?: return null
        return builder.build(entry, revision)
    }
}
