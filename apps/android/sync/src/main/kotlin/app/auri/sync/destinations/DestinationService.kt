package app.auri.sync.destinations

import app.auri.capture.contract.ErrorCode
import app.auri.capture.coordinator.Clock
import app.auri.sync.SyncLog
import app.auri.sync.discovery.GatewayLocator
import app.auri.sync.discovery.Located
import app.auri.sync.engine.SyncJobStore
import app.auri.sync.gateway.CreateDestinationRequest
import app.auri.sync.gateway.DeliveryOnGateway
import app.auri.sync.gateway.Destination
import app.auri.sync.gateway.GatewayClient
import app.auri.sync.gateway.GatewayResult
import app.auri.sync.gateway.IssuedCredential
import app.auri.sync.gateway.Publication
import app.auri.sync.http.HttpExchangeFactory
import app.auri.sync.http.TransportFailure
import app.auri.sync.machine.SyncState
import app.auri.sync.pairing.GatewayAddress
import app.auri.sync.pairing.NeedsUser
import app.auri.sync.pairing.PairedGateway
import app.auri.sync.pairing.PairingState
import app.auri.sync.pairing.PairingStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex

/** 管理 Destination 的操作没做成的原因。每一种界面要说不同的话，所以不合并。 */
sealed interface ServiceFailure {
    /** 还没配对（或配对记录丢了）：先去配对。 */
    data object NotPaired : ServiceFailure

    /** 连接本身要用户处理：公钥不符（要重新配对）或令牌被拒。和同步页是同一件事。 */
    data class NeedsRepair(val needs: NeedsUser) : ServiceFailure

    /** 这会儿连不上网关（不在同一网络、网关没开）。请求一定没有发出。 */
    data object Unreachable : ServiceFailure

    /** 网关明确拒绝。[code] 是合同错误码，没认出来为 null。 */
    data class Rejected(val status: Int, val code: ErrorCode?, val retryable: Boolean) : ServiceFailure

    /**
     * 网关回了 2xx，但内容读不懂或不是我们要的那个。**请求已经送到**：创建/撤销/签发可能已经生效，
     * 界面必须说“结果不确定”，让用户先刷新列表确认，不能叫人直接重试（创建没有幂等键，重试会多出一个同名 Destination）。
     */
    data object Unreadable : ServiceFailure

    /** 请求可能已发出，但没拿到完整回答：同 [Unreadable]，结果不确定。 */
    data object Interrupted : ServiceFailure

    /** 本机缓存里没有这个 Destination，或它已被撤销：不去问网关（更不弹指纹）。 */
    data object DestinationUnavailable : ServiceFailure

    /** 上一次生成/轮换凭证还没结束：不开第二个（否则双击会连着轮换两次，刚拿到的凭证立刻作废）。 */
    data object Busy : ServiceFailure

    /** 有效的 Destination 已经有 [Publication.MAX_DESTINATIONS] 个：合同限制一条文字最多投给这么多个，再多就没法都发了。请先撤销用不到的。请求没有发出。 */
    data object LimitReached : ServiceFailure
}

sealed interface ServiceResult<out T> {
    data class Ok<T>(val value: T) : ServiceResult<T>

    data class Failed(val failure: ServiceFailure) : ServiceResult<Nothing>
}

/** 生成/轮换凭证的结局。只有 [Issued] 带着凭证；核验没过时一个请求都没有发给网关。 */
sealed interface CredentialOutcome {
    data class Issued(val credential: IssuedCredential) : CredentialOutcome

    /** 本机身份核验没通过或做不了（[result] 一定不是 [AuthResult.Authenticated]）。 */
    data class NotAuthenticated(val result: AuthResult) : CredentialOutcome

    data class Failed(val failure: ServiceFailure) : CredentialOutcome
}

/** 界面（视图模型）用的 Destination 管理入口。 */
interface DestinationController {
    /** 本机缓存的 Destination（含已撤销的），缓存每变一次重新发出。读它不联网。 */
    val destinations: Flow<List<Destination>>

    /** 向网关要完整列表并更新缓存。 */
    suspend fun refresh(): ServiceResult<List<Destination>>

    /** 创建：[CreateDestinationRequest] 没有默认参数，所以调用方一定显式选过发送方式。 */
    suspend fun create(request: CreateDestinationRequest): ServiceResult<Destination>

    suspend fun revoke(destinationId: String): ServiceResult<Destination>

