package app.auri.platform.storage

import app.auri.capture.calibration.HeadsetProfile
import app.auri.capture.calibration.ProfileStore
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 当前档案存在应用私有目录里的一个 JSON 文件（合同 Headset Profile 的格式，也是真机复测时要取出来校验的那份）。
 *  - 读：文件不存在、读不出来、内容违反合同（[HeadsetProfile.fromJson] 与保存时同一套规则）一律是 null，不抛异常；
 *  - 写：先写临时文件再原子替换，保存到一半被打断时旧档案原样保留；写不进去就抛 [IOException]，由调用方告诉用户“没保存上”。
 */
class FileProfileStore(private val file: File) : ProfileStore {
    override fun load(): HeadsetProfile? = try {
        if (file.isFile) HeadsetProfile.fromJson(file.readText(Charsets.UTF_8)) else null
    } catch (e: Exception) {
        null
    }

    override fun save(profile: HeadsetProfile) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(profile.toJson(), Charsets.UTF_8)
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            temp.delete()
            throw e
        }
    }
}
