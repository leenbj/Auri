package app.auri.platform.storage

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The next capture uses the persisted limit; invalid or unreadable files use 30 minutes. */
class DurationLimitSetting(private val file: File) {
    private val current = MutableStateFlow(
        try { file.readText().trim().toIntOrNull()?.takeIf { it in 1..120 } ?: 30 } catch (_: Exception) { 30 },
    )
    val minutes: StateFlow<Int> = current.asStateFlow()

    @Synchronized
    fun save(minutes: Int) {
        require(minutes in 1..120) { "时长上限必须为 1–120 分钟" }
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        try {
            temporary.writeText(minutes.toString())
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            temporary.delete()
            throw e
        }
        current.value = minutes
    }
}
