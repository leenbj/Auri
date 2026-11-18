package app.auri.capture.machine

import app.auri.capture.machine.TranscriptionEvent.CANCEL
import app.auri.capture.machine.TranscriptionEvent.MODEL_MISSING
import app.auri.capture.machine.TranscriptionEvent.MODEL_READY
import app.auri.capture.machine.TranscriptionEvent.PERMANENT_ERROR
import app.auri.capture.machine.TranscriptionEvent.RESOURCES_LOW
import app.auri.capture.machine.TranscriptionEvent.RESOURCES_OK
import app.auri.capture.machine.TranscriptionEvent.RESULT_NO_SPEECH
import app.auri.capture.machine.TranscriptionEvent.RESULT_TEXT
import app.auri.capture.machine.TranscriptionEvent.RETRYABLE_ERROR
import app.auri.capture.machine.TranscriptionEvent.RETRY_DUE
import app.auri.capture.machine.TranscriptionEvent.RUN_STARTED
import app.auri.capture.machine.TranscriptionState.CANCELLED
import app.auri.capture.machine.TranscriptionState.FAILED
import app.auri.capture.machine.TranscriptionState.NO_SPEECH
import app.auri.capture.machine.TranscriptionState.QUEUED
import app.auri.capture.machine.TranscriptionState.RETRY_SCHEDULED
import app.auri.capture.machine.TranscriptionState.RUNNING
import app.auri.capture.machine.TranscriptionState.SUCCEEDED
import app.auri.capture.machine.TranscriptionState.SUBMITTED_REMOTE
import app.auri.capture.machine.TranscriptionState.WAITING_CONSENT
import app.auri.capture.machine.TranscriptionState.WAITING_MODEL
import app.auri.capture.machine.TranscriptionState.WAITING_NETWORK
import app.auri.capture.machine.TranscriptionState.WAITING_RESOURCES

/**
 * contracts/fixtures/state-machines/transcription.machine.json 的 states（PRD 第 12.3 节）。
 * 最后三个是合同保留的云端状态：V1 只做本地转录（ADR-0019），没有任何转移出入它们，调度器永远不会产生它们。
 */
enum class TranscriptionState {
    QUEUED,
    WAITING_MODEL,
    WAITING_RESOURCES,
    RUNNING,
    SUCCEEDED,
    NO_SPEECH,
    RETRY_SCHEDULED,
    FAILED,
    CANCELLED,
    WAITING_NETWORK,
    WAITING_CONSENT,
    SUBMITTED_REMOTE,
}

/** contracts/fixtures/state-machines/transcription.machine.json 的 events。 */
enum class TranscriptionEvent {
    MODEL_MISSING,
    RESOURCES_LOW,
    RUN_STARTED,
    CANCEL,
    MODEL_READY,
    RESOURCES_OK,
    RESULT_TEXT,
    RESULT_NO_SPEECH,
    RETRYABLE_ERROR,
    PERMANENT_ERROR,
    RETRY_DUE,
}

/** 转移表里的一条规则。表里没有的（状态，事件）组合是 [Outcome.REJECTED]：状态不变，迟到的旧结果也属于这一类。 */
data class TranscriptionRule(
    val from: TranscriptionState,
    val event: TranscriptionEvent,
    val outcome: Outcome,
    val to: TranscriptionState,
)

/**
 * 转录任务状态机的转移表，逐条对应 transcription.machine.json（TranscriptionMachineTableTest 与原始 JSON 对表）。
 * 它只回答“这个事件在这个状态下怎么办”，不做任何副作用；发布 Revision、起定时器、写任务库都在调度器里，
 * 而且调度器每个动作之前都先问这张表：表说拒绝，就什么副作用都不会发生。
 *
 * 一次重转录是一个新任务（合同 description）：终态之后只有 CANCEL 被忽略，没有任何出路。
 */
object TranscriptionMachine {
    val initialState: TranscriptionState = QUEUED

    val terminalStates: Set<TranscriptionState> = setOf(SUCCEEDED, NO_SPEECH, FAILED, CANCELLED)

    val reservedStates: Set<TranscriptionState> = setOf(WAITING_NETWORK, WAITING_CONSENT, SUBMITTED_REMOTE)

    private fun go(from: TranscriptionState, event: TranscriptionEvent, to: TranscriptionState) =
        TranscriptionRule(from, event, Outcome.TRANSITION, to)

    private fun ignore(state: TranscriptionState, event: TranscriptionEvent) = TranscriptionRule(state, event, Outcome.IGNORED, state)

    val rules: List<TranscriptionRule> =
        listOf(
            go(QUEUED, MODEL_MISSING, WAITING_MODEL),
            go(QUEUED, RESOURCES_LOW, WAITING_RESOURCES),
            go(QUEUED, RUN_STARTED, RUNNING),
            go(QUEUED, CANCEL, CANCELLED),

            go(WAITING_MODEL, MODEL_READY, QUEUED),
            go(WAITING_MODEL, CANCEL, CANCELLED),

            go(WAITING_RESOURCES, RESOURCES_OK, QUEUED),
            go(WAITING_RESOURCES, CANCEL, CANCELLED),

            go(RUNNING, RESULT_TEXT, SUCCEEDED),
            go(RUNNING, RESULT_NO_SPEECH, NO_SPEECH),
            go(RUNNING, RETRYABLE_ERROR, RETRY_SCHEDULED),
            go(RUNNING, RESOURCES_LOW, WAITING_RESOURCES),
            go(RUNNING, PERMANENT_ERROR, FAILED),
            go(RUNNING, CANCEL, CANCELLED),

            go(RETRY_SCHEDULED, RETRY_DUE, QUEUED),
            go(RETRY_SCHEDULED, CANCEL, CANCELLED),

            ignore(SUCCEEDED, CANCEL),
            ignore(NO_SPEECH, CANCEL),
            ignore(FAILED, CANCEL),
            ignore(CANCELLED, CANCEL),
        )

    private val index: Map<Pair<TranscriptionState, TranscriptionEvent>, TranscriptionRule> = rules.associateBy { it.from to it.event }

    /** 返回这个组合的规则；没有规则（null）就是被拒绝。 */
    fun resolve(state: TranscriptionState, event: TranscriptionEvent): TranscriptionRule? = index[state to event]
}
