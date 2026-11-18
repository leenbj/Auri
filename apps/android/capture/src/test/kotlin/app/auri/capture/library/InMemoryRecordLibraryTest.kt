package app.auri.capture.library

import app.auri.capture.testing.InMemoryRecordLibrary
import app.auri.capture.testing.RecordLibraryContract

/** JVM 里的内存库过契约测试：它是 RecordPipeline 测试用的替身，必须和 SQLite 库行为一致。 */
class InMemoryRecordLibraryTest : RecordLibraryContract() {
    override fun newLibrary(publicationStep: (Revision) -> Unit): RecordLibrary = InMemoryRecordLibrary(publicationStep)
}
