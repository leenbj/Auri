package app.auri.modelmgmt

import app.auri.asr.manifest.ModelVerifier
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Ticket 20 第 2 项：下载 / 暂停 / 续传 / 重启存活；启用之前先校验；原子启用；半截、失败、损坏的模型永远不会被加载；
 * 失败的更新不动旧模型；开始前检查可用空间；清晰的错误状态。全部对着本机假服务器与真实的文件系统。
 */
class ModelManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val servers = mutableListOf<FakeModelServer>()

    @After fun cleanUp() {
        scope.cancel()
        servers.forEach { it.close() }
    }

    private fun server(bytes: ByteArray) = FakeModelServer(bytes).also { servers += it }

    /** 一个测试要用的全部东西：一个模型、它的服务器、一个磁盘根目录；可以多次“重启”（新建管理器，磁盘不变）。 */
    private inner class Rig(
        val model: TestModel = TestModel(tmp.newFolder(newFolderName("model"))),
        val root: File = tmp.newFolder(newFolderName("root")),
    ) {
        val server = server(model.archiveBytes)
        val urls = mutableMapOf(model.url to server.url)
        val transport = RewritingTransport(UrlConnectionTransport(UrlPolicy.LOOPBACK_CLEARTEXT_FOR_TESTS, readTimeoutMs = 60_000)) { urls }
        val steps = CopyOnWriteArrayList<InstallStep>()
        var availableSpace = Long.MAX_VALUE / 2
        val id get() = model.modelId

        fun catalog(vararg models: TestModel) = ModelCatalog.fromJson(models.map { it.manifestJson })

        fun manager(
            catalog: ModelCatalog = catalog(model),
            root: File = this.root,
            hooks: InstallHooks = InstallHooks { steps += it },
            openSink: (File) -> OutputStream = { FileOutputStream(it, true) },
        ) = ModelManager(catalog, root, transport, scope, space = SpaceProbe { availableSpace }, hooks = hooks, openSink = openSink).also { it.restore() }
    }

    private fun ModelManager.await(id: String, timeoutMs: Long = 30_000, predicate: (ModelEntryState) -> Boolean): ModelEntryState =
        try {
            runBlocking { withTimeout(timeoutMs) { state.map { it.entry(id) }.first(predicate) } }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("等了 ${timeoutMs}ms，条件始终没有满足；最后的状态：${entry(id)}", e)
        }

    private fun ModelManager.awaitInstalled(id: String) = await(id) { it.transfer == Transfer.Idle && it.isInstalled }

    private fun ModelManager.entry(id: String) = state.value.entry(id)

    private fun failedOf(entry: ModelEntryState) = (entry.transfer as Transfer.Failed).error

    private fun ready(manager: ModelManager) = (manager.active.value as ActiveModelStatus.Ready).model

    private fun File.names() = (listFiles() ?: emptyArray()).map { it.name }.sorted()

    /** 目录树里所有文件（空目录不算）：用来断言“没有残留文件”。 */
    private fun filesUnder(dir: File) = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path }.sorted().toList()

    @Test
    fun `指针无法解析时保留所有版本并报告可恢复的存储错误`() {
        val rig = Rig()
        val first = rig.manager().apply { start(rig.id); awaitInstalled(rig.id) }
        val old = ready(first)
        val pointer = File(rig.root, "model-store/${rig.id}/active.json")
        val validPointer = pointer.readText()
        pointer.writeText("{ invalid")

        val restarted = rig.manager()

        assertTrue("不能因为指针不可读而删除已安装的模型", old.dir.isDirectory)
        assertEquals(ModelErrorKind.STORAGE_ERROR, failedOf(restarted.entry(rig.id)).kind)
        pointer.writeText(validPointer)
        ModelVerifier().verify(ready(rig.manager()).manifest, old.dir)
    }

    @Test
    fun `启用后读取指针失败也不能删除刚安装的版本`() {
        val v1 = TestModel(tmp.newFolder(newFolderName("v1")), version = "1.0.0", seed = 1)
        val v2 = TestModel(tmp.newFolder(newFolderName("v2")), version = "2.0.0", seed = 2)
        val rig = Rig(model = v1)
        rig.manager().apply { start(rig.id); awaitInstalled(rig.id) }
        rig.urls[v2.url] = server(v2.archiveBytes).url
        val updating = rig.manager(catalog = rig.catalog(v2), hooks = InstallHooks { step ->
            if (step == InstallStep.POINTER_WRITTEN) File(rig.root, "model-store/${rig.id}/active.json").writeText("{ invalid")
        })

        updating.start(rig.id)
        updating.awaitInstalled(rig.id)

        val installed = ready(updating)
        assertTrue("新启用的目录必须仍在", installed.dir.isDirectory)
        ModelVerifier().verify(installed.manifest, installed.dir)
    }

    @Test
    fun `安装新版本时仍在使用的旧版本保留到使用者完成`() {
        val v1 = TestModel(tmp.newFolder(newFolderName("v1")), version = "1.0.0", seed = 1)
        val v2 = TestModel(tmp.newFolder(newFolderName("v2")), version = "2.0.0", seed = 2)
        val rig = Rig(model = v1)
        rig.manager().apply { start(rig.id); awaitInstalled(rig.id) }
        rig.urls[v2.url] = server(v2.archiveBytes).url
        val updating = rig.manager(catalog = rig.catalog(v2))
        val inUse = ready(updating)
        val firstLease = checkNotNull(inUse.acquire())
        val secondLease = checkNotNull(inUse.acquire())

        updating.start(rig.id)
        updating.awaitInstalled(rig.id)

        ModelVerifier().verify(inUse.manifest, inUse.dir)
        firstLease.close()
        assertTrue(inUse.dir.isDirectory)
        firstLease.close()
        assertTrue(inUse.dir.isDirectory)
        secondLease.close()
        assertFalse(inUse.dir.exists())
        assertEquals(null, inUse.acquire())
    }

    @Test
    fun `安装成功：先校验压缩包，再解包，再校验每个文件，最后才启用；启用目录里只有校验过的文件`() {
        val rig = Rig()
        val manager = rig.manager()

        manager.start(rig.id)
        manager.awaitInstalled(rig.id)

        // 顺序本身就是规则：任何一步都在前一步通过之后
        assertEquals(
            listOf(InstallStep.ARCHIVE_VERIFIED, InstallStep.EXTRACTED, InstallStep.FILES_VERIFIED, InstallStep.DIRECTORY_RENAMED, InstallStep.POINTER_WRITTEN),
            rig.steps.toList(),
        )
        val model = ready(manager)
        ModelVerifier().verify(model.manifest, model.dir) // 与引擎加载前用的是同一个校验器
        assertEquals(listOf("model.bin", "tokens.txt"), model.dir.names())
        assertEquals(1L, model.generation)
        assertEquals("1.0.0", manager.entry(rig.id).installedVersion)
        // 暂存目录、压缩包、下载状态都不留
        assertEquals(emptyList<String>(), filesUnder(File(rig.root, "model-staging")))
        assertEquals(emptyList<String>(), filesUnder(File(rig.root, "model-downloads")))
    }

    @Test
    fun `下载没有完成的整个过程中，active 一直是没装：半截的模型不会被加载`() {
        val rig = Rig()
        lateinit var manager: ModelManager
        val violations = CopyOnWriteArrayList<InstallStep>()
        manager = rig.manager(hooks = InstallHooks { step ->
            rig.steps += step
            if (step != InstallStep.POINTER_WRITTEN && manager.active.value !is ActiveModelStatus.NotInstalled) violations += step
        })
        rig.server.stallAfterBytes = 50_000

        manager.start(rig.id)
        manager.await(rig.id) { (it.transfer as? Transfer.Downloading)?.let { d -> d.bytesDone >= 50_000 } == true }
        assertTrue("下载到一半时不能有可加载的模型", manager.active.value is ActiveModelStatus.NotInstalled)
        rig.server.stallAfterBytes = null
        rig.server.releaseStall()
        manager.awaitInstalled(rig.id)

        assertEquals(emptyList<InstallStep>(), violations.toList())
        assertTrue(manager.active.value is ActiveModelStatus.Ready)
    }

    @Test
    fun `暂停后，重启（新的管理器、同一个磁盘）仍显示已下载的字节，不会偷偷联网，再开始就从残片继续`() {
        val rig = Rig()
        val manager = rig.manager()
        rig.server.stallAfterBytes = 60_000

        manager.start(rig.id)
        manager.await(rig.id) { (it.transfer as? Transfer.Downloading)?.let { d -> d.bytesDone >= 60_000 } == true }
        manager.pause(rig.id)
        val paused = manager.await(rig.id) { it.transfer is Transfer.Paused }.transfer as Transfer.Paused
        rig.server.stallAfterBytes = null
        rig.server.releaseStall()

        assertEquals(60_000L, paused.bytesDone)
        assertEquals(rig.model.archiveBytes.size.toLong(), paused.bytesTotal)

        val restarted = rig.manager() // 进程被杀后重新启动
        assertEquals(Transfer.Paused(60_000L, rig.model.archiveBytes.size.toLong()), restarted.entry(rig.id).transfer)
        assertEquals("重启不会自己联网：用户没有点继续", 1, rig.server.requests.size)

        restarted.start(rig.id)
        restarted.awaitInstalled(rig.id)

        assertEquals(listOf(null, "bytes=60000-"), rig.server.requests.map { it.range })
        assertTrue(restarted.active.value is ActiveModelStatus.Ready)
    }

    @Test
    fun `开始前先查可用空间：不够就不联网、不建任何下载文件，恰好够就放行`() {
        val rig = Rig()
        val manager = rig.manager()
        val needed = manager.entry(rig.id).info.spaceNeededBytes
        rig.availableSpace = needed - 1

        manager.start(rig.id)
        val failed = manager.await(rig.id) { it.transfer is Transfer.Failed }

        assertEquals(ModelError(ModelErrorKind.NOT_ENOUGH_SPACE, neededBytes = needed, availableBytes = needed - 1), failedOf(failed))
        assertEquals("空间检查必须在联网之前", 0, rig.server.requests.size)
        assertFalse(File(rig.root, "model-downloads").exists())

        rig.availableSpace = needed
        manager.start(rig.id)
        manager.awaitInstalled(rig.id)
    }

    @Test
    fun `续传时已经下载的部分不再计入所需空间`() {
        val rig = Rig()
        val manager = rig.manager()
        val needed = manager.entry(rig.id).info.spaceNeededBytes
        rig.server.dropAfterBytes = 70_000
        manager.start(rig.id)
        manager.await(rig.id) { it.transfer is Transfer.Failed }
        rig.server.dropAfterBytes = null

        rig.availableSpace = needed - 70_000 - 1
        manager.start(rig.id)
        val tooLittle = manager.await(rig.id) { (it.transfer as? Transfer.Failed)?.error?.kind == ModelErrorKind.NOT_ENOUGH_SPACE }
        assertEquals(needed - 70_000, failedOf(tooLittle).neededBytes)

        rig.availableSpace = needed - 70_000
        manager.start(rig.id)
        manager.awaitInstalled(rig.id)
    }

    @Test
    fun `下载的字节与清单的哈希不符：丢弃、不解包、不启用，之后可以干净地重试`() {
        val rig = Rig()
        val manager = rig.manager()
        rig.server.content = rig.model.tamperedArchiveBytes() // 大小相同，字节不同

        manager.start(rig.id)
        val failed = manager.await(rig.id) { it.transfer is Transfer.Failed }

        assertEquals(ModelErrorKind.HASH_MISMATCH, failedOf(failed).kind)
        assertEquals("哈希没通过就一步都不往下走", emptyList<InstallStep>(), rig.steps.toList())
        assertTrue(manager.active.value is ActiveModelStatus.NotInstalled)
        assertFalse(File(rig.root, "model-store/${rig.id}").exists())
        assertEquals("错的字节不能留作下次续传的残片", emptyList<String>(), filesUnder(File(rig.root, "model-downloads")))

        rig.server.content = rig.model.archiveBytes
        rig.server.requests.clear()
        manager.start(rig.id)
        manager.awaitInstalled(rig.id)
        assertEquals("重试从零开始，不沿用错的残片", listOf<String?>(null), rig.server.requests.map { it.range })
    }

    @Test
    fun `压缩包哈希对、解出的文件与清单不符：不启用（解包之后还有一道校验）`() {
        val bad = TestModel(tmp.newFolder(newFolderName("bad")), declaredTokensSha256 = "0".repeat(64))
        val rig = Rig(model = bad)
        val manager = rig.manager()

        manager.start(rig.id)
        val failed = manager.await(rig.id) { it.transfer is Transfer.Failed }

        assertEquals(ModelErrorKind.HASH_MISMATCH, failedOf(failed).kind)
        assertEquals(listOf(InstallStep.ARCHIVE_VERIFIED, InstallStep.EXTRACTED), rig.steps.toList())
        assertTrue(manager.active.value is ActiveModelStatus.NotInstalled)
        assertFalse(File(rig.root, "model-store/${rig.id}").exists())
        assertEquals(emptyList<String>(), filesUnder(File(rig.root, "model-staging")))
    }

    @Test
    fun `失败的更新不动旧模型：旧模型照常可用，成功的更新才换过去并清掉旧目录`() {
        val v1 = TestModel(tmp.newFolder(newFolderName("v1")), version = "1.0.0", seed = 1)
        val v2 = TestModel(tmp.newFolder(newFolderName("v2")), version = "2.0.0", seed = 2)
        val rig = Rig(model = v1)
        rig.manager().apply { start(rig.id); awaitInstalled(rig.id) }
        val v2Server = server(v2.tamperedArchiveBytes())
        rig.urls[v2.url] = v2Server.url

        val updating = rig.manager(catalog = rig.catalog(v2))
        val before = ready(updating)
        assertEquals("1.0.0", before.manifest.modelVersion)
        assertTrue("版本不同就是有更新可用", updating.entry(rig.id).updateAvailable)

        updating.start(rig.id)
        val failed = updating.await(rig.id) { it.transfer is Transfer.Failed }

        assertEquals(ModelErrorKind.HASH_MISMATCH, failedOf(failed).kind)
        val after = ready(updating)
        assertEquals("失败的更新之后，用的还是旧模型", before.dir, after.dir)
        assertEquals(before.generation, after.generation)
        ModelVerifier().verify(after.manifest, after.dir)
        assertEquals("1.0.0", updating.entry(rig.id).installedVersion)

        v2Server.content = v2.archiveBytes
        updating.start(rig.id)
        updating.awaitInstalled(rig.id)

        val now = ready(updating)
        assertEquals("2.0.0", now.manifest.modelVersion)
        assertNotEquals(after.dir, now.dir)
        assertTrue("换了模型就是新的代：调度器据此重新放行等模型的任务", now.generation > after.generation)
        ModelVerifier().verify(now.manifest, now.dir)
        assertEquals("旧版本目录已清掉", listOf(now.dir.name, "active.json").sorted(), File(rig.root, "model-store/${rig.id}").names())
    }

    @Test
    fun `更新过程中任何一步被杀掉，重启后：指针之前旧模型照常可用，指针之后新模型可用，没有半成品`() {
        val steps = listOf(
            InstallStep.ARCHIVE_VERIFIED,
            InstallStep.EXTRACTED,
            InstallStep.FILES_VERIFIED,
            InstallStep.DIRECTORY_RENAMED,
            InstallStep.POINTER_WRITTEN,
        )
        for (killedAt in steps) {
            val v1 = TestModel(tmp.newFolder(newFolderName("v1")), version = "1.0.0", seed = 1)
            val v2 = TestModel(tmp.newFolder(newFolderName("v2")), version = "2.0.0", seed = 2)
            val rig = Rig(model = v1)
            rig.manager().apply { start(rig.id); awaitInstalled(rig.id) }
            val v2Server = server(v2.archiveBytes)
            rig.urls[v2.url] = v2Server.url
            val snapshot = File(tmp.root, newFolderName("killed-at-$killedAt"))
            var taken = false
            val updating = rig.manager(
                catalog = rig.catalog(v2),
                hooks = InstallHooks { step -> if (step == killedAt) { rig.root.snapshotTo(snapshot); taken = true } },
            )
            updating.start(rig.id)
            updating.awaitInstalled(rig.id)
            assertTrue("$killedAt 的快照应当已经拍好", taken)
            val requestsBefore = v2Server.requests.size

            val restarted = rig.manager(catalog = rig.catalog(v2), root = snapshot) // 进程在那一步被杀，磁盘就是快照的样子
            val store = File(snapshot, "model-store/${rig.id}")

            if (killedAt != InstallStep.POINTER_WRITTEN) {
                val old = ready(restarted)
                assertEquals("$killedAt：指针还没换，旧模型必须照常可用", "1.0.0", old.manifest.modelVersion)
                ModelVerifier().verify(old.manifest, old.dir)
                assertEquals("$killedAt：重启后清掉没引用的半成品目录", listOf(old.dir.name, "active.json").sorted(), store.names())
                assertEquals("$killedAt：暂存目录清空", emptyList<String>(), filesUnder(File(snapshot, "model-staging")))
                // 已经下完并校验过的压缩包还在：接着做不必再联网
                restarted.start(rig.id)
                restarted.awaitInstalled(rig.id)
                assertEquals("$killedAt：完成更新不该重新下载", requestsBefore, v2Server.requests.size)
                assertEquals("2.0.0", ready(restarted).manifest.modelVersion)
            } else {
                val updated = ready(restarted)
                assertEquals("$killedAt：指针已换，新模型可用", "2.0.0", updated.manifest.modelVersion)
                ModelVerifier().verify(updated.manifest, updated.dir)
                assertEquals("$killedAt：旧目录被清掉", listOf(updated.dir.name, "active.json").sorted(), store.names())
                assertTrue(restarted.entry(rig.id).isInstalled)
                assertEquals("$killedAt：已装好的版本不留下载残余", emptyList<String>(), filesUnder(File(snapshot, "model-downloads")))
            }
        }
    }

    @Test
    fun `已安装的模型文件缺了或变短：重启后判为损坏，active 不再给出它，重新下载能修好并换新的代`() {
        val rig = Rig()
        val first = rig.manager()
        first.start(rig.id)
        first.awaitInstalled(rig.id)
        val installed = ready(first)
        File(installed.dir, "model.bin").writeBytes(rig.model.modelBytes.copyOf(1000)) // 变短

        val restarted = rig.manager()

        assertEquals(ActiveModelStatus.Damaged(rig.id), restarted.active.value)
        assertTrue(restarted.entry(rig.id).damaged)
        assertFalse(restarted.entry(rig.id).isInstalled)

        restarted.start(rig.id) // 重新下载
        restarted.awaitInstalled(rig.id)

        val repaired = ready(restarted)
        ModelVerifier().verify(repaired.manifest, repaired.dir)
        assertNotEquals("修好后不能沿用损坏的那个目录", installed.dir, repaired.dir)
        assertEquals(listOf(repaired.dir.name, "active.json").sorted(), File(rig.root, "model-store/${rig.id}").names())
    }

    @Test
    fun `大小没变的损坏重启时查不出来，加载时被校验器拒绝后上报：判为损坏，修好后换新的代`() {
        val rig = Rig()
        val manager = rig.manager()
        manager.start(rig.id)
        manager.awaitInstalled(rig.id)
        val installed = ready(manager)
        File(installed.dir, "model.bin").writeBytes(rig.model.modelBytes.copyOf().also { it[10] = (it[10] + 1).toByte() })
        assertTrue("重启时的快速检查只看文件在不在、大小对不对", rig.manager().active.value is ActiveModelStatus.Ready)

        manager.reportIntegrityFailure(installed)

        assertEquals(ActiveModelStatus.Damaged(rig.id), manager.active.value)
        manager.start(rig.id)
        manager.awaitInstalled(rig.id)
        val repaired = ready(manager)
        assertTrue("新的代，被拒绝过的旧代不会再被复用", repaired.generation > installed.generation)
        ModelVerifier().verify(repaired.manifest, repaired.dir)
    }

    @Test
    fun `引擎加载时模型刚好被换掉：对旧一代的完整性上报被忽略，新模型不会被冤枉成损坏`() {
        val v1 = TestModel(tmp.newFolder(newFolderName("v1")), version = "1.0.0", seed = 1)
        val v2 = TestModel(tmp.newFolder(newFolderName("v2")), version = "2.0.0", seed = 2)
        val rig = Rig(model = v1)
        rig.manager().apply { start(rig.id); awaitInstalled(rig.id) }
        rig.urls[v2.url] = server(v2.archiveBytes).url
        val updating = rig.manager(catalog = rig.catalog(v2))
        val old = ready(updating)
        updating.start(rig.id)
        updating.awaitInstalled(rig.id)
        val now = ready(updating)

        updating.reportIntegrityFailure(old) // 旧引擎迟到的上报

        assertEquals(ActiveModelStatus.Ready(now), updating.active.value)
        assertFalse(updating.entry(rig.id).damaged)
    }

    @Test
    fun `没有网络：报没有网络，之后服务器回来就能继续`() {
        val rig = Rig()
        val dead = FakeModelServer(rig.model.archiveBytes).use { it.url }
        rig.urls[rig.model.url] = dead
        val manager = rig.manager()

        manager.start(rig.id)
        val failed = manager.await(rig.id) { it.transfer is Transfer.Failed }

        assertEquals(ModelErrorKind.NO_NETWORK, failedOf(failed).kind)
        assertTrue(manager.active.value is ActiveModelStatus.NotInstalled)

        rig.urls[rig.model.url] = rig.server.url
        manager.start(rig.id)
        manager.awaitInstalled(rig.id)
    }

    @Test
    fun `服务器报错：带着状态码如实报告`() {
        val rig = Rig()
        rig.server.statusOverride = 503
        val manager = rig.manager()

        manager.start(rig.id)
        val failed = manager.await(rig.id) { it.transfer is Transfer.Failed }

        assertEquals(ModelError(ModelErrorKind.SERVER_ERROR, httpStatus = 503), failedOf(failed))
    }

    @Test
    fun `写盘时系统报空间已满：报空间不足，已下载的部分保留`() {
        val rig = Rig()
        val full = IOException("write failed: ENOSPC (No space left on device)")
        val manager = rig.manager(openSink = { file ->
            object : OutputStream() {
                private val out = FileOutputStream(file, true)
                private var written = 0

                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

                override fun write(b: ByteArray, off: Int, len: Int) {
                    // 像真的磁盘写满：能写下的那一部分写下去，剩下的才失败
                    val room = 30_000 - written
                    if (len > room) {
                        out.write(b, off, room)
                        written += room
                        throw full
                    }
                    out.write(b, off, len)
                    written += len
                }

                override fun close() = out.close()
            }
        })

        manager.start(rig.id)
        val failed = manager.await(rig.id) { it.transfer is Transfer.Failed }

        assertEquals(ModelErrorKind.NOT_ENOUGH_SPACE, failedOf(failed).kind)
        assertEquals("已经写下的字节仍然算数，用户腾出空间后从这里继续", 30_000L, (failed.transfer as Transfer.Failed).bytesDone)
    }

    @Test
    fun `取消下载：残片删掉、回到空闲；已装好的旧模型不受影响`() {
        val v1 = TestModel(tmp.newFolder(newFolderName("v1")), version = "1.0.0", seed = 1)
        val v2 = TestModel(tmp.newFolder(newFolderName("v2")), version = "2.0.0", seed = 2)
        val rig = Rig(model = v1)
        rig.manager().apply { start(rig.id); awaitInstalled(rig.id) }
        val v2Server = server(v2.archiveBytes).also { it.stallAfterBytes = 40_000 }
        rig.urls[v2.url] = v2Server.url
        val updating = rig.manager(catalog = rig.catalog(v2))
        val before = ready(updating)

        updating.start(rig.id)
        updating.await(rig.id) { (it.transfer as? Transfer.Downloading)?.let { d -> d.bytesDone >= 40_000 } == true }
        updating.cancel(rig.id)
        updating.await(rig.id) { it.transfer == Transfer.Idle }

        assertEquals(emptyList<String>(), filesUnder(File(rig.root, "model-downloads")))
        assertEquals(before.dir, ready(updating).dir)
        v2Server.releaseStall()
    }

    @Test
    fun `同一个模型连点三次开始，只会有一个传输`() {
        val rig = Rig()
        rig.server.stallAfterBytes = 20_000
        val manager = rig.manager()

        repeat(3) { manager.start(rig.id) }
        rig.server.awaitRequests(1)
        Thread.sleep(300)

        assertEquals(1, rig.server.requests.size)
        rig.server.stallAfterBytes = null
        rig.server.releaseStall()
        manager.awaitInstalled(rig.id)
        assertEquals(1, rig.server.requests.size)
    }

    /**
     * 把“旧传输被暂停或取消之后、还没收完尾”的那一刻留住：第一个被打开的残片写入流在被关闭时卡住，直到 [letFirstClose]。
     * 同时数着任何时刻最多有几个写入流开着（一个残片不许有两个写入者）。
     */
    private class HeldFirstSink {
        private val open = AtomicInteger()
        private val firstIsNext = AtomicBoolean(true)
        val mostOpen = AtomicInteger()
        val firstIsClosing = CountDownLatch(1)
        private val release = CountDownLatch(1)

        val openNow: Int get() = open.get()

        fun letFirstClose() = release.countDown()

        fun openSink(file: File): OutputStream {
            val isFirst = firstIsNext.getAndSet(false)
            mostOpen.accumulateAndGet(open.incrementAndGet()) { a, b -> maxOf(a, b) }
            return object : OutputStream() {
                private val out = FileOutputStream(file, true)

                override fun write(b: Int) = out.write(b)

                override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)

                override fun close() {
                    if (isFirst) {
                        firstIsClosing.countDown()
                        release.await(20, TimeUnit.SECONDS)
                    }
                    out.close()
                    open.decrementAndGet()
                }
            }
        }
    }

    @Test
    fun `暂停之后旧的传输还在收尾时又点开始：新的传输等旧的放开残片才开始，不会两个一起写，也不会脱离暂停的控制`() {
        val rig = Rig()
        val held = HeldFirstSink()
        val manager = rig.manager(openSink = held::openSink)
        rig.server.stallAfterBytes = 60_000

        manager.start(rig.id)
        manager.await(rig.id) { (it.transfer as? Transfer.Downloading)?.let { d -> d.bytesDone >= 60_000 } == true }
        manager.pause(rig.id)
        assertTrue("第一个传输应当卡在放开残片文件这一步", held.firstIsClosing.await(10, TimeUnit.SECONDS))

        manager.start(rig.id) // 旧传输还没收完尾，用户（或调用方）又点了“开始”
        Thread.sleep(500) // 给一个过早开始的新传输足够的时间去联网、去打开残片

        assertEquals("旧传输还没放开残片，新传输不能先去联网", 1, rig.server.requests.size)
        assertEquals("旧传输还没放开残片，不能已经有第二个写入者", 1, held.openNow)
        held.letFirstClose()

        // 旧传输收完尾之后新传输从残片接着下（服务器的停顿也让它卡在中途）；对它按暂停必须生效：它没有被旧传输的收尾“摘掉”
        manager.await(rig.id) { (it.transfer as? Transfer.Downloading)?.let { d -> d.bytesDone >= 120_000 } == true }
        manager.pause(rig.id)
        val paused = manager.await(rig.id) { it.transfer is Transfer.Paused }.transfer as Transfer.Paused
        assertEquals(120_000L, paused.bytesDone)

        rig.server.stallAfterBytes = null
        rig.server.releaseStall()
        manager.start(rig.id)
        manager.awaitInstalled(rig.id)

        assertEquals(listOf(null, "bytes=60000-", "bytes=120000-"), rig.server.requests.map { it.range })
        assertEquals("任何时候一个残片都只有一个写入者", 1, held.mostOpen.get())
        assertTrue(manager.active.value is ActiveModelStatus.Ready)
    }

    @Test
    fun `取消之后旧的传输还在收尾时又点开始：旧的先把放弃的残片清掉，新的传输从头开始，不接着一个已经放弃的残片`() {
        val rig = Rig()
        val held = HeldFirstSink()
        val manager = rig.manager(openSink = held::openSink)
        rig.server.stallAfterBytes = 60_000

        manager.start(rig.id)
        manager.await(rig.id) { (it.transfer as? Transfer.Downloading)?.let { d -> d.bytesDone >= 60_000 } == true }
        manager.cancel(rig.id)
        assertTrue("第一个传输应当卡在放开残片文件这一步", held.firstIsClosing.await(10, TimeUnit.SECONDS))

        manager.start(rig.id) // 取消还没收完尾就又点了“开始”
        Thread.sleep(500)
        assertEquals("旧传输还没放开残片，新传输不能先去联网", 1, rig.server.requests.size)
        held.letFirstClose()

        // 新传输是从头开始的（取消就是放弃那个残片）：服务器收到的第二个请求没有 Range
        rig.server.awaitRequests(2)
        assertEquals(listOf(null, null), rig.server.requests.map { it.range })

        rig.server.stallAfterBytes = null
        rig.server.releaseStall()
        manager.awaitInstalled(rig.id)
        assertEquals("任何时候一个残片都只有一个写入者", 1, held.mostOpen.get())
    }

    @Test
    fun `已经装好且是最新的模型：再点开始什么也不做`() {
        val rig = Rig()
        val manager = rig.manager()
        manager.start(rig.id)
        manager.awaitInstalled(rig.id)
        rig.server.requests.clear()

        manager.start(rig.id)
        Thread.sleep(300)

        assertEquals(0, rig.server.requests.size)
        assertEquals(Transfer.Idle, manager.entry(rig.id).transfer)
    }

    @Test
    fun `目录里有多份清单：只有默认模型决定 active，装别的模型不改变它`() {
        val a = TestModel(tmp.newFolder(newFolderName("a")), modelId = "model-a", seed = 1)
        val b = TestModel(tmp.newFolder(newFolderName("b")), modelId = "model-b", seed = 2)
        val rig = Rig(model = a)
        val bServer = server(b.archiveBytes)
        rig.urls[b.url] = bServer.url
        val manager = rig.manager(catalog = rig.catalog(a, b))
        assertEquals(listOf("model-a", "model-b"), manager.state.value.models.map { it.info.modelId })
        assertEquals(listOf(true, false), manager.state.value.models.map { it.isDefault })

        manager.start("model-b")
        manager.awaitInstalled("model-b")

        assertTrue("默认模型还没装，active 不能因为装了别的模型而变", manager.active.value is ActiveModelStatus.NotInstalled)
        manager.start("model-a")
        manager.awaitInstalled("model-a")
        assertEquals("model-a", ready(manager).manifest.modelId)
    }

    @Test
    fun `重启时清掉暂存目录、别的版本的下载残片，指针文件损坏就当作没装而不是崩溃`() {
        val rig = Rig()
        val first = rig.manager()
        first.start(rig.id)
        first.awaitInstalled(rig.id)
        File(rig.root, "model-staging/${rig.id}-dead").apply { mkdirs() }.resolve("model.bin").writeText("半截")
        File(rig.root, "model-downloads/${rig.id}/0.9.0").apply { mkdirs() }.resolve(ResumableDownloader.PART_FILE).writeText("旧版本的残片")

        val restarted = rig.manager()

        assertEquals(emptyList<String>(), filesUnder(File(rig.root, "model-staging")))
        assertEquals(emptyList<String>(), filesUnder(File(rig.root, "model-downloads")))
        assertTrue(restarted.active.value is ActiveModelStatus.Ready)

        File(rig.root, "model-store/${rig.id}/active.json").writeText("{ 坏了")
        val afterCorruption = rig.manager()
        assertTrue(afterCorruption.active.value is ActiveModelStatus.NotInstalled)
        assertEquals(null, afterCorruption.entry(rig.id).installedVersion)
    }

    @Test
    fun `进度只增不减，最后等于压缩包大小`() {
        val rig = Rig(model = TestModel(tmp.newFolder(newFolderName("big")), modelSize = 2_000_000))
        val manager = rig.manager()
        val seen = CopyOnWriteArrayList<Long>()
        val collector = scope.launch { manager.state.collect { s -> (s.entry(rig.id).transfer as? Transfer.Downloading)?.let { seen += it.bytesDone } } }

        manager.start(rig.id)
        manager.awaitInstalled(rig.id)
        collector.cancel()

        assertTrue(seen.isNotEmpty())
        assertEquals(seen.sorted(), seen.toList())
    }
}
