package app.auri.android.modelui

import app.auri.asr.manifest.Redistribution
import app.auri.capture.contract.ErrorCode
import app.auri.capture.scheduler.ResourceReason
import app.auri.modelmgmt.ModelError
import app.auri.modelmgmt.ModelErrorKind
import app.auri.modelmgmt.Transfer
import java.util.Locale

// 界面上的文字：纯函数，不依赖 Compose，所以能在 JVM 里测。每一种失败与等待都给一句能照着做的话。

internal fun formatBytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)

internal fun describeRedistribution(redistribution: Redistribution): String = when (redistribution) {
    Redistribution.CONFIRMED -> "许可方已确认可以再分发"
    Redistribution.UNCONFIRMED -> "再分发未确认（只用于本机评测，不随应用分发）"
}

internal fun describeModelError(error: ModelError): String = when (error.kind) {
    ModelErrorKind.NO_NETWORK -> "没有网络：已下载的部分保留，联网后点“继续”"
    ModelErrorKind.NOT_ENOUGH_SPACE ->
        "存储空间不够" + (if (error.neededBytes != null && error.availableBytes != null) {
            "：需要 ${formatBytes(error.neededBytes!!)}，现有 ${formatBytes(error.availableBytes!!)}，腾出空间后重试"
        } else {
            "，腾出空间后重试"
        })
    ModelErrorKind.HASH_MISMATCH -> "下载的内容与清单固定的哈希不符，已丢弃，不会启用；请重新下载"
    ModelErrorKind.SERVER_ERROR -> "服务器返回了错误" + (error.httpStatus?.let { "（HTTP $it）" } ?: "") + "，稍后重试"
    ModelErrorKind.STORAGE_ERROR -> "本机存储读写失败"
    ModelErrorKind.ARCHIVE_INVALID -> "压缩包解不开，或缺少清单要求的文件；请重新下载"
}

internal fun describeWaitResources(reason: ResourceReason?, needsUser: Boolean): String {
    val why = when (reason) {
        ResourceReason.RECORDING_ACTIVE -> "正在录音，录完再转"
        ResourceReason.THERMAL -> "手机过热，降温后继续"
        ResourceReason.BATTERY_SAVER -> "省电模式开着，关掉省电模式或充电后继续"
        ResourceReason.LOW_MEMORY -> "可用内存不足，关掉别的应用后继续"
        ResourceReason.LOW_STORAGE -> "存储空间不足，腾出空间后继续"
        null -> "资源不足（原因未知）"
    }
    return if (needsUser) "$why；已经多次重查仍不行，不再自动重试，请处理后点“重新检查”" else why
}

internal fun describeModelWait(code: ErrorCode?): String = when (code) {
    ErrorCode.MODEL_INTEGRITY_FAILED -> "转录模型没通过完整性检查，请重新下载；音频已保留"
    else -> "转录模型还没装好，装好后自动继续；音频已保留"
}

internal fun describeJob(status: JobStatus): String = when (status) {
    JobStatus.Queued -> "排队中"
    JobStatus.Running -> "转录中…"
    is JobStatus.WaitingModel -> "等待转录模型：" + describeModelWait(status.code)
    is JobStatus.WaitingResources -> "等待：" + describeWaitResources(status.reason, status.needsUser)
    is JobStatus.RetryScheduled -> "转录没成功，已安排重试（已试 ${status.attempts} 次）"
    is JobStatus.Succeeded ->
        "已转出文字" + (status.revision?.let { "（第 $it 条 Revision" } ?: "（") +
            (status.modelId?.let { "，模型 $it ${status.modelVersion.orEmpty()}" } ?: "，模型未知") + "）"
    JobStatus.NoSpeech -> "没有识别出语音：没有生成文字"
    is JobStatus.Failed -> "转录失败" + (status.code?.let { "（$it）" } ?: "") + "，音频已保留，可以重新转录"
    JobStatus.Cancelled -> "已取消"
    is JobStatus.Unsupported -> "不支持的状态（${status.state}）"
}

internal fun describeStart(action: StartAction): String = when (action) {
    StartAction.DOWNLOAD -> "下载"
    StartAction.RESUME -> "继续"
    StartAction.UPDATE -> "更新"
    StartAction.REPAIR -> "重新下载"
    StartAction.RETRY -> "重试"
}

private fun progress(done: Long, total: Long) = "${formatBytes(done)} / ${formatBytes(total)}" + if (total > 0) "（${done * 100 / total}%）" else ""

/** 传输状态一行字；空闲时没有。 */
internal fun describeTransfer(transfer: Transfer): String? = when (transfer) {
    Transfer.Idle -> null
    is Transfer.Downloading -> "下载中：${progress(transfer.bytesDone, transfer.bytesTotal)}"
    is Transfer.Paused -> "已暂停：${progress(transfer.bytesDone, transfer.bytesTotal)}；点“继续”从这里接着下载"
    Transfer.Installing -> "已下载完，正在校验哈希、解包、再校验；全部通过才会启用…"
    is Transfer.Failed -> "失败：" + describeModelError(transfer.error)
}

internal fun describeInstalled(row: ModelRow): String = when {
    row.installedVersion == null -> "未安装"
    row.damaged -> "已装的 ${row.installedVersion} 没通过完整性检查，不会被使用"
    row.updateAvailable -> "已装 ${row.installedVersion}，有新版本 ${row.version} 可更新（更新完成之前旧版本继续可用）"
    else -> "已安装，通过校验：${row.installedVersion}"
}

internal fun describeActiveModel(fact: ActiveModelFact): String = when (fact) {
    ActiveModelFact.NotInstalled -> "还没有可用的转录模型：新录音会先保存，装好模型后自动转录"
    is ActiveModelFact.Damaged -> "已装的模型 ${fact.modelId} 没通过完整性检查，不会被使用：请重新下载；新录音会先保存"
    is ActiveModelFact.Ready -> "转录现在用的模型：${fact.modelId} ${fact.modelVersion}"
}
