package app.auri.sync.machine

import app.auri.capture.machine.Outcome
import app.auri.sync.machine.SyncEvent.AUTH_REVOKED
import app.auri.sync.machine.SyncEvent.GATEWAY_REACHABLE
import app.auri.sync.machine.SyncEvent.GATEWAY_UNREACHABLE
import app.auri.sync.machine.SyncEvent.KEY_MISMATCH
import app.auri.sync.machine.SyncEvent.RETRYABLE_FAILURE
import app.auri.sync.machine.SyncEvent.RETRY_DUE
import app.auri.sync.machine.SyncEvent.SEND_STARTED
import app.auri.sync.machine.SyncEvent.STORED_CONFIRMED
import app.auri.sync.machine.SyncEvent.USER_RESOLVED
import app.auri.sync.machine.SyncEvent.VERSION_CONFLICT
import app.auri.sync.machine.SyncState.BLOCKED
import app.auri.sync.machine.SyncState.PENDING
import app.auri.sync.machine.SyncState.RETRY_SCHEDULED
import app.auri.sync.machine.SyncState.SENDING
import app.auri.sync.machine.SyncState.STORED
import app.auri.sync.machine.SyncState.WAITING_GATEWAY

/** contracts/fixtures/state-machines/sync.machine.json 的 states（PRD 第 12.4 节）：手机上一条待同步任务的状态。 */
enum class SyncState { PENDING, SENDING, STORED, WAITING_GATEWAY, RETRY_SCHEDULED, BLOCKED }

/** sync.machine.json 的 events。 */
enum class SyncEvent {
    SEND_STARTED,
    GATEWAY_UNREACHABLE,
    STORED_CONFIRMED,
    RETRYABLE_FAILURE,
    KEY_MISMATCH,
    AUTH_REVOKED,
    VERSION_CONFLICT,
    GATEWAY_REACHABLE,
    RETRY_DUE,
    USER_RESOLVED,
}

data class SyncRule(val from: SyncState, val event: SyncEvent, val outcome: Outcome, val to: SyncState)

/**
 * 同步状态机的转移表，逐条对应 sync.machine.json（SyncMachineTableTest 与仓库里的原始 JSON 对表）。
 * 它只回答“这个事件在这个状态下怎么办”，不做副作用；表里没有的（状态，事件）组合就是被拒绝：状态不变。
 * 不存在“重试次数用尽就放弃”这样的捷径：能让任务离开 BLOCKED 的只有 USER_RESOLVED。
 */
object SyncMachine {
    val initialState: SyncState = PENDING
    val terminalStates: Set<SyncState> = setOf(STORED)

    private fun go(from: SyncState, event: SyncEvent, to: SyncState) = SyncRule(from, event, Outcome.TRANSITION, to)

    private fun ignore(state: SyncState, event: SyncEvent) = SyncRule(state, event, Outcome.IGNORED, state)

    val rules: List<SyncRule> = listOf(
        go(PENDING, SEND_STARTED, SENDING),
        go(PENDING, GATEWAY_UNREACHABLE, WAITING_GATEWAY),
        go(SENDING, STORED_CONFIRMED, STORED),
        go(SENDING, GATEWAY_UNREACHABLE, WAITING_GATEWAY),
        go(SENDING, RETRYABLE_FAILURE, RETRY_SCHEDULED),
        go(SENDING, KEY_MISMATCH, BLOCKED),
        go(SENDING, AUTH_REVOKED, BLOCKED),
        go(SENDING, VERSION_CONFLICT, BLOCKED),
        go(WAITING_GATEWAY, GATEWAY_REACHABLE, PENDING),
        ignore(WAITING_GATEWAY, GATEWAY_UNREACHABLE),
        go(RETRY_SCHEDULED, RETRY_DUE, PENDING),
        go(RETRY_SCHEDULED, GATEWAY_UNREACHABLE, WAITING_GATEWAY),
        go(BLOCKED, USER_RESOLVED, PENDING),
        ignore(STORED, STORED_CONFIRMED),
    )

    private val index: Map<Pair<SyncState, SyncEvent>, SyncRule> = rules.associateBy { it.from to it.event }

    /** 查（状态，事件）的规则；没有规则 = 被拒绝。 */
    fun resolve(state: SyncState, event: SyncEvent): SyncRule? = index[state to event]
}
