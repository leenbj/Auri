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
 *  - 写：先写临时文件再原子替换，保存到一半被打断时旧档案原样保留；写不进去（含写到一半失败）或替换不了
 *    （通常是 [IOException]，框架抛别的异常也一样），先清掉临时文件再把原异常抛出，由调用方告诉用户“没保存上”。
 */
class FileProfileStore(
    private val file: File,
    /** 把写好的临时文件换到正式位置；测试替换它来模拟文件系统抛出各种异常。 */
    private val replace: (from: File, to: File) -> Unit = { from, to ->
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    },
) : ProfileStore {
    override fun load(): HeadsetProfile? = try {
        if (file.isFile) HeadsetProfile.fromJson(file.readText(Charsets.UTF_8)) else null
    } catch (e: Exception) {
        null
    }

    override fun save(profile: HeadsetProfile) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            temp.writeText(profile.toJson(), Charsets.UTF_8)
            replace(temp, file)
        } catch (e: Exception) {
            // 不只是 IOException：框架的任何异常都要先清掉临时文件，再把原异常抛给调用方
            temp.delete()
            throw e
        }
    }
}
