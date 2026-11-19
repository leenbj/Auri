package app.auri.modelmgmt

import app.auri.asr.manifest.ManifestArchive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Ticket 20 第 2 项（下载部分）：暂停 / 续传（HTTP Range、从字节偏移继续、用服务器校验器核对残片身份）、
 * 进程重启后还能续、清晰的错误状态。全部对着本机假服务器，不碰真实网络。
 */
class ResumableDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val size = 300_000
    private val content = ByteArray(size) { ((it * 131) xor (it shr 7)).toByte() }
    private val server = FakeModelServer(content)
    private lateinit var work: File

    @Before fun makeWorkDir() {
        work = tmp.newFolder("work")
    }

    @After fun stopServer() = server.close()

    private fun transport() = UrlConnectionTransport(policy = UrlPolicy.LOOPBACK_CLEARTEXT_FOR_TESTS, readTimeoutMs = 60_000)

    /** 每次新建一个下载器：它没有内存里的状态，续传靠的只能是磁盘上的东西（等于进程重启）。 */
    private fun downloader(openSink: (File) -> OutputStream = { FileOutputStream(it, true) }) = ResumableDownloader(transport(), openSink)

    private fun archive(bytes: ByteArray = content, url: String = server.url) = ManifestArchive(url, bytes.size.toLong(), sha256Hex(bytes))

    private fun part() = File(work, ResumableDownloader.PART_FILE)

    private fun failureOf(block: suspend () -> Unit): ModelError = runBlocking {
        try {
            block()
            fail("应当以 ModelException 失败")
            error("unreachable")
        } catch (e: ModelException) {
            e.error
        }
    }

    /** 下到 [KEPT] 字节时断线，留下一份残片。 */
    private fun leavePartialByDroppingConnection() {
        server.dropAfterBytes = KEPT
        assertEquals(ModelErrorKind.NO_NETWORK, failureOf { downloader().download(archive(), work) }.kind)
        assertEquals(KEPT, part().length())
        server.dropAfterBytes = null
    }

    private fun awaitTrue(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            check(System.nanoTime() < deadline) { "等了 ${timeoutMs}ms 条件仍不成立" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `从零下载完整文件：进度单调上升直到总量，第一个请求没有 Range`() = runBlocking {
        val progress = CopyOnWriteArrayList<Long>()

        val file = downloader().download(archive(), work) { progress += it }

        assertArrayEquals(content, file.readBytes())
        assertEquals(size.toLong(), progress.last())
        assertEquals(progress.sorted(), progress.toList())
        assertNull(server.requests.single().range)
        assertNull(server.requests.single().ifRange)
    }

    @Test
    fun `暂停立刻生效，随后从已有字节续传：Range 的起点就是残片长度，并带着校验器`() = runBlocking {
        server.stallAfterBytes = KEPT
        val progress = AtomicLong()
        val running = launch(Dispatchers.IO) { downloader().download(archive(), work) { progress.set(it) } }
        awaitTrue { progress.get() >= KEPT }

        val pausedAt = System.nanoTime()
        running.cancelAndJoin() // 暂停 = 取消协程；服务器此刻一个字节也不给，读超时设的是 60 秒
        val pauseMs = (System.nanoTime() - pausedAt) / 1_000_000
        assertTrue("暂停必须靠主动断开连接立刻生效，而不是等读超时：用了 $pauseMs ms", pauseMs < 5_000)
        assertEquals("已经写进文件的字节不能因为暂停丢掉", KEPT, part().length())
        server.stallAfterBytes = null
        server.releaseStall()

        val file = downloader().download(archive(), work) // 新下载器：状态只在磁盘上

        assertEquals(listOf(null, "bytes=$KEPT-"), server.requests.map { it.range })
        assertEquals("续传请求要带着服务器给过的校验器，服务器才能在文件变了时拒绝", "\"v1\"", server.requests[1].ifRange)
        assertArrayEquals(content, file.readBytes())
    }

    @Test
    fun `中途断线报没有网络，已下载的部分保留，网络回来后接着下载`() {
        leavePartialByDroppingConnection()

        val file = runBlocking { downloader().download(archive(), work) }

        assertEquals(listOf(null, "bytes=$KEPT-"), server.requests.map { it.range })
        assertArrayEquals(content, file.readBytes())
    }

    @Test
    fun `服务器上的文件换了（校验器变了）：服务器回整包，残片作废，从头覆盖`() {
        leavePartialByDroppingConnection()
        val replacement = ByteArray(size) { (it % 251).toByte() } // 大小与清单相同，字节不同
        server.content = replacement
        server.etag = "\"v2\""

        // 哈希是调用方（管理器）在下载完成后用清单核对的；这里只要求“残片没有被拼在新文件前面”
        val file = runBlocking { downloader().download(archive(), work) }

        assertEquals("bytes=$KEPT-", server.requests[1].range)
        assertEquals("\"v1\"", server.requests[1].ifRange)
        assertArrayEquals("残片不能留在新文件前面", replacement, file.readBytes())
    }

    @Test
    fun `服务器不理会 If-Range 而给了带新校验器的 206：客户端自己比对，发现不同就从头下载`() {
        leavePartialByDroppingConnection()
        val replacement = ByteArray(size) { (it % 251).toByte() }
        server.content = replacement
        server.etag = "\"v2\""
        server.ignoreIfRange = true

        val file = runBlocking { downloader().download(archive(), work) }

        assertEquals(listOf(null, "bytes=$KEPT-", null), server.requests.map { it.range })
        assertArrayEquals(replacement, file.readBytes())
    }

    @Test
    fun `服务器不支持 Range（对续传请求回 200 整包）：从头覆盖，不把整包拼在残片后面`() {
        leavePartialByDroppingConnection()
        server.supportRange = false

        val file = runBlocking { downloader().download(archive(), work) }

        assertEquals(size.toLong(), file.length())
        assertArrayEquals(content, file.readBytes())
    }

    @Test
    fun `服务器对续传回 416：残片作废，从头下载一次`() {
        leavePartialByDroppingConnection()
        server.nextRangeStatus = 416

        val file = runBlocking { downloader().download(archive(), work) }

        assertEquals(listOf(null, "bytes=$KEPT-", null), server.requests.map { it.range })
        assertArrayEquals(content, file.readBytes())
    }

    @Test
    fun `服务器没给任何校验器：无法证明残片是同一份文件，续传时从头下载`() {
        server.etag = null
        server.lastModified = null
        leavePartialByDroppingConnection()

        val file = runBlocking { downloader().download(archive(), work) }

        assertEquals("没有校验器就不拿残片冒险续传", listOf(null, null), server.requests.map { it.range })
        assertNull(server.requests[1].ifRange)
        assertArrayEquals(content, file.readBytes())
    }

    @Test
    fun `只有 Last-Modified 也算校验器，If-Range 用它`() {
        server.etag = null
        server.lastModified = "Wed, 21 Oct 2026 07:28:00 GMT"
        leavePartialByDroppingConnection()

        val file = runBlocking { downloader().download(archive(), work) }

        assertEquals(listOf(null, "bytes=$KEPT-"), server.requests.map { it.range })
        assertEquals("Wed, 21 Oct 2026 07:28:00 GMT", server.requests[1].ifRange)
        assertArrayEquals(content, file.readBytes())
    }

    @Test
    fun `残片已经完整时不再请求网络`() {
        runBlocking { downloader().download(archive(), work) }
        server.requests.clear()

        val file = runBlocking { downloader().download(archive(), work) }

        assertEquals(0, server.requests.size)
        assertArrayEquals(content, file.readBytes())
    }

    @Test
    fun `残片比清单声明的还长：不可能是它的前半截，从头下载`() {
        leavePartialByDroppingConnection()
        part().appendBytes(ByteArray(size)) // 现在长度是 KEPT + size，超过清单大小

        val file = runBlocking { downloader().download(archive(), work) }

        // 第一个请求是造残片的那次；第二个（唯一的下载请求）不能带 Range：带着不可能的残片去续传没有意义
        assertEquals(listOf(null, null), server.requests.map { it.range })
        assertArrayEquals(content, file.readBytes())
    }

    @Test
    fun `状态文件损坏：当作没有残片，从头下载`() {
        leavePartialByDroppingConnection()
        File(work, ResumableDownloader.STATE_FILE).writeText("{ 这不是 JSON")

        val file = runBlocking { downloader().download(archive(), work) }

        assertEquals(listOf(null, null), server.requests.map { it.range })
        assertArrayEquals(content, file.readBytes())
    }

    @Test
    fun `残片属于另一份清单（大小或哈希不同）：丢掉，从头下载`() {
        leavePartialByDroppingConnection()
        val other = archive().copy(sha256 = "0".repeat(64))

        runBlocking { downloader().download(other, work) }

        assertEquals("换了清单就不能沿用旧残片", listOf(null, null), server.requests.map { it.range })
    }

    @Test
    fun `服务器给错误状态：报服务器错误并带状态码，残片原样保留`() {
        leavePartialByDroppingConnection()
        server.statusOverride = 503

        val error = failureOf { downloader().download(archive(), work) }

        assertEquals(ModelError(ModelErrorKind.SERVER_ERROR, httpStatus = 503), error)
        assertEquals("服务器暂时出错不该丢掉用户已经下载的部分", KEPT, part().length())
    }

    @Test
    fun `连接被拒绝：报没有网络`() {
        val deadUrl = FakeModelServer(content).use { it.url } // 端口随即关闭

        val error = failureOf { downloader().download(archive(url = deadUrl), work) }

        assertEquals(ModelErrorKind.NO_NETWORK, error.kind)
    }

    @Test
    fun `服务器声明的长度与清单不符：不下载正文，报哈希不符`() {
        server.content = ByteArray(size + 1000)

        val error = failureOf { downloader().download(archive(), work) }

        assertEquals(ModelErrorKind.HASH_MISMATCH, error.kind)
        assertFalse("长度不对就不要往盘上写", part().exists() && part().length() > 0)
    }

    @Test
    fun `不声明长度的响应超过清单大小：最多写清单大小那么多，随即作废`() {
        server.content = ByteArray(size + 1000) { 7 }
        server.chunked = true

        val error = failureOf { downloader().download(archive(), work) }

        assertEquals(ModelErrorKind.HASH_MISMATCH, error.kind)
        assertFalse("超长的内容不能留作残片，否则下次续传会接着它", part().exists())
        assertFalse(File(work, ResumableDownloader.STATE_FILE).exists())
    }

    @Test
    fun `写盘时系统报空间已满：报空间不足，已写的部分保留`() {
        val error = failureOf { downloader(openSink = { FailingSink(it, failAfterBytes = 50_000, IOException("No space left on device")) }).download(archive(), work) }

        assertEquals(ModelErrorKind.NOT_ENOUGH_SPACE, error.kind)
        assertEquals(50_000L, part().length())
    }

    @Test
    fun `写盘的其他失败报存储错误，不是空间不足`() {
        val error = failureOf { downloader(openSink = { FailingSink(it, failAfterBytes = 10_000, IOException("Input/output error")) }).download(archive(), work) }

        assertEquals(ModelErrorKind.STORAGE_ERROR, error.kind)
    }

    @Test
    fun `能续传的字节数：有残片与校验器才算，没有校验器、换了清单、没有状态都是零`() {
        assertEquals(0L, downloader().partialBytes(archive(), work))

        leavePartialByDroppingConnection()
        assertEquals(KEPT, downloader().partialBytes(archive(), work))
        assertEquals("清单变了，残片不属于它", 0L, downloader().partialBytes(archive().copy(sha256 = "1".repeat(64)), work))

        File(work, ResumableDownloader.STATE_FILE).delete()
        assertEquals("没有状态就无从证明残片的身份", 0L, downloader().partialBytes(archive(), work))
    }

    @Test
    fun `丢弃下载：残片与状态都删掉`() {
        leavePartialByDroppingConnection()

        downloader().discard(work)

        assertFalse(part().exists())
        assertFalse(File(work, ResumableDownloader.STATE_FILE).exists())
        assertEquals(0L, downloader().partialBytes(archive(), work))
    }

    private class FailingSink(file: File, private val failAfterBytes: Long, private val failure: IOException) : OutputStream() {
        private val out = FileOutputStream(file, true)
        private var written = 0L

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            val allowed = (failAfterBytes - written).coerceAtLeast(0).toInt()
            if (len > allowed) {
                out.write(b, off, allowed)
                written += allowed
                throw failure
            }
            out.write(b, off, len)
            written += len
        }

        override fun close() = out.close()
    }

    private companion object {
        const val KEPT = 100_000L
    }
}
