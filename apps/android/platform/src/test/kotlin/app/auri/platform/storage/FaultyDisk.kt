package app.auri.platform.storage

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * 测试用的“会出故障的磁盘”：只替换 [StorageIo] 这一条缝，存储的其余代码都是真的，文件也是真文件。
 *
 *  - [freeBytes]：磁盘还剩多少空间。只有文件**变长**才消耗空间（覆盖已有的字节不要钱，截短会还回去），
 *    写不下时先写进能放下的那一截、再抛 IOException，和真正的 ENOSPC 一样会留下半截；
 *  - [killAfterBytes]：进程在累计写出这么多字节之后被杀：写出刚好这么多（可能撕在一条日志的中间或一个采样的中间），
 *    然后抛 [ProcessKilled]。它是 Error，存储里的 catch (IOException / Exception) 拦不住，像真的被杀一样什么清理都来不及做；
 *  - [log]：按发生顺序记下 open / write / sync / syncDir / move，用来断言“先落数据、再写日志”这类顺序。
 *
 * fsync 在测试里是记一笔就算（[realSync] 为 false 时）：这里模拟的是进程被杀，页缓存里的数据不会丢；
 * 掉电时没落盘的字节怎么办，由直接截断、改写文件的测试负责。
 */
internal class FaultyDisk(var freeBytes: Long = Long.MAX_VALUE, var killAfterBytes: Long = Long.MAX_VALUE, private val realSync: Boolean = false) {
    class ProcessKilled : Error("进程被杀（测试模拟）")

    val log = mutableListOf<String>()
    var written = 0L
        private set

    /** 非 null 时，下一次 move 抛它（之后恢复正常）。可以是 Error 来模拟 move 时被杀。 */
    var failMoveOnce: Throwable? = null

    private fun open(file: File): RandomAccessFile {
        log += "open:${file.name}"
        return object : RandomAccessFile(file, "rw") {
            override fun write(b: ByteArray, off: Int, len: Int) {
                var count = len
                var failure: Throwable? = null
                if (killAfterBytes - written < count) {
                    count = (killAfterBytes - written).toInt()
                    failure = ProcessKilled()
                }
                val pointer = filePointer
                val size = length()
                val growth = maxOf(0L, pointer + count - size)
                if (growth > freeBytes) {
                    count -= (growth - freeBytes).toInt()
                    failure = IOException("No space left on device（测试注入）")
                }
                if (count > 0) {
                    val grew = maxOf(0L, pointer + count - size)
                    super.write(b, off, count)
                    written += count
                    freeBytes -= grew
                    log += "write:${file.name}:$count"
                }
                if (failure != null) throw failure
            }

            override fun write(b: ByteArray) = write(b, 0, b.size)

            override fun setLength(newLength: Long) {
                val old = length()
                super.setLength(newLength)
                // 默认的“无限空间”是 Long.MAX_VALUE：还回去的字节必须饱和相加，否则溢出成负数，之后每次写都被当成写满
                if (newLength < old) freeBytes = if (freeBytes > Long.MAX_VALUE - (old - newLength)) Long.MAX_VALUE else freeBytes + (old - newLength)
                log += "setLength:${file.name}:$newLength"
            }
        }
    }

    val io = StorageIo(
        open = ::open,
        sync = { file, name ->
            log += "sync:$name"
            if (realSync) file.fd.sync()
        },
        syncDirectory = { log += "syncDir" },
        move = { from, to ->
            failMoveOnce?.let { failure -> failMoveOnce = null; throw failure }
            log += "move:${from.name}->${to.name}"
            StorageIo.Real.move(from, to)
        },
    )
}
