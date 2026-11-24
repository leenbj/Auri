package app.auri.platform.storage

import app.auri.capture.library.AudioRetentionCleaner
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 音频保留期偏好设置（FR-TXT-005）：默认转录成功后 30 天，合法范围 1–365 天。
 * 文件缺失或损坏时回退到 30 天；写入使用临时文件原子替换。
 */
class AudioRetentionSetting(private val file: File) {
    private val current = MutableStateFlow(
        try {
            file.readText().trim().toIntOrNull()
                ?.takeIf { it in AudioRetentionCleaner.MIN_RETENTION_DAYS..AudioRetentionCleaner.MAX_RETENTION_DAYS }
                ?: AudioRetentionCleaner.DEFAULT_RETENTION_DAYS
        } catch (_: Exception) {
            AudioRetentionCleaner.DEFAULT_RETENTION_DAYS
        },
    )
    val days: StateFlow<Int> = current.asStateFlow()

    @Synchronized
    fun save(days: Int) {
        require(days in AudioRetentionCleaner.MIN_RETENTION_DAYS..AudioRetentionCleaner.MAX_RETENTION_DAYS) {
            "音频保留期必须为 ${AudioRetentionCleaner.MIN_RETENTION_DAYS}–${AudioRetentionCleaner.MAX_RETENTION_DAYS} 天"
        }
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        try {
            temporary.writeText(days.toString())
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            temporary.delete()
            throw e
        }
        current.value = days
    }
}
