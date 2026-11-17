package app.auri.asr

import app.auri.asr.contract.AsrCapabilities
import app.auri.asr.contract.AsrError
import app.auri.asr.contract.ContractJson
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 把一次转录结果写成合同 JSON 文件，让合同校验（Python + 真实 contracts/schemas/）有东西可验。
 * 正文只会出现在 revision-content.json；错误文件不含正文、路径或堆栈。
 *
 * 文件：capabilities.json、task-handle.json、trace.json（转录状态机的事件序列），
 * 以及按结果二选一的 revision-content.json 或 error.json。
 * 引擎内部失败在合同里没有错误码（见 README 的合同缺口），那种情况只有轨迹、没有 error.json。
 */
object ContractOutput {
    fun write(dir: File, capabilities: AsrCapabilities, result: TranscriptionResult) {
        dir.mkdirs()
        // 目录可能被复用：先清掉上一次留下的、互斥的结果文件，免得口述正文在失败之后还躺在目录里，
        // 或者一个目录里同时有“成功”与“出错”两种结论。deleteIfExists 删不掉就抛异常，不静默留着。
        listOf("revision-content.json", "error.json").forEach { Files.deleteIfExists(File(dir, it).toPath()) }
        File(dir, "capabilities.json").writeUtf8(ContractJson.encode(capabilities))
        File(dir, "task-handle.json").writeUtf8(ContractJson.encode(result.job))
        File(dir, "trace.json").writeUtf8(ContractJson.encode(trace(result)))
        when (val outcome = result.outcome) {
            is TranscriptionOutcome.Succeeded -> File(dir, "revision-content.json").writeUtf8(ContractJson.encode(outcome.content))
            is TranscriptionOutcome.NoSpeech -> writeError(dir, outcome.error)
            is TranscriptionOutcome.WaitingModel -> writeError(dir, outcome.error)
            is TranscriptionOutcome.Failed -> outcome.error?.let { writeError(dir, it) }
        }
    }

    private fun writeError(dir: File, error: AsrError) = File(dir, "error.json").writeUtf8(ContractJson.encode(error))

    private fun trace(result: TranscriptionResult) = TraceFile(
        machine = "transcription",
        initialState = TranscriptionState.QUEUED.name,
        steps = result.steps.map { TraceFileStep(it.event.name, it.stateAfter.name) },
    )

    private fun File.writeUtf8(text: String) = writeText(text, Charsets.UTF_8)

    @Serializable
    private data class TraceFile(
        val machine: String,
        @SerialName("initial_state") val initialState: String,
        val steps: List<TraceFileStep>,
    )

    @Serializable
    private data class TraceFileStep(
        val event: String,
        @SerialName("state_after") val stateAfter: String,
    )
}
