package app.auri.modelmgmt

import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 一次真实下载的检查（Ticket 20）。**不是单元测试**：它联网、要几分钟、要下 163 MB，所以不在 testDebugUnitTest 里（那里不允许真实网络），
 * 也就不会出现“被跳过的测试”。运行：
 *
 *     ./gradlew :modelmgmt:realDownloadCheck -PauriRealDownload=<目录>     目录放在仓库外，例如 ~/.cache/auri/models
 *
 * 它用线上同一套代码（[ModelManager] + [UrlConnectionTransport]），对清单里写的真实地址走一遍：
 * 下到约 5% 时暂停 → 确认进入“已暂停”并留下残片 → 继续（应当是带 Range 的请求，服务器回 206）→ 下完 →
 * 压缩包哈希 → 解包 → 逐个文件校验 → 原子启用；最后**独立**地（不经过本项目的校验器）重算每个文件的 SHA-256，与清单比对。
 * 退出码 0 才算通过。输出里没有地址：重定向后的地址是带签名、带有效期的临时地址，不进日志。
 * 目录里已经装好了同一版本时不会再下载（只做最后的独立校验），输出会写明“没有下载”；要重来就删掉目录里的 model-store。
 */
fun main(args: Array<String>) {
    if (args.size != 2) {
        System.err.println("用法：RealDownloadCheck <清单文件> <缓存目录>")
        exitProcess(2)
    }
    val ok = try {
        RealDownloadCheck(File(args[0]), File(args[1])).run()
    } catch (e: IllegalStateException) {
        println("不通过：${e.message}")
        false
    }
    exitProcess(if (ok) 0 else 1)
}

private class CountingInputStream(source: InputStream, private val counter: AtomicLong) : FilterInputStream(source) {
    override fun read(): Int = super.read().also { if (it >= 0) counter.incrementAndGet() }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) counter.addAndGet(it.toLong()) }
}

/** 记下每次请求有没有 Range / If-Range、服务器回了什么状态、收到多少字节；不记地址。 */
private class RecordingTransport(private val inner: HttpTransport) : HttpTransport {
    class Entry(val hasRange: Boolean, val hasIfRange: Boolean) {
        @Volatile var status = 0
        val bytes = AtomicLong()
    }

    val entries = CopyOnWriteArrayList<Entry>()

    override fun get(url: String, headers: Map<String, String>): TransportResponse {
        val entry = Entry(
            hasRange = headers.keys.any { it.equals("Range", ignoreCase = true) },
            hasIfRange = headers.keys.any { it.equals("If-Range", ignoreCase = true) },
        )
        entries += entry
        val response = inner.get(url, headers)
        entry.status = response.status
        return object : TransportResponse by response {
            private val counted by lazy { CountingInputStream(response.body, entry.bytes) }
            override val body: InputStream get() = counted
        }
    }
}

private class RealDownloadCheck(manifestFile: File, private val root: File) {
    private val manifestJson = manifestFile.readText()
    private val catalog = ModelCatalog.fromJson(listOf(manifestJson))
    private val info = catalog.default.info
    private val id = catalog.defaultModelId
    private val transport = RecordingTransport(UrlConnectionTransport())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = System.nanoTime()

    private val manager = ModelManager(catalog, root.apply { mkdirs() }, transport, scope)

    private fun seconds() = (System.nanoTime() - started) / 1_000_000_000

    private fun transfer(): Transfer = manager.state.value.entry(id).transfer

    private fun ready(): Boolean = manager.active.value is ActiveModelStatus.Ready && manager.state.value.entry(id).isInstalled

    private fun fail(message: String): Nothing = throw IllegalStateException(message)

    private fun waitFor(what: String, timeoutSeconds: Long, condition: () -> Boolean) {
        var lastReport = 0L
        while (!condition()) {
            (transfer() as? Transfer.Failed)?.let { fail("$what 的途中下载失败：${it.error.kind}（http=${it.error.httpStatus}，已下 ${it.bytesDone} 字节）") }
            if (seconds() > timeoutSeconds) fail("等不到：$what（超过 $timeoutSeconds 秒）")
            if (seconds() - lastReport >= 15) {
                lastReport = seconds()
                println("  [${seconds()} 秒] ${describe(transfer())}")
            }
            Thread.sleep(200)
        }
    }

