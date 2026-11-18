package app.auri.modelmgmt

import app.auri.asr.manifest.ManifestArchive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 可暂停、可续传的下载：只负责把清单里的压缩包的字节拿到本机一个文件里，不判断这些字节对不对（那是 [ModelManager] 用清单里固定的哈希做的）。
 *
 * 续传靠两样东西，都在磁盘上（进程被杀、手机重启之后照样能续）：
 *  - `archive.part`：已经收到的字节，只追加。续传的起点就是它的长度。
 *  - `download.json`：这份残片的“身份”——清单里的压缩包大小与哈希，加上服务器给过的强校验器（ETag，没有就用 Last-Modified）。
 * 续传请求带 `Range: bytes=<长度>-` 与 `If-Range: <校验器>`；服务器若回整包（文件换了或不支持 Range），或回 206 但响应里的校验器不同
 * （有的服务器不理 If-Range），残片作废、从头下载。没有校验器就无法证明残片与服务器上的是同一份文件，所以也从头下载，不拿残片冒险。
 *
 * 暂停就是取消协程。读网络的调用（建立连接、读正文、关闭连接）可能阻塞很久，而且在 JDK 上 `disconnect()` 自己也会一直等到进行中的读返回
 * （实测：服务器卡住 19 秒，disconnect() 就阻塞 19 秒），所以它们都不在协程线程上做：每个阻塞调用交给专用线程，协程这边只等“交接”，取消时立刻返回。
 * 被丢下的线程在连接被断开（Android 上 HttpURLConnection 由 OkHttp 实现，disconnect() 立刻取消）或读超时（30 秒）后自己结束；
 * 它从不碰残片文件，所以暂停之后不会再有字节写进去，已经写进文件的字节也不会因为暂停、断线、报错而丢掉（除非它们已被判定为不可能属于这份文件）。
 */