    /** 生成或轮换凭证：先本机核验，通过了才联网。凭证只在返回值里出现这一次。 */
    suspend fun issueCredential(destinationId: String): CredentialOutcome

    /** 这个 Destination 上最近 [limit] 条文字各自的情形（已入库的会去网关问状态）。 */
    suspend fun deliveries(destinationId: String, limit: Int): List<ItemDelivery>
}

/**
 * [DestinationController] 的实现：和同步引擎走同一条路——配对记录里的令牌与公钥、[GatewayLocator] 找地址、固定公钥的 [GatewayClient]。
 * 没有第二套网络栈。
 *
 * - 日志只写事件名与异常类名：没有凭证、令牌、Destination 名字与编号、地址。
 * - 生成/轮换凭证：**每次**现场核验（[LocalAuthenticator]），通过才联网；核验没过则零请求。凭证不进缓存、不进日志、不落盘。
 * - 缓存（[DispatchStore]）里只有 Destination 的公开信息（名字、模式、意图、是否已签发过凭证）。
 * - 连接问题（公钥不符、令牌被拒）只回报给调用方，不改写配对记录：那是同步引擎的职责，它下一次尝试会自己发现并记下。
 */
class DestinationService(
    private val pairing: PairingStore,
    private val http: HttpExchangeFactory,
    private val locator: GatewayLocator,
    private val dispatch: DispatchStore,
    private val jobs: SyncJobStore,
    private val authenticator: LocalAuthenticator,
    private val clock: Clock,
    private val log: SyncLog = SyncLog.None,
) : DestinationController {
    private val issuing = Mutex()

    override val destinations: Flow<List<Destination>> = dispatch.changes().onStart { emit(0L) }.map { dispatch.destinations() }

    override suspend fun refresh(): ServiceResult<List<Destination>> = gatewayCall(
        "refresh",
        request = { it.client.listDestinations(it.address, it.gateway.token) },
        apply = { session, list -> dispatch.applyDestinations(session.gateway.gatewayId, list, authoritative = true, now = clock.now()) },
    )

    override suspend fun create(request: CreateDestinationRequest): ServiceResult<Destination> {
        // 一条文字最多投给 50 个 Destination（合同）：再建就有 Destination 永远收不到文字，不如在这里明说。按本机缓存算，请求没有发出
        if (dispatch.destinations().count { it.active } >= Publication.MAX_DESTINATIONS) return ServiceResult.Failed(ServiceFailure.LimitReached)
        return createOnGateway(request)
    }

    private suspend fun createOnGateway(request: CreateDestinationRequest): ServiceResult<Destination> = gatewayCall(
        "create",
        request = { it.client.createDestination(it.address, it.gateway.token, request) },
        apply = { session, created -> dispatch.applyDestinations(session.gateway.gatewayId, listOf(created), authoritative = false, now = clock.now()) },
    )

    override suspend fun revoke(destinationId: String): ServiceResult<Destination> = gatewayCall(
        "revoke",
        request = { it.client.revokeDestination(it.address, it.gateway.token, destinationId) },
        apply = { session, revoked -> dispatch.applyDestinations(session.gateway.gatewayId, listOf(revoked), authoritative = false, now = clock.now()) },
    )

    override suspend fun issueCredential(destinationId: String): CredentialOutcome {
        if (!issuing.tryLock()) return CredentialOutcome.Failed(ServiceFailure.Busy)
        try {
            // 能提前知道要失败的，在弹指纹之前就说：没配对、连接坏了、没有这个 Destination 或已撤销
            val gateway = when (val state = pairing.load()) {
                is PairingState.Paired -> state.gateway
                else -> return CredentialOutcome.Failed(ServiceFailure.NotPaired)
            }
            gateway.needsUser?.let { return CredentialOutcome.Failed(ServiceFailure.NeedsRepair(it)) }
            val known = dispatch.destinations().firstOrNull { it.destinationId == destinationId }
            if (known == null || !known.active) return CredentialOutcome.Failed(ServiceFailure.DestinationUnavailable)

            val purpose = if (known.credentialIssued) AuthPurpose.ROTATE_CREDENTIAL else AuthPurpose.ISSUE_CREDENTIAL
            val auth = authenticator.authenticate(purpose)
            if (auth != AuthResult.Authenticated) {
                log.event("credential.auth ${auth::class.simpleName}")
                return CredentialOutcome.NotAuthenticated(auth)
            }

            val session = when (val connected = connect()) {
                is Connect.Ready -> connected.session
                is Connect.Blocked -> return CredentialOutcome.Failed(connected.failure)
            }
            val result = session.client.issueCredential(session.address, session.gateway.token, destinationId)
            if (!stillPaired(session.gateway)) return CredentialOutcome.Failed(ServiceFailure.NotPaired)
            return when (result) {
                is GatewayResult.Success -> {
                    dispatch.applyDestinations(session.gateway.gatewayId, listOf(known.copy(credentialIssued = true)), authoritative = false, now = clock.now())
                    log.event("credential.issued replaced=${result.value.replacedPrevious}")
                    CredentialOutcome.Issued(result.value)
                }

                else -> CredentialOutcome.Failed(result.failure()).also { log.event("credential.failed ${result::class.simpleName}") }
            }
        } finally {
            issuing.unlock()
        }
    }

    override suspend fun deliveries(destinationId: String, limit: Int): List<ItemDelivery> {
        val items = dispatch.itemsFor(destinationId, limit)
        // 只有“已入库”、而且本机的账说不清的才需要问网关（用户跳过的、还在等用户的、网关拒绝建的，账上就是答案）；一条 Record 只问一次
        val needGateway = items.filter { item ->
            jobs.get(item.key)?.state == SyncState.STORED && item.targets.first { it.destinationId == destinationId }.needsGatewayAnswer()
        }.map { it.key.recordId }.distinct()
        val remote = if (needGateway.isEmpty()) emptyMap() else askGateway(needGateway)
        return items.map { item ->
            val job = jobs.get(item.key)
            val target = item.targets.first { it.destinationId == destinationId }
            ItemDelivery(item, viewOf(target, job?.state, remote[item.key.recordId], item.key.revision, destinationId))
        }
    }

    // ---- 状态怎么算 ----

    /** 向网关问的结果：Record → 它的投递列表；问不到的整条 Record 记成 [Remote.Unusable]。 */
    private sealed interface Remote {
        data class Answer(val deliveries: List<DeliveryOnGateway>) : Remote

        data class Unusable(val reason: UnknownReason) : Remote
    }

    private suspend fun askGateway(recordIds: List<String>): Map<String, Remote> {
        val session = when (val connected = connect()) {
            is Connect.Ready -> connected.session
            is Connect.Blocked -> return recordIds.associateWith { Remote.Unusable(UnknownReason.GATEWAY_UNREACHABLE) }
        }
        return recordIds.associateWith { recordId ->
            when (val result = session.client.recordStatus(session.address, session.gateway.token, recordId)) {
                is GatewayResult.Success -> Remote.Answer(result.value.destinations)
                is GatewayResult.TransportFailed -> Remote.Unusable(UnknownReason.GATEWAY_UNREACHABLE)
                is GatewayResult.Rejected, is GatewayResult.Garbled -> Remote.Unusable(UnknownReason.GATEWAY_ANSWER_UNUSABLE)
            }
        }
    }

    private fun viewOf(target: DispatchTarget, jobState: SyncState?, remote: Remote?, revision: Int, destinationId: String): DeliveryView {
        // 账上就说得清的：用户跳过了、还在等用户（文字可能已经在网关，但没有给它的 Delivery）、网关拒绝建
        if (target.decision == TargetDecision.SKIPPED) return DeliveryView.Skipped
        if (target.awaitingUser) return DeliveryView.AwaitingYou
        if (target.delivery == GatewayDelivery.REFUSED) return DeliveryView.Unknown(UnknownReason.DELIVERY_REFUSED)
        if (jobState == SyncState.STORED) {
            // 已经入库：网关怎么说就怎么显示（包括它把已撤销 Destination 上的投递取消了）
            when (remote) {
                null -> return DeliveryView.Unknown(UnknownReason.GATEWAY_UNREACHABLE)
                is Remote.Unusable -> return DeliveryView.Unknown(remote.reason)
                is Remote.Answer -> {
                    val delivery = remote.deliveries.firstOrNull { it.destinationId == destinationId && it.revision == revision }
                    if (delivery != null) {
                        val stage = GatewayStage.fromGateway(delivery.state) ?: return DeliveryView.Unknown(UnknownReason.UNRECOGNIZED_STATE)
                        return DeliveryView.OnGateway(stage)
                    }
                    // 网关没有投递记录：本机没打算发给它（撤销了、不接收这种意图）是对的；
                    // 打算发而账上还没记下网关有（刚确认、追加还没做成）是“正在请网关追加”；账上说有而网关没有，是个该说明白的缺口
                    return when {
                        !target.willReceive -> DeliveryView.NotTargeted
                        target.delivery == GatewayDelivery.CREATED -> DeliveryView.Unknown(UnknownReason.NO_DELIVERY_RECORD)
                        else -> DeliveryView.Queued(QueuedPhase.REQUESTING_DELIVERY)
                    }
                }
            }
        }
        // 还没入库（有任务但没入库，或还没有任务）：只有本来就要发给它的才算“排队中”
        if (!target.willReceive) return DeliveryView.NotTargeted
        return DeliveryView.Queued(jobState?.let(QueuedPhase::of) ?: QueuedPhase.SENDING)
    }

    private fun DispatchTarget.needsGatewayAnswer(): Boolean =
        decision != TargetDecision.SKIPPED && !awaitingUser && delivery != GatewayDelivery.REFUSED

    // ---- 连接 ----

    private class Session(val gateway: PairedGateway, val address: GatewayAddress, val client: GatewayClient)

    private sealed interface Connect {
        data class Ready(val session: Session) : Connect

        data class Blocked(val failure: ServiceFailure) : Connect
    }

    private suspend fun connect(): Connect {
        val gateway = when (val state = pairing.load()) {
            is PairingState.Paired -> state.gateway
            else -> return Connect.Blocked(ServiceFailure.NotPaired)
        }
        gateway.needsUser?.let { return Connect.Blocked(ServiceFailure.NeedsRepair(it)) }
        return when (val located = locator.locate(gateway)) {
            is Located.Reachable -> Connect.Ready(Session(gateway, located.address, GatewayClient(http.forPin(gateway.pin))))
            Located.Unreachable -> Connect.Blocked(ServiceFailure.Unreachable)
            Located.KeyMismatch -> Connect.Blocked(ServiceFailure.NeedsRepair(NeedsUser.KEY_MISMATCH))
        }
    }

    /** 请求途中用户重新配对了：旧配对的回答不能写进新配对的缓存。 */
    private suspend fun stillPaired(gateway: PairedGateway): Boolean {
        val current = (pairing.load() as? PairingState.Paired)?.gateway ?: return false
        return current.gatewayId == gateway.gatewayId && current.deviceId == gateway.deviceId && current.pairedAt == gateway.pairedAt && current.pin == gateway.pin
    }

    /**
     * 一次管理操作：连上、发请求、**确认还是同一个配对**之后才把回答写进缓存（请求途中重新配对了，旧网关的回答不能写进新网关的缓存）。
     */
    private suspend fun <T> gatewayCall(
        name: String,
        request: suspend (Session) -> GatewayResult<T>,
        apply: suspend (Session, T) -> Unit,
    ): ServiceResult<T> {
        val session = when (val connected = connect()) {
            is Connect.Ready -> connected.session
            is Connect.Blocked -> return ServiceResult.Failed(connected.failure)
        }
        val result = request(session)
        if (!stillPaired(session.gateway)) return ServiceResult.Failed(ServiceFailure.NotPaired)
        return when (result) {
            is GatewayResult.Success -> {
                apply(session, result.value)
                log.event("destination.$name ok")
                ServiceResult.Ok(result.value)
            }

            else -> {
                val failure = result.failure()
                log.event("destination.$name failed ${failure::class.simpleName}")
                ServiceResult.Failed(failure)
            }
        }
    }

    private fun GatewayResult<*>.failure(): ServiceFailure = when (this) {
        is GatewayResult.Success<*> -> error("failure() 只处理失败的结果")
        is GatewayResult.Garbled -> ServiceFailure.Unreadable
        is GatewayResult.TransportFailed -> when (failure) {
            is TransportFailure.Unreachable -> ServiceFailure.Unreachable
            is TransportFailure.KeyMismatch -> ServiceFailure.NeedsRepair(NeedsUser.KEY_MISMATCH)
            is TransportFailure.Interrupted -> ServiceFailure.Interrupted
        }

        is GatewayResult.Rejected ->
            if (status == 401 || code == ErrorCode.GATEWAY_AUTH_REVOKED || code == ErrorCode.GATEWAY_AUTH_INVALID) ServiceFailure.NeedsRepair(NeedsUser.AUTH_REJECTED)
            else ServiceFailure.Rejected(status, code, retryable)
    }
}
