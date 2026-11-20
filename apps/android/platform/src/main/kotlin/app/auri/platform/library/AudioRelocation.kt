package app.auri.platform.library

import java.io.File

/**
 * 登记的音频位置里“目录那一段”的写法：目录的绝对路径加一个分隔符。
 * 带分隔符，所以 `recordings` 不会误伤 `recordings-old/…` 里的位置。
 */
fun audioDirPrefix(dir: File): String = dir.absolutePath.trimEnd('/') + "/"

/**
 * Ticket 19 评审 H1：升级时录音目录从 filesDir 整个挪到 noBackupFilesDir（[app.auri.platform.storage.NoBackupStorage.place]，一次原子改名），
 * 库里登记的音频位置却还是老目录下的绝对路径，等转录的、重新转录的 Record 打开的就是不存在的文件。
 *
 * 这里让库里的位置跟着目录走，**每次启动都调用**，所以挪目录与改库之间无论在哪里被杀，下次启动都会把没做完的补上：
 *  - 目录挪了、库还没改：老目录已经不在，补改库；
 *  - 什么都没做：先挪（[app.auri.platform.storage.NoBackupStorage.place]），再改库；
 *  - 库先改了、目录还没挪（别的版本可能这样做）：这里没有行再以老目录开头，什么也不改；目录随后照常被挪走。
 * [relocate] 必须幂等（改过的行不再匹配老前缀），并且是一次原子的整体更新。
 *
 * 只有老目录**已经不在**时才改：它还在，说明目录没挪成（或两处都有），此刻登记的位置指着有文件的地方，
 * 改了反而会指向没有文件的地方。
 *
 * @return 改了几行
 */
suspend fun relocateRecordedAudio(
    legacyDir: File,
    currentDir: File,
    relocate: suspend (fromPrefix: String, toPrefix: String) -> Int,
): Int {
    val from = audioDirPrefix(legacyDir)
    val to = audioDirPrefix(currentDir)
    if (from == to) return 0
    if (legacyDir.exists()) return 0
    return relocate(from, to)
}
