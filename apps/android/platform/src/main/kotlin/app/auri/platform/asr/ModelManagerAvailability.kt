package app.auri.platform.asr

import app.auri.capture.contract.ErrorCode
import app.auri.capture.scheduler.ModelAvailability
import app.auri.capture.scheduler.ModelReadiness
import app.auri.modelmgmt.ActiveModelStatus
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 模型管理器的启用状态 → 调度器的“本地转录模型能不能用”。
 * “代”原样带过去：同一个版本重装、修好之后也是新的一代，调度器据此放行等模型的任务。
 */
class ModelManagerAvailability(active: StateFlow<ActiveModelStatus>) : ModelAvailability {
    override val readiness: StateFlow<ModelReadiness> = Mapped(active)

    // 继承 StateFlow 在 kotlinx-coroutines 里标为不稳定（以后可能新增方法）；版本锁定在 1.9.0，升级时由编译失败提醒
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class Mapped(private val source: StateFlow<ActiveModelStatus>) : StateFlow<ModelReadiness> {
        override val value: ModelReadiness get() = source.value.toReadiness()

        override val replayCache: List<ModelReadiness> get() = listOf(value)

        // StateFlow 的 collect 永不返回；相邻相同的值不重复发（损坏的模型换个名字上报也还是“损坏”）
        override suspend fun collect(collector: FlowCollector<ModelReadiness>): Nothing {
            source.map { it.toReadiness() }.distinctUntilChanged().collect(collector)
            awaitCancellation()
        }
    }
}

internal fun ActiveModelStatus.toReadiness(): ModelReadiness = when (this) {
    ActiveModelStatus.NotInstalled -> ModelReadiness.Missing(ErrorCode.MODEL_NOT_INSTALLED)
    is ActiveModelStatus.Damaged -> ModelReadiness.Missing(ErrorCode.MODEL_INTEGRITY_FAILED)
    is ActiveModelStatus.Ready -> ModelReadiness.Ready(model.generation)
}
