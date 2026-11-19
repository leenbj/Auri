package app.auri.platform.storage

import app.auri.asr.audio.WavReader
import app.auri.capture.adapter.RecoveredRecording
import app.auri.capture.contract.CaptureState
import app.auri.capture.contract.CaptureStatus
import app.auri.capture.contract.EndReason
import app.auri.capture.contract.ErrorCode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Ticket 19 第 3、6 项合起来：真的协调器 + 真的存储（真文件），磁盘在任意位置写满、进程在任意字节被杀。
 * 分开测协调器（假存储）与存储（直接调用写入器）时各自都对，合起来才看得出“写不下就收场、写下的一个采样都不丢”是不是真的。
 *
 * 每个场景都是同一段录音：开始 → 连续说 12 批（每批 100 ms）→ 用户停止。先在不出故障的磁盘上跑一遍量出总字节数，
 * 再让磁盘在不同位置出故障：覆盖开始时、录音中、停止时写最后一批、封存时提交日志等不同的失败点。
 * 分段提交间隔在这里是 100 ms（线上是 1 秒）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InterruptionEndToEndTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val batches = 12

    private fun newDir(): File = folder.newFolder()

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun bytes(samples: ShortArray): ByteArray =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putShort(it) } }.array()

    private fun TestScope.session(disk: FaultyDisk, directory: File): RealStoreRig {
        val rig = RealStoreRig(this, directory, disk)
        rig.startRecording()
        rig.speak(batches)
        rig.stop()
        return rig
    }

    private class Totals(val written: Long, val growth: Long)

    /** 不出故障的磁盘上跑一遍：必须完整结束，量出总共写了多少字节、占了多少空间。 */
    private fun TestScope.dryRun(): Totals {
        val big = 1_000_000_000L
        val disk = FaultyDisk(freeBytes = big)
        val rig = session(disk, newDir())
        rig.close()
        assertEquals(CaptureState.DISARMED, rig.state)
        assertEquals(EndReason.SCREEN_CONTROL, rig.output.recordings.single().result.endReason)
        assertEquals(batches * RealStoreRig.SAMPLES_PER_BATCH.toLong(), rig.output.recordings.single().audio?.sampleCount)
        return Totals(disk.written, big - disk.freeBytes)
    }

    /** 这个音频文件里的采样必须是送进去的语音的前缀，且文件头、字节数、哈希都与声明一致；返回采样。 */
    private fun checkPrefix(context: String, location: String, declaredSamples: Long, declaredSha: String?): ShortArray {
        val pcm = WavReader.read(File(location)).samples
        val expected = RealStoreRig.expectedPcm(batches)
        assertTrue("$context：音频不能比送进去的还长", pcm.size <= expected.size)
        assertTrue("$context：音频是送进去的语音的前缀，一个采样都不能错", expected.copyOf(pcm.size).contentEquals(pcm))
        assertEquals("$context：声明的采样数与文件一致", declaredSamples, pcm.size.toLong())
        if (declaredSha != null) assertEquals("$context：声明的哈希与文件里的字节一致", sha(bytes(pcm)), declaredSha)
        return pcm
    }

    private fun RecoveredRecording.checkedPcm(context: String): ShortArray {
        val audio = audio ?: return ShortArray(0)
        val pcm = checkPrefix(context, audio.location, audio.sampleCount, audio.sha256)
        assertEquals("$context：连续的语音只有一段", 1, runs.size)
        assertEquals("$context：这一段的哈希是文件里这些字节的哈希", sha(bytes(pcm)), runs.single().sha256)
        assertEquals(0L, runs.single().startSample)
        assertEquals(pcm.size.toLong(), runs.single().endSample)
        return pcm
    }

    // ---- 存储写满 ----

    @Test
    fun `磁盘在任意位置写满：协调器都安全收场，已写下的音频一个采样都不丢，下次启动不重复也不遗漏`() = runTest {
        val totals = dryRun()
        val coarse = (0L..totals.growth step maxOf(1L, totals.growth / 24)).toList()
        val nearEnd = ((totals.growth - 400).coerceAtLeast(0)..totals.growth step 5).toList() // 停止时写最后一批、封存时写日志都在最后这一小段
        val outcomes = mutableSetOf<String>()

        // 同一个写满位置各跑两遍：封存顺利；封存时改名失败（磁盘写满时的 seal 不需要新空间，要让它失败得另想办法）
        val cases = (coarse + nearEnd).toSortedSet().flatMap { free -> listOf(free to false, free to true) }
        for ((free, sealFails) in cases) {
            val directory = newDir()
            val disk = FaultyDisk(freeBytes = free)
            if (sealFails) disk.failMoveOnce = java.io.IOException("改名失败（测试注入）")
            val rig = session(disk, directory)
            val context = "磁盘只剩 $free 字节" + if (sealFails) "，封存时改名失败" else ""
            try {
                assertTrue("$context：不该有没人处理的异常：${rig.deaths}", rig.deaths.isEmpty())
                assertTrue("$context：协调器必须收场，不能停在录音或封存中（${rig.state}）", rig.state == CaptureState.DISARMED || rig.state == CaptureState.BLOCKED)
                assertEquals("$context：每次打开的采集恰好释放一次", rig.capture.requests.map { it.operationId }, rig.capture.releases.toList())
                assertEquals("$context：没有自己重新开始", 1, rig.capture.requests.size)

                val sealed = rig.output.recordings.toList()
                val lost = rig.output.lost.toList()
                val registered = sealed.map { it.result.recordId }.toSet()
                val scan = runBlocking { WavRecordingStore(directory).recoverOrphans { it in registered } }
                assertTrue("$context：下次启动读盘不出错：${scan.failures}", scan.failures.isEmpty())

                when {
                    rig.state == CaptureState.BLOCKED -> {
                        outcomes += "开始就写不下"
                        assertEquals(context, ErrorCode.STORAGE_EXHAUSTED, rig.snapshot.blockedReason)
                        assertTrue("$context：没有录音，也没有通知丢失", sealed.isEmpty() && lost.isEmpty())
                        assertTrue("$context：磁盘上不留任何残渣", scan.recovered.isEmpty() && directory.list().orEmpty().isEmpty())
                    }

                    sealed.size == 1 -> {
                        val record = sealed.single()
                        val audio = record.audio
                        assertTrue("$context：封存失败的注入只会让有音频的录音走到“丢失”那条路", !sealFails || audio == null)
                        val pcm = if (audio == null) ShortArray(0) else checkPrefix(context, audio.location, audio.sampleCount, audio.sha256)
                        assertEquals("$context：封存的采样数就是协调器写下并计数的", rig.snapshot.counters.writtenSamples, pcm.size.toLong())
                        if (audio != null) assertEquals("$context：连续的语音只有一段，它的哈希就是整个音频的", audio.sha256, record.result.chunks.single().sha256)
                        if (record.result.endReason == EndReason.SYSTEM_INTERRUPTION) {
                            outcomes += "写满后封存了已写下的部分"
                            assertEquals(context, CaptureStatus.PARTIAL, record.result.status)
                            assertEquals("$context：界面看得到是存储", ErrorCode.STORAGE_EXHAUSTED, rig.snapshot.interruption)
                            assertEquals(context, EndReason.SYSTEM_INTERRUPTION, rig.snapshot.interruptionReason)
                        } else {
                            outcomes += "完整结束"
                            assertEquals(context, EndReason.SCREEN_CONTROL, record.result.endReason)
                            assertEquals(context, CaptureStatus.COMPLETE, record.result.status)
                            assertEquals("$context：完整结束的录音一个采样不少", batches * RealStoreRig.SAMPLES_PER_BATCH, pcm.size)
                        }
                        assertTrue("$context：已经登记的录音不会被当成孤儿再恢复一遍", scan.recovered.isEmpty())
                        val files = if (audio == null) emptyList() else listOf("${record.result.recordId}.wav")
                        assertEquals("$context：登记之后恢复记录被清掉，只剩音频（一个采样都没写下的，什么都不剩）", files, directory.list().orEmpty().sorted())
                    }

                    lost.size == 1 -> {
                        assertEquals(context, ErrorCode.STORAGE_EXHAUSTED, lost.single().second)
                        assertTrue("$context：没有交出任何录音结果", sealed.isEmpty())
                        val written = rig.snapshot.counters.writtenSamples
                        if (written == 0L) {
                            // 第一批都没写下就写满了：没有音频可留，通知“录音丢失（存储）”，而不是留一个没有音频的 Record 冒充“无语音”；磁盘上不留残渣
                            outcomes += "第一批都没写下就写满：通知丢失，不留 Record 也不留残渣"
                            assertTrue("$context：一个采样都没写下，磁盘上什么都不剩：${scan.recovered}", scan.recovered.isEmpty() && directory.list().orEmpty().isEmpty())
                        } else {
                            outcomes += "封存没完成但分段留在磁盘上，下次启动找回"
                            assertTrue("$context：磁盘写满时封存不需要新空间，只有注入的封存失败才会走到这里", sealFails)
                            val recovered = scan.recovered.single()
                            assertEquals(context, lost.single().first, recovered.recordId)
                            val kept = recovered.checkedPcm(context).size.toLong()
                            assertTrue("$context：找回的不超过写下的（$kept > $written）", kept <= written)
                            assertTrue("$context：丢的不超过一个提交间隔（$written 写下，$kept 找回）", written - kept <= COMMIT_SAMPLES)
                            assertTrue("$context：找回的是真的有内容的分段", kept > 0 || written <= COMMIT_SAMPLES)
                        }
                    }

                    else -> throw AssertionError("$context：既没有封存也没有通知丢失：sealed=$sealed lost=$lost")
                }
            } finally {
                rig.close()
            }
        }

        assertEquals(
            "扫描必须真的走到过每一种收场方式，否则这个测试什么也没证明：$outcomes",
            setOf("开始就写不下", "第一批都没写下就写满：通知丢失，不留 Record 也不留残渣", "写满后封存了已写下的部分", "完整结束", "封存没完成但分段留在磁盘上，下次启动找回"),
            outcomes,
        )
    }

    // ---- 进程被杀 ----

    @Test
    fun `进程在任意字节被杀：下次启动找回的是写下过的音频的前缀，哈希对得上；丢掉的不超过一个提交间隔加一次写入加一个分析窗`() = runTest {
        val totals = dryRun()
        val points = ((0L until totals.written step maxOf(1L, totals.written / 40)).toList() + listOf(1L, 10L, 100L, totals.written - 1)).toSortedSet()
        val outcomes = mutableSetOf<String>()
        var worstLoss = 0L

        for (kill in points) {
            val directory = newDir()
            val rig = session(FaultyDisk(killAfterBytes = kill), directory)
            val context = "在第 $kill 个字节被杀"
            try {
                assertEquals("$context：恰好被杀一次：${rig.deaths}", 1, rig.deaths.size)
                assertTrue("$context：被杀的原因必须是模拟的被杀，而不是别的意外：${rig.deaths}", rig.deaths.single() is FaultyDisk.ProcessKilled)

                // 下次启动：全新的存储对象（没有任何内存里的记忆），只凭磁盘
                val scan = runBlocking { WavRecordingStore(directory).recoverOrphans { false } }
                assertTrue("$context：恢复不出错：${scan.failures}", scan.failures.isEmpty())
                val fed = rig.fedAtDeath ?: rig.fedSamples

                if (scan.recovered.isEmpty()) {
                    outcomes += "什么都没留下"
                    assertTrue("$context：没留下 Record 的，磁盘上也没有音频：${directory.list().orEmpty().toList()}", directory.list().orEmpty().none { it.endsWith(".wav") || it.endsWith(".part") })
                    assertTrue("$context：被杀时还没有任何语音送进来，什么都没有可丢（已送入 $fed）", fed <= COMMIT_SAMPLES + RealStoreRig.SAMPLES_PER_BATCH)
                    continue
                }
                val recovered = scan.recovered.single()
                val kept = recovered.checkedPcm(context).size.toLong()
                outcomes += if (kept == 0L) "只有开始的记录，没有已提交的音频" else "找回了已提交的分段"
                val loss = fed - kept
                worstLoss = maxOf(worstLoss, loss)
                assertTrue("$context：丢掉的 $loss 个采样超过了 一个提交间隔 + 一次写入 + 一个分析窗（$MAX_LOSS_SAMPLES）", loss <= MAX_LOSS_SAMPLES)
            } finally {
                rig.close()
            }
        }

        assertEquals(
            "扫描必须真的走到过每一种被杀的位置：$outcomes",
            setOf("什么都没留下", "只有开始的记录，没有已提交的音频", "找回了已提交的分段"),
            outcomes,
        )
        println("MEASURED kill-sweep points=${points.size} worstLossSamples=$worstLoss (${worstLoss / 16} ms at 16 kHz) boundSamples=$MAX_LOSS_SAMPLES commitSamples=$COMMIT_SAMPLES")
    }

    private companion object {
        const val COMMIT_SAMPLES = RealStoreRig.COMMIT_MS * 16

        /** 一个提交间隔 + 一次写入（100 ms 一批）+ 一个 10 ms 的分析窗。 */
        const val MAX_LOSS_SAMPLES = COMMIT_SAMPLES + RealStoreRig.SAMPLES_PER_BATCH + 160
    }
}
