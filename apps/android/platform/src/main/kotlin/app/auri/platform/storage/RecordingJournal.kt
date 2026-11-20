package app.auri.platform.storage

import app.auri.capture.adapter.PcmFormat
import app.auri.capture.contract.ContractJson
import app.auri.capture.contract.RouteChangeEvent
import java.io.IOException
import java.io.RandomAccessFile
import java.time.Instant
import java.util.zip.CRC32
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * 一次录音的日志（`<record_id>.journal`）：进程被杀之后，恢复流程只凭磁盘上的东西把这次录音找回来（Ticket 19 第 3 项）。
 *
 * 一行一条记录：`<8 位十六进制 CRC32> <紧凑 JSON>\n`，CRC 只覆盖 JSON 那部分。读的时候遇到第一条不完整、校验不对、
 * 内容读不懂的行就停下，它之后的一律不信——撕断的最后一行和中间被写坏的行都是这样处理，不会“跳过去继续读”。
 *
 *  - `begin`：第一行，在任何音频之前落盘。Record 是谁、哪次会话、什么时候开始、确认路由的那次观测、PCM 格式；
 *  - `seg`：一个**已提交**的分段：采样在原始时间线上的 [start, end)、提交之后音频文件里累计的 PCM 字节数、这段字节的 SHA-256。
 *    写这一行之前，这一段的数据已经 fsync；这一行本身也立刻 fsync。所以日志里有的分段，数据一定在。
 */
internal object RecordingJournal {
    const val VERSION = 1

    class Begin(
        val recordId: String,
        val sessionId: String,
        val startedAt: Instant,
        val evidenceId: String,
        val evidence: RouteChangeEvent,
        val format: PcmFormat,
    )

    class Segment(val start: Long, val end: Long, val offset: Long, val sha256: String)

    class Parsed(val begin: Begin?, val segments: List<Segment>)

    fun beginLine(begin: Begin): ByteArray = line(
        buildJsonObject {
            put("t", "begin")
            put("v", VERSION)
            put("record_id", begin.recordId)
            put("session", begin.sessionId)
            put("started_at", begin.startedAt.toString())
            put("evidence_id", begin.evidenceId)
            put("evidence", ContractJson.json.parseToJsonElement(ContractJson.encode(begin.evidence)))
            put("rate", begin.format.sampleRateHz)
            put("channels", begin.format.channels)
        },
    )

    fun segmentLine(segment: Segment): ByteArray = line(
        buildJsonObject {
            put("t", "seg")
            put("start", segment.start)
            put("end", segment.end)
            put("off", segment.offset)
            put("sha", segment.sha256)
        },
    )

    private fun line(json: JsonObject): ByteArray {
        val body = json.toString().toByteArray(Charsets.UTF_8)
        val crc = CRC32().apply { update(body) }.value
        return "%08x ".format(crc).toByteArray(Charsets.US_ASCII) + body + '\n'.code.toByte()
    }

    /** 读出日志里按顺序可信的部分：第一条必须是 begin，其后只认 seg；任何一行有问题，后面的全不要。 */
    fun parse(bytes: ByteArray): Parsed {
        var begin: Begin? = null
        val segments = mutableListOf<Segment>()
        var offset = 0
        while (offset < bytes.size) {
            val end = bytes.indexOf('\n'.code.toByte(), offset)
            if (end < 0) break // 没写完的最后一行
            val entry = decode(bytes, offset, end) ?: break
            offset = end + 1
            when {
                begin == null -> begin = (entry as? Begin ?: break)
                entry is Segment -> segments += entry
                else -> break
            }
        }
        return Parsed(begin, segments)
    }

    private fun ByteArray.indexOf(value: Byte, from: Int): Int {
        for (i in from until size) if (this[i] == value) return i
        return -1
    }

    private fun decode(bytes: ByteArray, start: Int, end: Int): Any? {
        if (end - start < 10 || bytes[start + 8] != ' '.code.toByte()) return null
        val crc = bytes.decodeToString(start, start + 8).toLongOrNull(16) ?: return null
        val body = bytes.copyOfRange(start + 9, end)
        if (CRC32().apply { update(body) }.value != crc) return null
        return try {
            val json = ContractJson.json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
            when (json["t"]?.jsonPrimitive?.content) {
                "begin" -> {
                    require(json.getValue("v").jsonPrimitive.int == VERSION)
                    Begin(
                        recordId = json.getValue("record_id").jsonPrimitive.content,
                        sessionId = json.getValue("session").jsonPrimitive.content,
                        startedAt = Instant.parse(json.getValue("started_at").jsonPrimitive.content),
                        evidenceId = json.getValue("evidence_id").jsonPrimitive.content,
                        evidence = ContractJson.json.decodeFromJsonElement(RouteChangeEvent.serializer(), json.getValue("evidence")),
                        format = PcmFormat(json.getValue("rate").jsonPrimitive.int, json.getValue("channels").jsonPrimitive.int),
                    )
                }
                "seg" -> Segment(
                    start = json.getValue("start").jsonPrimitive.long,
                    end = json.getValue("end").jsonPrimitive.long,
                    offset = json.getValue("off").jsonPrimitive.long,
                    sha256 = json.getValue("sha").jsonPrimitive.content,
                )
                else -> null
            }
        } catch (e: Exception) {
            null // 校验过了但内容读不懂（版本不认识、字段缺失）：和撕断的行一样处理
        }
    }
}

/**
 * 日志文件的追加端：每条记录一次写入加一次 fsync；失败时把可能写了一半的行截掉再抛出，日志永远只增长完整的行。
 */
internal class JournalWriter(private val file: RandomAccessFile, private val name: String, private val io: StorageIo) {
    fun append(line: ByteArray) {
        val before = file.length()
        try {
            file.seek(before)
            file.write(line)
            io.sync(file, name)
        } catch (e: IOException) {
            try {
                file.setLength(before)
            } catch (cleanup: IOException) {
                // 截不掉也无妨：读日志时撕断的行本来就会被忽略
            }
            throw e
        }
    }

    fun close() {
        try {
            file.close()
        } catch (e: IOException) {
            // 关不上也没有更多能做的
        }
    }
}
