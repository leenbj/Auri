package app.auri.platform.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.auri.capture.library.RecordLibrary
import app.auri.capture.library.Revision
import app.auri.capture.testing.RecordLibraryContract
import java.util.UUID
import org.junit.After

/**
 * SQLite 库与内存库过**同一份**行为合同（RecordLibraryContract，在 :capture 的 testFixtures 里）。
 * 这份只能在 Android 上跑（框架自带的 SQLite）：模拟器上是 E2，真机上才是 E3。
 */
class SqliteRecordLibraryContractTest : RecordLibraryContract() {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val opened = mutableListOf<Pair<SqliteRecordLibrary, String>>()

    override fun newLibrary(publicationStep: (Revision) -> Unit): RecordLibrary {
        val name = "contract-${UUID.randomUUID()}.db"
        return SqliteRecordLibrary(context, name, publicationStep = { _, revision -> publicationStep(revision) })
            .also { opened += it to name }
    }

    @After
    fun cleanUp() {
        opened.forEach { (library, name) ->
            library.close()
            context.deleteDatabase(name)
        }
    }
}