    private fun describe(t: Transfer) = when (t) {
        Transfer.Idle -> "空闲"
        is Transfer.Downloading -> "下载中 ${t.bytesDone / 1_000_000} / ${t.bytesTotal / 1_000_000} MB"
        is Transfer.Paused -> "已暂停 ${t.bytesDone / 1_000_000} / ${t.bytesTotal / 1_000_000} MB"
        Transfer.Installing -> "校验并安装中"
        is Transfer.Failed -> "失败 ${t.error.kind}"
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun run(): Boolean {
        println("模型 ${info.modelId} ${info.modelVersion}：下载 ${info.archiveSizeBytes} 字节，解包后 ${info.unpackedSizeBytes} 字节，所需空间 ${info.spaceNeededBytes} 字节")
        manager.restore()
        var pausedAt: Long? = null
        val alreadyInstalled = ready()
        if (alreadyInstalled) {
            println("目录里已经装好了这个版本：没有下载，只做最后的独立校验")
        } else {
            manager.start(id)
            println("[${seconds()} 秒] 已开始，等下载到 5% 就暂停")
            waitFor("下载到 5%", 3600) { (transfer() as? Transfer.Downloading)?.let { it.bytesDone >= info.archiveSizeBytes / 20 } == true }
            manager.pause(id)
            waitFor("进入已暂停", 60) { transfer() is Transfer.Paused }
            pausedAt = (transfer() as Transfer.Paused).bytesDone
            if (pausedAt <= 0 || pausedAt >= info.archiveSizeBytes) fail("暂停时已下载的字节数不合理：$pausedAt")
            println("[${seconds()} 秒] 暂停于 ${pausedAt / 1_000_000} MB，立刻继续")
            manager.start(id)
            waitFor("下完并启用", 3600) { ready() }
        }

        val active = (manager.active.value as? ActiveModelStatus.Ready)?.model ?: fail("没有启用的模型")
        println("[${seconds()} 秒] 已启用，独立重算每个文件的 SHA-256")
        for (file in info.files) {
            val onDisk = File(active.dir, file.path)
            if (!onDisk.isFile) fail("缺少文件 ${file.path}")
            if (onDisk.length() != file.sizeBytes) fail("${file.path} 的大小是 ${onDisk.length()}，清单要求 ${file.sizeBytes}")
            val actual = sha256(onDisk)
            if (actual != file.sha256) fail("${file.path} 的 SHA-256 与清单不符")
            println("  ${file.path}  ${file.sizeBytes} 字节  SHA-256 与清单一致")
        }
        val leftovers = listOf(File(root, "model-staging")).filter { it.exists() && !it.listFiles().isNullOrEmpty() }
        if (leftovers.isNotEmpty()) fail("启用之后暂存目录里还有东西：${leftovers.map { it.name }}")

        if (!alreadyInstalled) {
            val requests = transport.entries.toList()
            val received = requests.sumOf { it.bytes.get() }
            println("网络请求 ${requests.size} 次：")
            requests.forEachIndexed { i, r ->
                println("  #${i + 1}  Range=${if (r.hasRange) "有" else "无"}  If-Range=${if (r.hasIfRange) "有" else "无"}  状态 ${r.status}  收到 ${r.bytes.get()} 字节")
            }
            if (requests.size < 2) fail("暂停后继续应当是第二次请求")
            if (!requests[1].hasRange || requests[1].status != 206) fail("继续下载应当是带 Range 的请求，服务器回 206（实际 Range=${requests[1].hasRange}，状态 ${requests[1].status}）")
            println("压缩包 ${info.archiveSizeBytes} 字节；网络上实际收到 $received 字节（多出来的 ${received - info.archiveSizeBytes} 字节是续传时重复收到的，没有从头再下）")
            if (received - info.archiveSizeBytes > info.archiveSizeBytes / 10) fail("续传重复收到的字节太多：说明没有从断点接着下")
        }
        println("用时 ${seconds()} 秒${pausedAt?.let { "；暂停点 $it 字节" } ?: ""}")
        println("结果：通过")
        scope.cancel()
        return true
    }
}
