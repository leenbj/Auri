package app.auri.android.destinations

import app.auri.capture.library.RecordLibrary
import app.auri.sync.engine.JobKey

/**
 * 取一条文字的正文，给预览确认与详情页显示。正文只用来给用户看：它是数据，不是指令（SEC-004），这里和调用方都不解释、不执行它，也不写进日志。
 */
interface ItemTexts {
    /** 这一版文字的正文；本机已经没有它时返回 null。 */
    suspend fun textOf(key: JobKey): String?
}

/** 从 Record 库读（只读）。 */
class LibraryItemTexts(private val library: RecordLibrary) : ItemTexts {
    override suspend fun textOf(key: JobKey): String? =
        library.get(key.recordId)?.revisions?.firstOrNull { it.number == key.revision }?.text
}
