package app.auri.sync.policy

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest

/** 读不出来之后第一次重试前等多久；之后每次翻倍，到 [STORED_STATES_RETRY_MAX_MS] 为止（与界面层读发送账失败的重试同一套节奏，见 :app 的 ContainedReads）。 */
internal const val STORED_STATES_RETRY_BASE_MS = 2_000L
internal const val STORED_STATES_RETRY_MAX_MS = 30_000L
private const val MAX_DOUBLINGS = 5

/**
 * [DataFlowPolicy.storedStates] 的实现骨架（复评 R9）：[this]（“存档可能变了”的信号，订阅那一刻也算）每发一次就读一次 [read]。
 * 读出来了就发出读到的值；读不出来（[read] 回答 null）**立刻**发出 [StoredDataFlow.Closed]（不许），然后按 2 秒起翻倍到 30 秒封顶的节奏重试，
 * 读到了就发出读到的值——不能停在 Closed 上等一次不会来的变动：数据库只是暂时出了故障，用户的授权其实好好地存着。
 * 又来了新的变动就丢掉旧的重试、从头读；收集者取消，重试跟着停。读本身不能吞掉取消（[read] 的实现要把取消往外抛）。
 */
fun Flow<*>.recoveringStoredStates(read: suspend () -> StoredDataFlow?): Flow<StoredDataFlow> = channelFlow {
    collectLatest {
        var failures = 0
        while (true) {
            val stored = read()
            if (stored != null) {
                send(stored)
                return@collectLatest
            }
            if (failures == 0) send(StoredDataFlow.Closed)
            delay((STORED_STATES_RETRY_BASE_MS shl failures.coerceAtMost(MAX_DOUBLINGS)).coerceAtMost(STORED_STATES_RETRY_MAX_MS))
            failures += 1
        }
    }
}
