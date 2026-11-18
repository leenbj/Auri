package app.auri.platform.asr

import app.auri.asr.LocalTranscriber
import app.auri.asr.engine.AsrEngineFactory
import app.auri.asr.manifest.ModelManifest
import app.auri.capture.adapter.TranscriptionAdapter
import app.auri.capture.adapter.TranscriptionOutcome
import app.auri.capture.adapter.TranscriptionRequest
import app.auri.capture.adapter.TranscriptionResult
import app.auri.capture.contract.AsrCapabilities
import app.auri.capture.contract.AsrTaskHandle
import app.auri.capture.contract.ErrorCode
import app.auri.modelmgmt.ActiveModel
import app.auri.modelmgmt.ActiveModelStatus
import java.io.File
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** 一个创建好的引擎，和用完要关的东西（生产里它们是同一个 [LocalTranscriber]）。 */
class LoadedEngine(val engine: LocalSpeechEngine, val closer: AutoCloseable)

/** 给一份启用的模型创建引擎：只创建 [LocalTranscriber]（它在第一次转录时才校验与加载模型，所以这里不碰磁盘、不加载原生库）。 */
fun loadLocalEngine(model: ActiveModel, factory: AsrEngineFactory): LoadedEngine {
    val transcriber = LocalTranscriber(model.manifest, model.dir, factory)
    return LoadedEngine(transcriber.asSpeechEngine(), transcriber)
}

/** 清单声明的转录能力：只读，不碰磁盘，不创建引擎。 */
fun ModelManifest.declaredCapabilities(): AsrCapabilities =
    LocalTranscriber(this, File(""), AsrEngineFactory { error("只读能力声明，不创建引擎") }).use { it.capabilities.toCapture() }

/**
 * 转录适配器：跟着模型管理器“当前启用的模型”走（Ticket 20）。
 *  - 没装、或已判为损坏：不创建引擎、不碰任何目录，直接回“等模型”（调度器本来就不会在这时放行任务，这是第二道门）；
 *  - 装好了：按模型的“代”创建引擎并复用；换代后等旧引擎的使用者全部返回，再在适配器线程关闭它；
 *  - 引擎加载前的校验器拒绝了这个模型（MODEL_INTEGRITY_FAILED）：把出事的那一份 [ActiveModel] 上报给 [onIntegrityFailure]，
 *    模型管理器据此判为损坏；上报带着“代”，所以模型已被换掉时迟到的上报不会冤枉新模型；
 *  - 没有云端回退：这里只有本地引擎。创建引擎抛异常、适配器已关闭、上报方出错，都收敛成结果，不往上抛。
 *
 * [fallbackCapabilities] 只在没有可用模型时回答“能不能中途停、同时转几个”，不会出现在 Revision 里
 * （Revision 的模型名只取自真正转出文字的引擎）。
 */
class ActiveModelTranscriptionAdapter(
    private val active: StateFlow<ActiveModelStatus>,
    private val fallbackCapabilities: AsrCapabilities,
    private val onIntegrityFailure: (ActiveModel) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val loadEngine: (ActiveModel) -> LoadedEngine,
) : TranscriptionAdapter, AutoCloseable {
    private class Current(val model: ActiveModel, val loaded: LoadedEngine) {
        var users = 0
        var retired = false
        var closeScheduled = false
    }

    private val lock = Any()
    private var current: Current? = null
    private var closed = false

    override suspend fun capabilities(): AsrCapabilities {
        while (true) {
            val status = active.value as? ActiveModelStatus.Ready ?: return fallbackCapabilities
            val lease = status.model.acquire() ?: continue
            var borrowed: Current? = null
            return try {
                borrow(status.model).also { borrowed = it }.loaded.engine.capabilities.toCapture()
            } catch (e: Exception) {
                fallbackCapabilities
            } finally {
                borrowed?.let(::release)
                lease.close()
            }
        }
    }

    override suspend fun transcribe(request: TranscriptionRequest): TranscriptionResult = withContext(dispatcher) { transcribeActive(request) }

    private suspend fun transcribeActive(request: TranscriptionRequest): TranscriptionResult {
        while (true) {
            when (val status = active.value) {
                ActiveModelStatus.NotInstalled -> return outcomeOnly(TranscriptionOutcome.WaitingModel(ErrorCode.MODEL_NOT_INSTALLED))
                is ActiveModelStatus.Damaged -> return outcomeOnly(TranscriptionOutcome.WaitingModel(ErrorCode.MODEL_INTEGRITY_FAILED))
                is ActiveModelStatus.Ready -> {
                    val lease = status.model.acquire() ?: continue
                    try {
                        val result = run(status.model, request)
                        return TranscriptionResult(result.job, result.capabilities, result.outcome, status.model.generation)
                    } finally {
                        lease.close()
                    }
                }
            }
        }
    }

    private suspend fun run(model: ActiveModel, request: TranscriptionRequest): TranscriptionResult {
        val borrowed = try {
            borrow(model)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return outcomeOnly(TranscriptionOutcome.Failed(null))
        }
        try {
            val result = LocalTranscriptionAdapter(borrowed.loaded.engine, dispatcher).transcribe(request)
            val outcome = result.outcome
            if (outcome is TranscriptionOutcome.WaitingModel && outcome.code == ErrorCode.MODEL_INTEGRITY_FAILED) {
                try {
                    onIntegrityFailure(model)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 上报方出错不能吞掉转录结果：调度器仍要知道这次是“等模型”
                }
            }
            return result
        } finally {
            release(borrowed)
        }
    }

    private fun borrow(model: ActiveModel): Current {
        var stale: LoadedEngine? = null
        try {
            return synchronized(lock) {
                check(!closed) { "转录适配器已关闭" }
                val existing = current
                val selected = if (existing != null && existing.model.generation == model.generation) existing else {
                    current = null
                    if (existing != null) stale = retire(existing)
                    Current(model, loadEngine(model)).also { current = it }
                }
                selected.users += 1
                selected
            }
        } finally {
            stale?.closeLater()
        }
    }

    override fun close() {
        val toClose = synchronized(lock) {
            closed = true
            current?.let(::retire).also { current = null }
        }
        toClose?.closeLater()
    }

    /** 在 lock 内调用，确保每个引擎只安排一次关闭。 */
    private fun retire(engine: Current): LoadedEngine? {
        engine.retired = true
        return closable(engine)
    }

    private fun closable(engine: Current): LoadedEngine? {
        if (!engine.retired || engine.users != 0 || engine.closeScheduled) return null
        engine.closeScheduled = true
        return engine.loaded
    }

    private fun release(engine: Current) {
        val toClose = synchronized(lock) {
            engine.users -= 1
            closable(engine)
        }
        toClose?.closeLater()
    }

    private fun LoadedEngine.closeLater() {
        dispatcher.dispatch(EmptyCoroutineContext, Runnable { closeQuietly() })
    }

    private fun LoadedEngine.closeQuietly() {
        try {
            closer.close()
        } catch (e: Exception) {
            // 关不掉也没有别的办法：引擎已经摘掉，不再被用
        }
    }

    private fun outcomeOnly(outcome: TranscriptionOutcome) = TranscriptionResult(
        AsrTaskHandle(UUID.randomUUID().toString(), "local-${fallbackCapabilities.engine}", null, 1),
        fallbackCapabilities,
        outcome,
    )
}