class ResumableDownloader(
    private val transport: HttpTransport,
    private val openSink: (File) -> OutputStream = { FileOutputStream(it, /* append = */ true) },
) {
    /**
     * 下载（或接着下载）到 [workDir] 里的 [PART_FILE]，返回这个文件。返回时它的长度一定等于清单声明的大小；
     * 内容对不对由调用方用哈希核对。失败一律抛 [ModelException]；取消抛 CancellationException，残片保留。
     * [onProgress] 收到的是文件里已有的总字节数（含续传前就有的），在 IO 线程上调用。
     */
    suspend fun download(archive: ManifestArchive, workDir: File, onProgress: (Long) -> Unit = {}): File = withContext(Dispatchers.IO) {
        try {
            run(archive, workDir, onProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelException) {
            throw e
        } catch (e: TransportException) {
            throw ModelException(ModelError(ModelErrorKind.SERVER_ERROR))
        } catch (e: IOException) {
            // 读响应头之前、读正文时的 IO 失败都在里面分过类了；留到这里的只有本机文件操作
            throw ModelException(storageError(e))
        }
    }

    /** 现在续传能从哪个字节开始；残片不可信（没有状态、没有校验器、属于别的清单）就是 0。不修改磁盘。 */
    fun partialBytes(archive: ManifestArchive, workDir: File): Long {
        trustedState(archive, workDir) ?: return 0L
        val length = File(workDir, PART_FILE).length()
        return if (length <= archive.sizeBytes) length else 0L
    }

    /** 作废这次下载：删残片与状态。 */
    fun discard(workDir: File) {
        File(workDir, PART_FILE).delete()
        File(workDir, STATE_FILE).delete()
        File(workDir, STATE_FILE + TMP_SUFFIX).delete()
    }

    private suspend fun run(archive: ManifestArchive, workDir: File, onProgress: (Long) -> Unit): File {
        workDir.mkdirs()
        val part = File(workDir, PART_FILE)
        var state = trustedState(archive, workDir)
        if (state == null || part.length() > archive.sizeBytes) {
            discard(workDir)
            state = null
        }
        var restarts = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val offset = if (state != null) part.length() else 0L
            if (offset == archive.sizeBytes) return part
            when (fetch(archive, workDir, part, state, offset, onProgress)) {
                Fetch.DONE -> return part
                Fetch.RESTART -> {
                    if (++restarts > MAX_RESTARTS) throw ModelException(ModelError(ModelErrorKind.SERVER_ERROR))
                    discard(workDir)
                    state = null
                }
            }
        }
    }

    private enum class Fetch { DONE, RESTART }

    private suspend fun fetch(
        archive: ManifestArchive,
        workDir: File,
        part: File,
        state: TransferState?,
        offset: Long,
        onProgress: (Long) -> Unit,
    ): Fetch {
        val validator = state?.validator
        val headers = buildMap {
            if (offset > 0 && validator != null) {
                put("Range", "bytes=$offset-")
                put("If-Range", validator.value)
            }
        }
        val response = try {
            openResponse(archive.url, headers)
        } catch (e: IOException) {
            // 读到响应头之前的任何 IO 失败：连不上、DNS、超时、TLS。区分它们没有价值：用户能做的都是“检查网络后重试”
            currentCoroutineContext().ensureActive()
            throw ModelException(ModelError(ModelErrorKind.NO_NETWORK))
        }
        val pump = Pump(response)
        try {
            return handle(archive, workDir, part, state, offset, pump, onProgress)
        } finally {
            pump.release()
        }
    }

    /**
     * 在专用线程里建立连接并等响应头；协程被取消（暂停）时立刻返回，不等网络。
     * 取消之后才到的响应由那条线程自己断开并关闭，不留连接。
     */
    private suspend fun openResponse(url: String, headers: Map<String, String>): TransportResponse =
        suspendCancellableCoroutine { continuation ->
            Thread(
                {
                    try {
                        val response = transport.get(url, headers)
                        continuation.resume(response) { discardLate(response) }
                    } catch (e: Throwable) {
                        continuation.resumeWithException(e)
                    }
                },
                "model-download-connect",
            ).apply { isDaemon = true }.start()
        }

    private fun discardLate(response: TransportResponse) {
        runCatching { response.abort() }
        runCatching { response.close() }
    }

    private suspend fun handle(
        archive: ManifestArchive,
        workDir: File,
        part: File,
        state: TransferState?,
        offset: Long,
        pump: Pump,
        onProgress: (Long) -> Unit,
    ): Fetch {
        val response = pump.response
        when (response.status) {
            200 -> {
                val declared = response.header("Content-Length")?.trim()?.toLongOrNull()
                if (declared != null && declared != archive.sizeBytes) {
                    discard(workDir)
                    throw ModelException(ModelError(ModelErrorKind.HASH_MISMATCH))
                }
                // 整包从第 0 字节开始：对续传请求回 200 说明服务器不支持 Range 或文件换了，残片不再有用
                if (offset > 0) part.delete()
                saveState(workDir, TransferState(sha256 = archive.sha256, size = archive.sizeBytes, validator = validatorOf(response)))
                return copyBody(archive, workDir, part, startAt = 0L, pump, onProgress)
            }

            206 -> {
                val validator = state?.validator
                if (offset == 0L || validator == null) throw ModelException(ModelError(ModelErrorKind.SERVER_ERROR, httpStatus = 206))
                val range = CONTENT_RANGE.matchEntire(response.header("Content-Range").orEmpty().trim())
                val sameRange = range != null && range.groupValues[1].toLong() == offset &&
                    range.groupValues[2].toLong() == archive.sizeBytes - 1 && range.groupValues[3].toLong() == archive.sizeBytes
                // 服务器可能不理 If-Range：以响应里的校验器为准，不同就是另一份文件
                if (!sameRange || response.header(validator.header)?.trim() != validator.value) return Fetch.RESTART
                return copyBody(archive, workDir, part, startAt = offset, pump, onProgress)
            }

            416 -> {
                if (offset == 0L) throw ModelException(ModelError(ModelErrorKind.SERVER_ERROR, httpStatus = 416))
                return Fetch.RESTART
            }

            else -> throw ModelException(ModelError(ModelErrorKind.SERVER_ERROR, httpStatus = response.status))
        }
    }

    private suspend fun copyBody(
        archive: ManifestArchive,
        workDir: File,
        part: File,
        startAt: Long,
        pump: Pump,
        onProgress: (Long) -> Unit,
    ): Fetch {
        val sink = try {
            openSink(part)
        } catch (e: IOException) {
            throw ModelException(storageError(e))
        }
        pump.start()
        var total = startAt
        sink.use {
            while (true) {
                when (val piece = pump.next()) {
                    Piece.End -> break
                    is Piece.Failure -> {
                        // 暂停时 abort 会让读取以 IOException 失败：此时它是取消，不是网络故障
                        currentCoroutineContext().ensureActive()
                        throw if (piece.error is IOException) ModelException(ModelError(ModelErrorKind.NO_NETWORK)) else piece.error
                    }
                    is Piece.Data -> {
                        if (total + piece.bytes.size > archive.sizeBytes) {
                            // 比清单声明的还多：这不是清单里的那个文件。不能留作残片，否则下次续传会接着它
                            sink.close()
                            discard(workDir)
                            throw ModelException(ModelError(ModelErrorKind.HASH_MISMATCH))
                        }
                        try {
                            sink.write(piece.bytes)
                        } catch (e: IOException) {
                            throw ModelException(storageError(e))
                        }
                        total += piece.bytes.size
                        onProgress(total)
                    }
                }
            }
        }
        // 连接被对方提前掐断时，JDK 的 HttpURLConnection 不一定抛异常，只是读到“结束”：以收到的字节数为准
        if (total < archive.sizeBytes) throw ModelException(ModelError(ModelErrorKind.NO_NETWORK))
        return Fetch.DONE
    }

    private sealed interface Piece {
        class Data(val bytes: ByteArray) : Piece

        data object End : Piece

        class Failure(val error: Throwable) : Piece
    }

    /**
     * 一个响应的正文由专用线程读：阻塞的 read、close、abort 都不在协程线程上做（JDK 的 disconnect() 会一直等到进行中的读返回）。
     * 协程这边只从有界队列里取块、写文件；读线程从不碰文件。取消时 [next] 被中断，立刻返回。
     */
    private class Pump(val response: TransportResponse) {
        private val queue = ArrayBlockingQueue<Piece>(QUEUE_CHUNKS)

        @Volatile private var started = false

        @Volatile private var stopped = false

        @Volatile private var finished = false

        fun start() {
            started = true
            Thread(this::read, "model-download-read").apply { isDaemon = true }.start()
        }

        private fun read() {
            try {
                val input = response.body
                val buffer = ByteArray(CHUNK_BYTES)
                while (!stopped) {
                    val n = input.read(buffer)
                    if (n < 0) {
                        offer(Piece.End)
                        return
                    }
                    offer(Piece.Data(buffer.copyOf(n)))
                }
            } catch (e: Throwable) {
                offer(Piece.Failure(e))
            } finally {
                finished = true
                runCatching { response.close() }
            }
        }

        private fun offer(piece: Piece) {
            while (!stopped && !queue.offer(piece, 100, TimeUnit.MILLISECONDS)) {
                // 消费者还没取走：等。消费者走了（stopped）就丢弃
            }
        }

        suspend fun next(): Piece = runInterruptible(Dispatchers.IO) { queue.take() }

        /**
     * 没开始读就直接关响应；开始了就叫读线程停下，并在另一条线程上断开连接。
     * 断开多快由平台决定（JDK 上 abort 要等进行中的读返回，最长一个读超时），所以读线程是被放弃的：
     * 它最多再多读一块、丢掉，随后自己关响应；暂停的生效不取决于它。
     */
        fun release() {
            stopped = true
            if (!started) {
                runCatching { response.close() }
                return
            }
            if (!finished) Thread({ runCatching { response.abort() } }, "model-download-abort").apply { isDaemon = true }.start()
        }
    }

    private fun validatorOf(response: TransportResponse): Validator? {
        response.header("ETag")?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("W/") }?.let { return Validator("ETag", it) }
        response.header("Last-Modified")?.trim()?.takeIf { it.isNotEmpty() }?.let { return Validator("Last-Modified", it) }
        return null
    }

    /** 只信“身份与这份清单一致、并且有校验器”的状态；损坏、缺失、属于别的清单、没有校验器都当作没有。 */
    private fun trustedState(archive: ManifestArchive, workDir: File): TransferState? {
        val file = File(workDir, STATE_FILE)
        val state = try {
            if (file.isFile) json.decodeFromString<TransferState>(file.readText()) else null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: IOException) {
            null
        }
        return state?.takeIf { it.version == 1 && it.sha256 == archive.sha256 && it.size == archive.sizeBytes && it.validator != null }
    }

    /** 先写临时文件再改名：状态文件要么是旧的完整版本，要么是新的完整版本，不会读到半个。 */
    private fun saveState(workDir: File, state: TransferState) {
        val file = File(workDir, STATE_FILE)
        if (state.validator == null) {
            file.delete() // 没有校验器就没有可续传的身份；下次从头下载
            return
        }
        val temp = File(workDir, STATE_FILE + TMP_SUFFIX)
        temp.writeText(json.encodeToString(TransferState.serializer(), state))
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    @Serializable
    private data class Validator(val header: String, val value: String)

    @Serializable
    private data class TransferState(
        @SerialName("v") val version: Int = 1,
        @SerialName("archive_sha256") val sha256: String,
        @SerialName("archive_size") val size: Long,
        val validator: Validator? = null,
    )

    companion object {
        internal const val PART_FILE = "archive.part"
        internal const val STATE_FILE = "download.json"
        private const val TMP_SUFFIX = ".tmp"
        private const val CHUNK_BYTES = 64 * 1024
        private const val QUEUE_CHUNKS = 4
        private const val MAX_RESTARTS = 2
        private val CONTENT_RANGE = Regex("^bytes (\\d+)-(\\d+)/(\\d+)$")
        private val json = Json { ignoreUnknownKeys = false }

        /** 写盘失败：系统报空间已满是一类（用户能腾空间），其余是存储错误。 */
        internal fun storageError(e: IOException): ModelError {
            val text = e.message.orEmpty()
            val noSpace = "ENOSPC" in text || "No space left" in text
            return ModelError(if (noSpace) ModelErrorKind.NOT_ENOUGH_SPACE else ModelErrorKind.STORAGE_ERROR)
        }
    }
}
