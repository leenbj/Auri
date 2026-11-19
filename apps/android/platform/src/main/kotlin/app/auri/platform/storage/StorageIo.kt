package app.auri.platform.storage

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * 录音存储碰磁盘的所有入口，集中在这一条缝上：生产环境用真的（[Real]），测试换成会写满、会在第几个字节被杀、会记下操作顺序的假磁盘。
 * 存储里的其余代码（日志格式、分段提交、验证、截断）都是真的，不会因为换了这条缝就变成测试替身。
 */
internal class StorageIo(
    val open: (File) -> RandomAccessFile = { RandomAccessFile(it, "rw") },
    /** 把已写的内容落到介质上；第二个参数是文件名，只给测试记录顺序用。 */
    val sync: (RandomAccessFile, String) -> Unit = { file, _ -> file.fd.sync() },
    /** 让目录项（新建、改名）落盘：只 fsync 文件，不保证掉电之后还能在目录里找到它。 */
    val syncDirectory: (File) -> Unit = { directory -> FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) } },
    val move: (File, File) -> Unit = { from, to -> Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE) },
) {
    companion object {
        val Real = StorageIo()
    }
}
