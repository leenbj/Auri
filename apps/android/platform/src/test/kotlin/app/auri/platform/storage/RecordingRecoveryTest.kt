package app.auri.platform.storage

import app.auri.asr.audio.WavReader
import app.auri.capture.adapter.PcmFormat
import app.auri.capture.adapter.RecordingWriter
import app.auri.capture.adapter.RecoveredRecording
import app.auri.capture.adapter.RecoveredRun
import app.auri.capture.contract.CaptureHandle
import app.auri.capture.contract.GateState
import app.auri.capture.testing.Samples
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Ticket 19 第 3、6 项（存储这一侧）：分段落盘、进程被杀之后下次启动逐段验证并找回、写满时的行为。
 *
 * 用的都是真文件和真存储代码，只有 [FaultyDisk] 这一条缝是假的（写不下、被杀在第几个字节、记录操作顺序）。
 * 分段提交间隔在这里调成 100 ms（1600 个采样），让一小段音频就跨过好几个边界。
 */
class RecordingRecoveryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val format = PcmFormat(16_000)
    private val recordId = Samples.uuid(1)
    private val sessionId = Samples.uuid(1001)
    private val open = CaptureHandle(sessionId, 1, 1, GateState.OPEN)
    private val meta = Samples.recordingMeta(sessionId = sessionId)
    private val records get() = File(folder.root, "records")
    private val part get() = File(records, "$recordId.wav.part")
    private val wav get() = File(records, "$recordId.wav")
    private val journal get() = File(records, "$recordId.journal")

    private fun store(disk: FaultyDisk = FaultyDisk(), dir: File = records) = WavRecordingStore(dir, disk.io, commitIntervalMs = 100)

    /** 每个位置的采样值都由位置决定：找回的字节对不对，不用存副本就能重新算出来。 */
    private fun pcm(start: Long, count: Int) = ShortArray(count) { (((start + it) * 7) % 20_000 - 10_000).toShort() }

    private fun bytes(samples: ShortArray): ByteArray =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putShort(it) } }.array()

    private fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun expected(run: RecoveredRun) = pcm(run.startSample, (run.endSample - run.startSample).toInt())

    /**
     * 三段语音（中间各隔一段被 VAD 丢掉的静音），每次写 480 个采样（30 ms）：
     * [0,1920) 在写第 5 次时（遇到空隙）提交，[4800,6240) 在写第 8 次时（又遇到空隙）提交，[9600,10560) 要等封存才提交。
     */
    private val script = listOf(
        0L to 480, 480L to 480, 960L to 480, 1440L to 480,
        4800L to 480, 5280L to 480, 5760L to 480,
        9600L to 480, 10080L to 480,
    )

    private fun play(writer: RecordingWriter, pieces: List<Pair<Long, Int>> = script) {
        for ((start, count) in pieces) writer.append(open, start, pcm(start, count))
    }

    private fun contiguous(count: Int) = List(count) { it * 480L to 480 }

    private suspend fun recover(store: WavRecordingStore = store(), registered: Set<String> = emptySet()) = store.recoverOrphans { it in registered }

    /** 恢复结果的全部内容，不含 droppedBytes（那是“这一轮截掉了多少”，第二轮自然是 0）。 */
    private fun describe(r: RecoveredRecording) =
        "${r.recordId}|${r.captureSessionId}|${r.startedAt}|${r.evidence}|${r.routeEvidenceId}|${r.runs}|${r.audio}"

    private fun <T : Any> T?.orFail(): T = this ?: throw AssertionError("期望非 null")

    private fun readPcm(location: String): ShortArray = WavReader.read(File(location)).samples

    // ---- 日志先于音频 ----

    @Test
    fun `begin 一返回，恢复所需的一切（会话、开始时间、路由证据）已经落盘，早于任何音频`() = runBlocking {
        val disk = FaultyDisk()
        store(disk).begin(recordId, open, format, meta) // 之后进程在写第一个采样之前就被杀

        val scan = recover(store())

        val r = scan.recovered.single()
        assertEquals(recordId, r.recordId)
        assertEquals(sessionId, r.captureSessionId)
        assertEquals(meta.startedAt, r.startedAt)
        assertEquals(meta.evidence, r.evidence)
        assertEquals(meta.routeEvidenceId, r.routeEvidenceId)
        assertEquals(format, r.format)
        assertTrue(r.runs.isEmpty())
        assertNull("没有任何验证过的音频", r.audio)
        assertTrue(scan.failures.isEmpty())
        val log = disk.log
        assertTrue("日志先落盘（sync 日志）、之后才创建音频文件", log.indexOf("sync:$recordId.journal") in 0 until log.indexOf("open:$recordId.wav.part"))
        assertTrue("创建完两个文件之后同步目录项", log.indexOf("syncDir") > log.indexOf("open:$recordId.wav.part"))
    }

    @Test
    fun `begin 的日志行写到一半就被杀：音频文件还没创建，什么都没录到；恢复只清掉日志空壳，不算失败`() = runBlocking {
        for (killAt in listOf(0L, 1L, 10L, 100L)) {
            records.deleteRecursively()
            try {
                store(FaultyDisk(killAfterBytes = killAt)).begin(recordId, open, format, meta)
                fail("应当被杀（$killAt）")
            } catch (expected: FaultyDisk.ProcessKilled) {
                // 预期
            }
            assertTrue("被杀时只有（残缺的）日志（$killAt）", journal.isFile && !part.exists() && !wav.exists())

            val scan = recover()

            assertTrue("什么都没录到，没有 Record 可交（$killAt）", scan.recovered.isEmpty())
            assertTrue("也不是恢复失败（$killAt）：${scan.failures}", scan.failures.isEmpty())
            assertEquals("空壳被清掉（$killAt）", emptyList<String>(), records.list()!!.toList())
        }
    }

    @Test
    fun `写入顺序：数据先 fsync、才写这一段的日志行并 fsync；下一段的数据在日志落盘之后才开始写`() {
        val disk = FaultyDisk()
        val writer = store(disk).begin(recordId, open, format, meta)
        disk.log.clear()

        play(writer, script.take(5)) // 写第 5 次时遇到空隙，提交 [0,1920)

        val log = disk.log
        val commitAt = log.indexOf("sync:$recordId.wav.part")
        assertTrue("提交时先同步数据文件", commitAt >= 0)
        val journalWrite = log.indexOfFirst { it.startsWith("write:$recordId.journal") }
        assertTrue("数据 fsync 之后才写日志行", journalWrite > commitAt)
        assertTrue("日志行写完马上 fsync", log.indexOf("sync:$recordId.journal") > journalWrite)
        val nextData = log.indexOfLast { it.startsWith("write:$recordId.wav.part") }
        assertTrue("下一段的数据在日志落盘之后才开始写", nextData > log.indexOf("sync:$recordId.journal"))
        assertEquals("提交之前的 4 次写入都在 fsync 之前", 4, log.take(commitAt).count { it.startsWith("write:$recordId.wav.part") })
    }

    // ---- 进程被杀 ----

    @Test
    fun `进程被杀：已提交的分段全部找回，哈希逐段对得上，没提交的尾巴被截掉，音频是完整可读的 WAV`() = runBlocking {
        play(store().begin(recordId, open, format, meta)) // 不封存、不释放：被杀

        val scan = recover()

        val r = scan.recovered.single()
        assertEquals(listOf(RecoveredRun(0, 1920, sha(bytes(pcm(0, 1920)))), RecoveredRun(4800, 6240, sha(bytes(pcm(4800, 1440))))), r.runs)
        assertEquals("最后那段 [9600,10560) 还没提交，是不可信的尾巴：960 个采样", 1920L, r.droppedBytes)
        val audio = r.audio.orFail()
        assertEquals(3360L, audio.sampleCount)
        assertEquals(wav.absolutePath, audio.location)
        assertEquals(44L + 3360 * 2, wav.length())
        assertEquals(wav.length(), audio.sizeBytes)
        assertFalse("改名之后不再有 .part", part.exists())
        assertArrayEquals(pcm(0, 1920) + pcm(4800, 1440), readPcm(audio.location))
        assertEquals(sha(bytes(pcm(0, 1920) + pcm(4800, 1440))), audio.sha256)
        assertTrue(scan.failures.isEmpty())
        assertTrue("日志留着，直到录音登记进库", journal.isFile)
    }

    @Test
    fun `连续的多个分段合并成一段语音，这一段的哈希覆盖它所有的字节`() = runBlocking {
        play(store().begin(recordId, open, format, meta), contiguous(10)) // 4800 个采样连续：[0,1920)、[1920,3840) 已提交，其后 960 个未提交

        val r = recover().recovered.single()

        assertEquals(listOf(RecoveredRun(0, 3840, sha(bytes(pcm(0, 3840))))), r.runs)
        assertEquals(3840L, r.audio?.sampleCount)
    }

    @Test
    fun `正常封存之后，恢复仍能认出这条没交付的录音：整段原样找回，什么都没丢`() = runBlocking {
        val sealed = store().begin(recordId, open, format, meta).also { play(it) }.seal()

        val r = recover().recovered.single()

        assertEquals(listOf(RecoveredRun(0, 1920, sha(bytes(pcm(0, 1920)))), RecoveredRun(4800, 6240, sha(bytes(pcm(4800, 1440)))), RecoveredRun(9600, 10560, sha(bytes(pcm(9600, 960))))), r.runs)
        assertEquals(0L, r.droppedBytes)
        assertEquals(sealed.sha256, r.audio?.sha256)
        assertEquals(sealed.sizeBytes, r.audio?.sizeBytes)
        assertEquals(sealed.sampleCount, r.audio?.sampleCount)
    }

    @Test
    fun `已经登记进库的录音不再恢复，只清理它的日志，音频原样保留`() = runBlocking {
        store().begin(recordId, open, format, meta).also { play(it) }.seal()

        val scan = recover(registered = setOf(recordId))

        assertTrue(scan.recovered.isEmpty())
        assertTrue(scan.failures.isEmpty())
        assertFalse(journal.exists())
        assertTrue("登记过的音频不能动", wav.isFile)
        assertEquals(44L + 4320 * 2, wav.length())
    }

    // ---- 掉电、磁盘损坏：不可信的字节一律截掉 ----

    @Test
    fun `日志最后一行被撕断：忽略那一行，它之前提交的照常找回，数据截回上一个有效边界`() = runBlocking {
        play(store().begin(recordId, open, format, meta))
        RandomAccessFile(journal, "rw").use { it.setLength(it.length() - 7) } // 撕掉第二个分段那行的结尾

        val r = recover().recovered.single()

        assertEquals(listOf(0L to 1920L), r.runs.map { it.startSample to it.endSample })
        assertEquals(1920L, r.audio?.sampleCount)
        assertEquals("[4800,6240) 和未提交的 [9600,10560) 都不可信：(1440 + 960) 个采样", 4800L, r.droppedBytes)
        assertEquals(44L + 1920 * 2, wav.length())
        assertArrayEquals(pcm(0, 1920), readPcm(wav.path))
    }

    @Test
    fun `日志中间一行校验不对：到那一行为止，之后的行即使完好也不信`() = runBlocking {
        play(store().begin(recordId, open, format, meta), script + listOf(14400L to 480, 14880L to 480, 15360L to 480, 15840L to 480, 19200L to 480)) // 提交四个分段
        val lines = journal.readText().lines().filter { it.isNotEmpty() }
        assertEquals("begin + 四个分段", 5, lines.size)
        val damaged = lines.toMutableList().also { it[2] = (if (it[2][0] == '0') '1' else '0') + it[2].substring(1) } // 校验码对不上了
        journal.writeText(damaged.joinToString("\n", postfix = "\n"))

        val r = recover().recovered.single()

        assertEquals("只剩第一个分段", listOf(0L to 1920L), r.runs.map { it.startSample to it.endSample })
    }

    @Test
    fun `数据文件在已提交的分段里被撕断（掉电）：这一段和之后的全丢，截回上一个有效边界`() = runBlocking {
        play(store().begin(recordId, open, format, meta))
        RandomAccessFile(part, "rw").use { it.setLength(44L + 1920 * 2 + 1001) } // 撕在第二个分段的中间

        val r = recover().recovered.single()

        assertEquals(listOf(0L to 1920L), r.runs.map { it.startSample to it.endSample })
        assertEquals(1001L, r.droppedBytes)
        assertEquals(44L + 1920 * 2, wav.length())
        assertArrayEquals(pcm(0, 1920), readPcm(wav.path))
    }

    @Test
    fun `数据被改了一个字节：这一段的哈希对不上，它和之后的丢弃，前面的保留`() = runBlocking {
        play(store().begin(recordId, open, format, meta))
        RandomAccessFile(part, "rw").use { f ->
            f.seek(44L + 1920 * 2 + 100)
            val b = f.read()
            f.seek(44L + 1920 * 2 + 100)
            f.write(b xor 0x01)
        }

        val r = recover().recovered.single()

        assertEquals(listOf(0L to 1920L), r.runs.map { it.startSample to it.endSample })
        assertEquals(1920L, r.audio?.sampleCount)
    }

    @Test
    fun `第一个分段就不可信：没有任何音频留下，Record 仍然被找回（没有音频）；不可信的音频壳留到它登记之后才清理`() = runBlocking {
        play(store().begin(recordId, open, format, meta))
        RandomAccessFile(part, "rw").use { f ->
            f.seek(44L + 10)
            f.write(0x55)
        }

        val r = recover().recovered.single()

        assertTrue(r.runs.isEmpty())
        assertNull(r.audio)
        assertFalse("没有可信的音频，不会产出一个 .wav 冒充", wav.exists())
        // 评审 M2：恢复交出的 Record 还没登记进库。这时删掉音频壳而日志的分段行还在，就是一个再也恢复不了的状态
        assertTrue("日志与不可信的音频壳都原样留着，直到 Record 登记", journal.isFile && part.isFile)
    }

    /**
     * Ticket 19 评审 M2：第一个分段就不可信时，旧恢复把 .part 删了、日志里的分段行还在；恢复交出的 Record 登记之前进程又被杀，
     * 下次启动日志说“有分段”、音频文件却没了，只能报 audio_missing，这个 Record 永远建不起来。
     */
    @Test
    fun `第一个分段不可信、交出的 Record 登记之前进程又被杀：再恢复得到同一个没有音频的 Record，不是 audio_missing；登记之后清理干净`() = runBlocking {
        play(store().begin(recordId, open, format, meta))
        RandomAccessFile(part, "rw").use { f ->
            f.seek(44L + 10)
            f.write(0x55)
        }

        val first = recover().recovered.single() // 第一次恢复；进程在登记之前被杀
        val afterKill = recover(store()) // 新的进程，只凭第一次恢复之后留在磁盘上的东西
        val second = afterKill.recovered.single()

        assertTrue("不是 audio_missing：${afterKill.failures}", afterKill.failures.isEmpty())
        assertEquals("恢复可重复：同一个 Record", describe(first), describe(second))
        assertEquals("再来一次还是一样", describe(first), describe(recover(store()).recovered.single()))

        val settled = recover(store(), registered = setOf(recordId)) // Record 登记之后的那次启动
        assertTrue(settled.recovered.isEmpty() && settled.failures.isEmpty())
        assertEquals("日志与音频壳都清掉了，什么都不剩", emptyList<String>(), records.list()!!.toList())
    }

    // ---- Ticket 19 评审 M1：没有任何音频的录音，Record 仍然欠着 ----

    @Test
    fun `一个采样都没写下就收场：不留音频文件，日志留到登记；登记之前被杀，下次启动找回一个没有音频的 Record，登记之后清理干净`() = runBlocking {
        val thisProcess = store()
        thisProcess.begin(recordId, open, format, meta).finishWithoutAudio()

        assertEquals("没有音频文件，只有恢复记录", listOf("$recordId.journal"), records.list()!!.toList())
        assertTrue("本进程的恢复扫描不把它当孤儿（它正在被交付）", thisProcess.recoverOrphans { false }.recovered.isEmpty())

        // 进程在 Record 登记之前被杀（或登记时库写失败）：下一个进程只凭磁盘
        val scan = recover(store())
        val r = scan.recovered.single()
        assertTrue(scan.failures.isEmpty())
        assertEquals(recordId, r.recordId)
        assertEquals(sessionId, r.captureSessionId)
        assertEquals(meta.startedAt, r.startedAt)
        assertTrue(r.runs.isEmpty())
        assertNull(r.audio)
        assertEquals("恢复可重复", describe(r), describe(recover(store()).recovered.single()))

        val settled = recover(store(), registered = setOf(recordId))
        assertTrue(settled.recovered.isEmpty() && settled.failures.isEmpty())
        assertEquals("登记之后清理干净", emptyList<String>(), records.list()!!.toList())
    }

    @Test
    fun `写过采样的录音不能当作没有音频：抛出，一个字节都不删，之后照常封存`() = runBlocking {
        val writer = store().begin(recordId, open, format, meta)
        play(writer, contiguous(4))

        assertThrows(IllegalStateException::class.java) { writer.finishWithoutAudio() }

        assertTrue("音频与日志都还在", part.isFile && journal.isFile)
        assertEquals("写下的采样一个都没丢，仍然可以封存", 4L * 480, writer.seal().sampleCount)
    }

    @Test
    fun `没有音频的收场是幂等的；放弃之后再调无效，不会让已经删掉的东西重新出现`() {
        val a = store().begin(recordId, open, format, meta)
        a.finishWithoutAudio()
        a.finishWithoutAudio()
        assertEquals(listOf("$recordId.journal"), records.list()!!.toList())

        val other = Samples.uuid(4)
        val b = store().begin(other, open, format, meta)
        b.abort()
        b.finishWithoutAudio()
        assertFalse(File(records, "$other.journal").exists() || File(records, "$other.wav.part").exists())
    }

    // ---- 幂等、可重入 ----

    @Test
    fun `恢复是幂等的：连续两次的结果一模一样，磁盘上的文件也不再变化`() = runBlocking {
        play(store().begin(recordId, open, format, meta))

        val first = recover().recovered.single()
        val filesAfterFirst = records.list()!!.sorted()
        val wavAfterFirst = wav.readBytes()
        val second = recover().recovered.single()

        assertEquals(describe(first), describe(second))
        assertEquals(filesAfterFirst, records.list()!!.sorted())
        assertArrayEquals(wavAfterFirst, wav.readBytes())
    }

    @Test
    fun `恢复做到一半被杀（截断和修头做完，改名之前）：再恢复一次得到和没被打断一样的结果`() = runBlocking {
        play(store().begin(recordId, open, format, meta))
        val undisturbed = run {
            val other = File(folder.root, "undisturbed")
            val writer = store(dir = File(other, "records")).begin(recordId, open, format, meta)
            play(writer)
            describe(recover(store(dir = File(other, "records"))).recovered.single()).replace(File(other, "records").path, "<dir>")
        }
        val killedRecovery = FaultyDisk().apply { failMoveOnce = FaultyDisk.ProcessKilled() }

        try {
            recover(store(killedRecovery))
            fail("应当在改名时被杀")
        } catch (expected: FaultyDisk.ProcessKilled) {
            // 预期
        }
        assertTrue("被杀时还是 .part，没有看上去完成的文件", part.exists() && !wav.exists())
        val again = recover().recovered.single()

        assertEquals(undisturbed, describe(again).replace(records.path, "<dir>"))
        assertTrue(wav.isFile)
    }

    @Test
    fun `一个录音恢复失败只影响它自己：记下原因，文件原样留着；别的照常找回`() = runBlocking {
        play(store().begin(recordId, open, format, meta))
        val brokenId = Samples.uuid(2)
        File(records, "$brokenId.journal").writeText("这不是日志\n")
        File(records, "$brokenId.wav.part").writeBytes(ByteArray(100) { 7 })

        val scan = recover()

        assertEquals(listOf(recordId), scan.recovered.map { it.recordId })
        assertEquals(listOf(brokenId), scan.failures.map { it.recordId })
        assertTrue("原因只是类别，不含路径或内容", scan.failures.single().reason.matches(Regex("[a-z_]+")))
        assertTrue("坏的录音原样留着，不删", File(records, "$brokenId.wav.part").isFile && File(records, "$brokenId.journal").isFile)
    }

    @Test
    fun `目录里不是录音的东西被忽略：非 uuid 的名字、子目录、别的扩展名`() = runBlocking {
        records.mkdirs()
        File(records, "README.txt").writeText("x")
        File(records, "foo.journal").writeText("x")
        File(records, "${Samples.uuid(9)}.journal").mkdirs() // 同名子目录
        File(records, "${Samples.uuid(8)}.tmp").writeText("x")

        val scan = recover()

        assertTrue(scan.recovered.isEmpty())
        assertTrue(scan.failures.isEmpty())
    }

    @Test
    fun `查“是否已登记”时库出了错：这个录音记为恢复失败，文件原样留着，不会误删日志`() = runBlocking {
        store().begin(recordId, open, format, meta).also { play(it) }.seal()

        val scan = store().recoverOrphans { throw IllegalStateException("库打不开（测试注入）") }

        assertTrue(scan.recovered.isEmpty())
        assertEquals(listOf(recordId), scan.failures.map { it.recordId })
        assertTrue(journal.isFile && wav.isFile)
    }

    @Test
    fun `没有录音目录（全新安装）也不是错误`() = runBlocking {
        val scan = recover(store(dir = File(folder.root, "never-created")))

        assertTrue(scan.recovered.isEmpty() && scan.failures.isEmpty())
    }

    // ---- 本进程自己的写入 ----

    @Test
    fun `本进程正在写的录音不会被恢复扫描当成孤儿，写入照常继续；封存之后也不会被本进程重复交付`() = runBlocking {
        val live = store()
        val writer = live.begin(recordId, open, format, meta)
        play(writer, script.take(3))

        assertTrue("正在写的不是孤儿", live.recoverOrphans { false }.recovered.isEmpty())
        play(writer, script.drop(3))
        val sealed = writer.seal()
        assertEquals(4320L, sealed.sampleCount)

        assertTrue("本进程自己封存的，由协调器交付，恢复不再碰", live.recoverOrphans { false }.recovered.isEmpty())
        assertTrue(wav.isFile)
        assertTrue("下一次启动（新的存储对象）才会清理它的日志", recover(registered = setOf(recordId)).recovered.isEmpty() && !journal.exists())
    }

    @Test
    fun `放弃删除一切，包括日志；释放保留一切，并且把还没提交的尾巴也提交，一个采样都不丢`() = runBlocking {
        val aborted = store().begin(recordId, open, format, meta)
        play(aborted)
        aborted.abort()
        assertEquals(emptyList<String>(), records.list()!!.toList())

        val other = Samples.uuid(3)
        val kept = store().begin(other, open, format, meta)
        play(kept)
        kept.release()
        kept.release()
        assertTrue(File(records, "$other.wav.part").isFile && File(records, "$other.journal").isFile)

        val r = recover().recovered.single()
        assertEquals("释放是体面的收场，不像被杀：4320 个采样都在", 4320L, r.audio?.sampleCount)
        assertEquals(0L, r.droppedBytes)
    }

    @Test
    fun `写入的位置不能倒退：拒绝，且文件一个字节不增`() {
        val writer = store().begin(recordId, open, format, meta)
        writer.append(open, 0, pcm(0, 480))
        val before = part.length()

        assertThrows(IllegalArgumentException::class.java) { writer.append(open, 100, pcm(100, 480)) }

        assertEquals(before, part.length())
    }

    // ---- 写满 ----

    @Test
    fun `磁盘写满（在选定的字节处）：append 抛 IOException，磁盘上只剩已经接受的完整采样；封存要么完成要么抛出（最后一行日志写不下），不留无法验证的 wav`() = runBlocking {
        val total = FaultyDisk().also { disk -> store(disk).begin(recordId, open, format, meta).also { play(it) }.seal() }.written
        var failures = 0
        var journalHits = 0
        var sealedOk = 0
        var sealRefused = 0
        for (free in generateSequence(0L) { it + 53 }.takeWhile { it < total }) {
            records.deleteRecursively()
            val disk = FaultyDisk(freeBytes = free)
            val writer = try {
                store(disk).begin(recordId, open, format, meta)
            } catch (e: IOException) {
                assertEquals("begin 失败不留任何残渣（空间 $free）", emptyList<String>(), records.list()?.toList().orEmpty())
                continue
            }
            var accepted = 0L
            val acceptedPieces = mutableListOf<Pair<Long, Int>>()
            for ((start, count) in script) {
                try {
                    writer.append(open, start, pcm(start, count))
                    acceptedPieces += start to count
                    accepted += count
                } catch (e: IOException) {
                    failures += 1
                    break
                }
            }
            val partSize = if (part.exists()) part.length() else -1
            assertEquals("没有半截的采样（空间 $free）：文件正好是头加上已接受的字节", 44L + accepted * 2, partSize)

            val sealed = try {
                writer.seal() // 头部原地改、改名不要新空间，但最后一段的日志行要
            } catch (e: IOException) {
                null
            }
            if (sealed == null) {
                // 最后一行日志写不下：封存不能完成，不留看上去已经完成的 wav；已提交的分段在下次启动逐字节找回
                sealRefused += 1
                assertFalse("空间 $free：封存没完成就不能有 wav", wav.exists())
                writer.release()
                val r = recover(store(FaultyDisk())).recovered.singleOrNull()
                val audio = r?.audio
                if (r != null && audio != null) {
                    assertArrayEquals("空间 $free：找回的是已提交的分段", r.runs.flatMap { expected(it).toList() }.toShortArray(), readPcm(audio.location))
                    assertTrue("空间 $free：找回的不超过已接受的", audio.sampleCount <= accepted)
                    for (run in r.runs) assertEquals("空间 $free", sha(bytes(expected(run))), run.sha256)
                }
                continue
            }
            sealedOk += 1
            assertEquals("空间 $free", accepted, sealed.sampleCount)
            val expectedSamples = acceptedPieces.flatMap { (s, n) -> pcm(s, n).toList() }.toShortArray()
            assertArrayEquals("空间 $free", expectedSamples, readPcm(sealed.location))
            assertEquals(sha(bytes(expectedSamples)), sealed.sha256)
            assertFalse(part.exists())
            if (journal.isFile) journalHits += 1
        }
        assertTrue("至少在几十个不同的字节处写满过（$failures）", failures >= 30)
        assertTrue("日志一直在", journalHits > 0)
        assertTrue("封存完成的位置也走到了（$sealedOk）", sealedOk > 0)
        assertTrue("最后一行日志写不下、封存被拒的位置也走到了（$sealRefused）", sealRefused > 0)
    }

    @Test
    fun `写满之后不封存、改成释放：日志仍然完好，下次启动找回已接受的音频`() = runBlocking {
        val disk = FaultyDisk(freeBytes = 7_000) // 够写完前两段的大部分，写不完全部
        val writer = store(disk).begin(recordId, open, format, meta)
        var accepted = 0L
        try {
            for ((start, count) in script) {
                writer.append(open, start, pcm(start, count))
                accepted += count
            }
            fail("应当写满")
        } catch (expected: IOException) {
            // 预期
        }
        writer.release()

        val r = recover().recovered.single()

        assertTrue("至少找回一部分", (r.audio?.sampleCount ?: 0) > 0)
        assertTrue("不会多于已接受的", (r.audio?.sampleCount ?: 0) <= accepted)
        for (run in r.runs) assertEquals(sha(bytes(expected(run))), run.sha256)
    }

    @Test
    fun `begin 遇到写满的磁盘：抛 IOException，不留日志也不留音频文件；空间腾出来之后可以重新开始`() = runBlocking {
        val full = FaultyDisk(freeBytes = 10)
        assertThrows(IOException::class.java) { store(full).begin(recordId, open, format, meta) }
        assertEquals(emptyList<String>(), records.list()!!.toList())

        val writer = store().begin(recordId, open, format, meta)
        play(writer)
        assertEquals(4320L, writer.seal().sampleCount)
    }

    @Test
    fun `提交时日志写不进去：这次 append 抛出且不写新数据，日志只有完整的行；腾出空间后换一批连续的数据继续，分段哈希仍然对得上`() = runBlocking {
        val disk = FaultyDisk()
        val writer = store(disk).begin(recordId, open, format, meta)
        play(writer, script.take(2)) // [0,960) 还没提交
        val before = part.length()
        disk.freeBytes = 3 // 容不下一行日志

        assertThrows(IOException::class.java) { writer.append(open, 4800, pcm(4800, 480)) } // 遇到空隙要先提交，提交失败

        assertEquals("提交失败，新数据一个字节都没写", before, part.length())
        assertEquals("日志里只有 begin 这一整行，没有半行", 1, journal.readText().lines().count { it.isNotEmpty() })
        disk.freeBytes = Long.MAX_VALUE
        // 上一次提交失败过：即使这一批和未提交的分段连续，也必须先把它提交了，新数据不能落进一个哈希已经定下的分段
        writer.append(open, 960, pcm(960, 480))
        writer.append(open, 1440, pcm(1440, 480))
        play(writer, script.drop(4))
        writer.release()

        val r = recover().recovered.single()
        assertEquals(listOf(0L to 1920L, 4800L to 6240L, 9600L to 10560L), r.runs.map { it.startSample to it.endSample })
        for (run in r.runs) assertEquals(sha(bytes(expected(run))), run.sha256)
        assertEquals(4320L, r.audio?.sampleCount)
        assertEquals(0L, r.droppedBytes)
    }

    @Test
    fun `封存时改名失败：抛出，part 文件和日志原样留着；释放之后下次启动整段找回`() = runBlocking {
        val disk = FaultyDisk()
        val writer = store(disk).begin(recordId, open, format, meta)
        play(writer)
        disk.failMoveOnce = IOException("改名失败（测试注入）")

        assertThrows(IOException::class.java) { runBlocking { writer.seal() } }
        writer.release()

        assertTrue(part.isFile && journal.isFile && !wav.exists())
        val r = recover().recovered.single()
        assertEquals(4320L, r.audio?.sampleCount)
    }

    // ---- Ticket 19 评审 B2：封存必须等分段记录落盘 ----

    @Test
    fun `seal cannot complete while the last segment cannot be journaled, and recovery after a crash before registration is deterministic`() = runBlocking {
        // 封存好的音频要能在进程被杀之后凭日志验证、找回。最后一段的日志行写不进去时，旧代码吞掉失败照样改名，
        // 留下一个没有任何分段记录的 .wav：登记之前被杀，之后每次恢复都拒收它（audio_unverified）。
        val cases = listOf(
            Triple("一个分段都没提交过（整段语音都在没提交的尾巴里）", script.take(3), null),
            Triple("提交过一个分段，尾巴没提交", script.take(7), 1920L),
        )
        for ((label, pieces, committedSamples) in cases) {
            records.deleteRecursively()
            val disk = FaultyDisk()
            val writer = store(disk).begin(recordId, open, format, meta)
            play(writer, pieces)
            disk.freeBytes = 3 // 容不下一行日志；封存的其余步骤（头部原地改写、改名）不要新空间

            assertThrows("$label：日志落不了盘，封存不能算完成", IOException::class.java) { runBlocking { writer.seal() } }
            assertFalse("$label：不能留下看上去已经完成的 .wav", wav.exists())
            assertTrue("$label：音频与日志原样留着，等下次启动恢复", part.isFile && journal.isFile)
            writer.release() // 协调器在封存失败之后释放写入器

            // 进程在登记之前被杀：新的进程只凭磁盘恢复，恢复多少次结果都一样
            val first = recover(store()).recovered.single()
            val second = recover(store()).recovered.single()

            assertEquals("$label：恢复可重复", describe(first), describe(second))
            if (committedSamples == null) {
                assertTrue("$label：没有任何可信的字节", first.runs.isEmpty() && first.audio == null)
            } else {
                assertEquals("$label：已提交的分段原样找回", committedSamples, first.audio?.sampleCount)
                for (run in first.runs) assertEquals(sha(bytes(expected(run))), run.sha256)
            }
        }
    }

    // ---- 在任意字节处被杀 ----

    @Test
    fun `在任意字节处被杀：恢复永远不抛异常；找回的音频逐字节等于当初写下的；多写的字节不会让找回的变少`() = runBlocking {
        val total = FaultyDisk().also { disk -> store(disk).begin(recordId, open, format, meta).also { play(it) }.seal() }.written
        val starts = script.map { it.first }.toSet()
        val ends = script.map { it.first + it.second }.toSet()
        val points = (0L..minOf(total, 700L)).toList() + (700L until total step 37).toList() + total
        var previous = 0L
        var killed = 0
        var recoveredAny = 0
        for (k in points) {
            records.deleteRecursively()
            val disk = FaultyDisk(killAfterBytes = k)
            try {
                store(disk).begin(recordId, open, format, meta).also { play(it) }.seal()
            } catch (e: FaultyDisk.ProcessKilled) {
                killed += 1
            }

            val scan = recover(store(FaultyDisk())) // 新的进程

            assertTrue("kill=$k", scan.recovered.size <= 1)
            val r = scan.recovered.singleOrNull()
            if (r == null) {
                assertEquals("kill=$k：什么都没恢复出来就不该有音频文件留下来冒充", 0, records.list()!!.count { it.endsWith(".wav") })
                continue
            }
            recoveredAny += 1
            assertEquals("kill=$k", sessionId, r.captureSessionId)
            assertEquals("kill=$k", meta.evidence, r.evidence)
            assertEquals("kill=$k", meta.startedAt, r.startedAt)
            for (run in r.runs) {
                assertTrue("kill=$k：分段起点只会落在写入的边界上", run.startSample in starts)
                assertTrue("kill=$k：分段终点只会落在写入的边界上", run.endSample in ends)
                assertEquals("kill=$k：每段的哈希是当初写下的字节的哈希", sha(bytes(expected(run))), run.sha256)
            }
            val samples = r.runs.sumOf { it.endSample - it.startSample }
            if (samples == 0L) {
                assertNull("kill=$k", r.audio)
                assertFalse("kill=$k：没有可信的音频就不产出 .wav 冒充", wav.exists())
                // 评审 M2：清理留到 Record 登记之后，而不是恢复的时候；登记之后的那次启动不留任何音频文件
                val settled = recover(store(FaultyDisk()), registered = setOf(recordId))
                assertTrue("kill=$k", settled.recovered.isEmpty() && settled.failures.isEmpty())
                assertFalse("kill=$k：登记之后没有可信的音频就不留音频文件", part.exists() || wav.exists())
            } else {
                val audio = r.audio.orFail()
                val want = r.runs.flatMap { expected(it).toList() }.toShortArray()
                assertArrayEquals("kill=$k：整理好的 WAV 逐采样等于当初写下的", want, readPcm(audio.location))
                assertEquals("kill=$k", sha(bytes(want)), audio.sha256)
                assertEquals("kill=$k", 44L + samples * 2, File(audio.location).length())
                assertFalse("kill=$k：不留 .part", File(audio.location + ".part").exists() || part.exists())
            }
            assertTrue("kill=$k：多写了字节，找回的不会比之前少（$samples < $previous）", samples >= previous)
            previous = samples
        }
        assertTrue("在几百个不同的字节处被杀过（$killed）", killed >= 500)
        assertTrue("其中大多数找回了音频（$recoveredAny）", recoveredAny >= 300)
        assertEquals("没有被杀（跑完并封存）时整段都在", 4320L, previous)
    }
}
