package app.auri.sync.destinations

import app.auri.capture.contract.ErrorCode
import app.auri.capture.coordinator.Clock
import app.auri.sync.SyncLog
import app.auri.sync.discovery.GatewayLocator
import app.auri.sync.discovery.Located
import app.auri.sync.engine.Refusal
import app.auri.sync.engine.SyncJobStore
import app.auri.sync.gateway.ContractAnswer
import app.auri.sync.gateway.ContractGuard
import app.auri.sync.gateway.CreateDestinationRequest
import app.auri.sync.gateway.DeliveryOnGateway
import app.auri.sync.gateway.Destination
import app.auri.sync.gateway.GatewayClient
import app.auri.sync.gateway.GatewayResult
import app.auri.sync.gateway.IssuedCredential
import app.auri.sync.http.HttpExchangeFactory
import app.auri.sync.http.TransportFailure
import app.auri.sync.machine.SyncState
import app.auri.sync.pairing.GatewayAddress
import app.auri.sync.pairing.NeedsUser
import app.auri.sync.pairing.PairedGateway
import app.auri.sync.pairing.PairingState
import app.auri.sync.pairing.PairingStore
import app.auri.sync.pairing.clearGatewayTooOld
import app.auri.sync.pairing.markGatewayTooOld
import app.auri.sync.policy.DataFlowPolicy
import app.auri.sync.policy.PolicyGeneration
import app.auri.sync.policy.RequestGate
import app.auri.sync.policy.current
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 管理 Destination 的操作没做成的原因。每一种界面要说不同的话，所以不合并。 */
sealed interface ServiceFailure {
    /** 还没配对（或配对记录丢了）：先去配对。 */
    data object NotPaired : ServiceFailure

    /** 连接本身要用户处理：公钥不符（要重新配对）、令牌被拒，或网关太旧（要先更新网关，不用重新配对；评审 R7）。和同步页是同一件事。 */
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

    /**
     * 网关拒绝创建：有效（未撤销）的 Destination 已经有 50 个（contracts/README.md 的上限规则，评审 M4）。网关在创建事务里数它自己的账，
     * 什么也没创建；本机缓存说了不算（它可能是旧的，别的手机也在建）。请先撤销用不到的。
     */
    data object LimitReached : ServiceFailure

    /**
     * 这是一次**自动**读取（页面打开时自己去读，用户没点），而文字现在不能进网关（没授权给这台网关、“严格仅本地”开着、或设置读不出来）：
     * 一个请求也没发（健康检查、合同版本、列表、状态查询都没有），界面显示的是本机缓存，可能不是最新的。用户点“刷新”才会联系网关（第四轮 F8）。
     */
    data object AutomaticReadsOff : ServiceFailure
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
    /**
     * 本机缓存的 Destination（含已撤销的），缓存每变一次重新发出。读它不联网。
     * 读本机缓存（SQLite）失败时流以异常结束：订阅者要自己包住并重读（视图模型用 containReadFailures），否则异常会逃出它的作用域（评审 H4）。
     */
    val destinations: Flow<List<Destination>>

    /** 向网关要完整列表并更新缓存。用户自己点的“刷新”：不看文字授权（它不带文字）。 */
    suspend fun refresh(): ServiceResult<List<Destination>>

    /**
     * 同 [refresh]，但这是**自动**读（页面打开时自己去读，用户没点）：文字不能进网关时一个请求也不发，回 [ServiceFailure.AutomaticReadsOff]；
     * 能进时每个请求交出去的前一刻再确认一次（授权随时可能被收回），和同步引擎是同一套规矩（第四轮 F8）。
     */
    suspend fun refreshAutomatically(): ServiceResult<List<Destination>>

    /** 创建：[CreateDestinationRequest] 没有默认参数，所以调用方一定显式选过发送方式。 */
    suspend fun create(request: CreateDestinationRequest): ServiceResult<Destination>

    suspend fun revoke(destinationId: String): ServiceResult<Destination>

    /** 生成或轮换凭证：先本机核验，通过了才联网。凭证只在返回值里出现这一次。 */
    suspend fun issueCredential(destinationId: String): CredentialOutcome

    /** 这个 Destination 上最近 [limit] 条文字各自的情形（已入库的会去网关问状态）。用户自己点的“刷新状态”：不看文字授权。 */
    suspend fun deliveries(destinationId: String, limit: Int): List<ItemDelivery>

    /**
     * 同 [deliveries]，但这是**自动**读：文字不能进网关时不问网关，要问网关才说得清的各条是 [UnknownReason.NOT_ASKED]（本机账上说得清的照常给）；
     * 能进时每个状态查询交出去的前一刻再确认一次（第四轮 F8）。
     */
    suspend fun deliveriesAutomatically(destinationId: String, limit: Int): List<ItemDelivery>
}

/**
 * [DestinationController] 的实现：和同步引擎走同一条路——配对记录里的令牌与公钥、[GatewayLocator] 找地址、固定公钥的 [GatewayClient]。
 * 没有第二套网络栈。
 *
 * - 日志只写事件名与异常类名：没有凭证、令牌、Destination 名字与编号、地址。
 * - 生成/轮换凭证：**每次**现场核验（[LocalAuthenticator]），通过才联网；核验没过则零请求。凭证不进缓存、不进日志、不落盘。
 * - 缓存（[DispatchStore]）里只有 Destination 的公开信息（名字、模式、意图、是否已签发过凭证）。
 * - **自动读**（[refreshAutomatically]、[deliveriesAutomatically]：页面自己去读、用户没点）只在文字可以进网关时才联系网关，每个请求各过一次放行（第四轮 F8）；
 *   用户自己发起的（配对、创建、撤销、签发凭证、点“刷新”）不受这条限制。
 * - 连接问题（公钥不符、令牌被拒）只回报给调用方，不改写配对记录：那是同步引擎的职责，它下一次尝试会自己发现并记下。
 * - 唯一的例外是“网关太旧”（评审 R7）：每个管理请求之前先问网关会哪些合同版本（[ContractGuard]，与同步引擎共用），太旧就一个请求都不发；
 *   这个结论是这里问出来的，也是这里确认好了的，所以由这里打上与清掉配对记录上的标记，同步页与管理页说的是同一件事。
 */
class DestinationService(
    private val pairing: PairingStore,
    private val http: HttpExchangeFactory,
    private val locator: GatewayLocator,
    private val dispatch: DispatchStore,
    private val jobs: SyncJobStore,
    private val authenticator: LocalAuthenticator,
    /** 自动读之前问“文字现在能不能进网关”（第四轮 F8）；用户自己发起的操作不问它。 */
    private val policy: DataFlowPolicy,
    private val clock: Clock,
    private val log: SyncLog = SyncLog.None,
    private val contract: ContractGuard = ContractGuard(),
) : DestinationController {
    private val issuing = Mutex()

    /**
     * 一次只有一个管理类的网关往返（从发请求到把回答写进缓存，评审 H3）：较早发出的请求的回答不可能在较晚的撤销、创建、签发写完之后才落进缓存，
     * 把已撤销的改回有效、把新建的当成“网关没有”删掉。排队的操作等前一个做完（包括它的超时），界面上显示的就是“进行中”。
     */
    private val exchange = Mutex()

    override val destinations: Flow<List<Destination>> = dispatch.changes().onStart { emit(0L) }.map { dispatch.destinations() }

    override suspend fun refresh(): ServiceResult<List<Destination>> = list(RequestGate.Open, only = null)

    override suspend fun refreshAutomatically(): ServiceResult<List<Destination>> {
        val gateway = (pairing.load() as? PairingState.Paired)?.gateway ?: return ServiceResult.Failed(ServiceFailure.NotPaired)
        val permit = permitFor(gateway) ?: return ServiceResult.Failed(ServiceFailure.AutomaticReadsOff)
        return list(permit, only = gateway)
    }

    private suspend fun list(gate: RequestGate, only: PairedGateway?): ServiceResult<List<Destination>> = gatewayCall(
        "refresh",
        gate = gate,
        only = only,
        request = { it.client.listDestinations(it.address, it.gateway.token) },
        apply = { session, list -> dispatch.applyDestinations(session.gateway.identity, list, authoritative = true, now = clock.now()) },
    )

    override suspend fun create(request: CreateDestinationRequest): ServiceResult<Destination> {
        // 上限由网关数，不用本机缓存挡：缓存可能是旧的（别的手机建了或撤销了），挡错了人或放过了第 51 个（评审 M4）
        val result = createOnGateway(request)
        return if (result is ServiceResult.Failed && result.failure.isActiveLimit()) ServiceResult.Failed(ServiceFailure.LimitReached) else result
    }

    /** 创建 Destination 只有一个原因会得到 409：有效的已经满 50 个（contracts/README.md）。别的拒绝照旧是普通的拒绝。 */
    private fun ServiceFailure.isActiveLimit(): Boolean = this is ServiceFailure.Rejected && status == 409 && code == ErrorCode.REQUEST_INVALID

    private suspend fun createOnGateway(request: CreateDestinationRequest): ServiceResult<Destination> = gatewayCall(
        "create",
        request = { it.client.createDestination(it.address, it.gateway.token, request) },
        apply = { session, created -> dispatch.applyDestinations(session.gateway.identity, listOf(created), authoritative = false, now = clock.now()) },
    )

    override suspend fun revoke(destinationId: String): ServiceResult<Destination> = gatewayCall(
        "revoke",
        request = { it.client.revokeDestination(it.address, it.gateway.token, destinationId) },
        apply = { session, revoked -> dispatch.applyDestinations(session.gateway.identity, listOf(revoked), authoritative = false, now = clock.now()) },
    )

    override suspend fun issueCredential(destinationId: String): CredentialOutcome {
        if (!issuing.tryLock()) return CredentialOutcome.Failed(ServiceFailure.Busy)
        try {
            // 能提前知道要失败的，在弹指纹之前就说：没配对、连接坏了、没有这个 Destination 或已撤销
            val gateway = when (val state = pairing.load()) {
                is PairingState.Paired -> state.gateway
                else -> return CredentialOutcome.Failed(ServiceFailure.NotPaired)
            }
            // 要重新配对的在弹指纹之前就说；“网关太旧”的标记可能已经过时（用户刚更新完网关），留给通过核验之后的 connect() 去问一次
            gateway.needsUser?.takeIf { it != NeedsUser.GATEWAY_TOO_OLD }?.let { return CredentialOutcome.Failed(ServiceFailure.NeedsRepair(it)) }
            val known = dispatch.destinations().firstOrNull { it.destinationId == destinationId }
            if (known == null || !known.active) return CredentialOutcome.Failed(ServiceFailure.DestinationUnavailable)

            // 暂停的（凭证已被撤销）没有旧凭证可换：重新生成不是轮换
            val purpose = if (known.credentialIssued && !known.suspended) AuthPurpose.ROTATE_CREDENTIAL else AuthPurpose.ISSUE_CREDENTIAL
            val auth = authenticator.authenticate(purpose)
            if (auth != AuthResult.Authenticated) {
                log.event("credential.auth ${auth::class.simpleName}")
                return CredentialOutcome.NotAuthenticated(auth)
            }

            // 核验（用户可能停很久）之后才进入往返：不让一个等指纹的操作挡住别的管理操作
            return exchange.withLock {
                val session = when (val connected = connect()) {
                    is Connect.Ready -> connected.session
                    is Connect.Blocked -> return@withLock CredentialOutcome.Failed(connected.failure)
                }
                val result = session.client.issueCredential(session.address, session.gateway.token, destinationId).noteOutcome()
                when (result) {
                    is GatewayResult.Success -> {
                        // 确认还是这一份配对与写缓存在同一把锁里（复评 R5）
                        pairing.whileCurrent(session.gateway) {
                            dispatch.applyDestinations(session.gateway.identity, listOf(known.copy(credentialIssued = true, credentialActive = true)), authoritative = false, now = clock.now())
                        } ?: return@withLock CredentialOutcome.Failed(ServiceFailure.NotPaired)
                        log.event("credential.issued replaced=${result.value.replacedPrevious}")
                        CredentialOutcome.Issued(result.value)
                    }

                    else -> {
                        if (!stillPaired(session.gateway)) return@withLock CredentialOutcome.Failed(ServiceFailure.NotPaired)
                        CredentialOutcome.Failed(result.failure()).also { log.event("credential.failed ${result::class.simpleName}") }
                    }
                }
            }
        } finally {
            issuing.unlock()
        }
    }

    override suspend fun deliveries(destinationId: String, limit: Int): List<ItemDelivery> = deliveriesThrough(destinationId, limit, RequestGate.Open, only = null)

    override suspend fun deliveriesAutomatically(destinationId: String, limit: Int): List<ItemDelivery> {
        val gateway = (pairing.load() as? PairingState.Paired)?.gateway
        return deliveriesThrough(destinationId, limit, gateway?.let { permitFor(it) }, only = gateway)
    }

    /** [gate] 为 null = 不许联系网关：要问网关才说得清的各条记 [UnknownReason.NOT_ASKED]。 */
    private suspend fun deliveriesThrough(destinationId: String, limit: Int, gate: RequestGate?, only: PairedGateway?): List<ItemDelivery> {
        val items = dispatch.itemsFor(destinationId, limit)
        // 只有“已入库”、而且本机的账说不清的才需要问网关（用户跳过的、还在等用户的、网关拒绝建的，账上就是答案）；一条 Record 只问一次
        val needGateway = items.filter { item ->
            jobs.get(item.key)?.state == SyncState.STORED && item.targets.first { it.destinationId == destinationId }.needsGatewayAnswer()
        }.map { it.key.recordId }.distinct()
        val remote = when {
            needGateway.isEmpty() -> emptyMap()
            gate == null -> needGateway.associateWith { Remote.Unusable(UnknownReason.NOT_ASKED) }
            else -> askGateway(needGateway, gate, only)
        }
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

    private suspend fun askGateway(recordIds: List<String>, gate: RequestGate, only: PairedGateway?): Map<String, Remote> {
        val session = when (val connected = connect(gate, only)) {
            is Connect.Ready -> connected.session
            is Connect.Blocked -> {
                val reason = if (connected.failure == ServiceFailure.AutomaticReadsOff) UnknownReason.NOT_ASKED else UnknownReason.GATEWAY_UNREACHABLE
                return recordIds.associateWith { Remote.Unusable(reason) }
            }
        }
        return recordIds.associateWith { recordId ->
            val asked = gate.gated { session.client.recordStatus(session.address, session.gateway.token, recordId) } ?: return@associateWith Remote.Unusable(UnknownReason.NOT_ASKED)
            when (val result = asked.noteOutcome()) {
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
        if (target.delivery == GatewayDelivery.REFUSED) {
            return if (target.refusal == Refusal.SUSPENDED) DeliveryView.Suspended else DeliveryView.Unknown(UnknownReason.DELIVERY_REFUSED)
        }
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

    /**
     * 连上网关：[gate] 是每个会发请求的步骤（探测一个地址、问 mDNS、问合同版本）各过一次的门；用户自己发起的操作用不设防的 [RequestGate.Open]，
     * 自动读用一张放行（[permitFor]）：中途授权被收回，后面的一个请求也不发，回 [ServiceFailure.AutomaticReadsOff]。
     * [only] 不为 null = 就连这一份配对（放行是对它问的）：不再重读配对记录，免得放行问的是 A、连上的却是刚配上的 B。
     */
    private suspend fun connect(gate: RequestGate = RequestGate.Open, only: PairedGateway? = null): Connect {
        val gateway = only ?: when (val state = pairing.load()) {
            is PairingState.Paired -> state.gateway
            else -> return Connect.Blocked(ServiceFailure.NotPaired)
        }
        // “网关太旧”不是要重新配对的问题，更新网关之后自己会好：带着这个标记也再问一次版本；别的连接问题照旧直接拦
        gateway.needsUser?.takeIf { it != NeedsUser.GATEWAY_TOO_OLD }?.let { return Connect.Blocked(ServiceFailure.NeedsRepair(it)) }
        return when (val located = locator.locate(gateway, gate) ?: return Connect.Blocked(ServiceFailure.AutomaticReadsOff)) {
            is Located.Reachable -> verified(gateway, located.address, gate)
            Located.Unreachable -> {
                contract.invalidate() // 连不上过：回来时网关可能已不是原来的版本，下一次操作重新问（再评 S6）
                Connect.Blocked(ServiceFailure.Unreachable)
            }

            Located.KeyMismatch -> Connect.Blocked(ServiceFailure.NeedsRepair(NeedsUser.KEY_MISMATCH))
        }
    }

    /**
     * 文字可以进 [gateway] 吗？可以就给一张放行，不可以是 null。和同步引擎的 Permit 是同一个办法（再评 S3）：世代在问之前记下，
     * 每个请求交出去的前一刻再比一次（[PolicyGeneration.handoff]），问与发之间有人动过设置就不发。授权是对这一台网关问的（复评 R3）；
     * [DataFlowPolicy.stored] 读不出来就回答“不许”。引擎那份是它的内部类、有源码守卫钉着，这里不去合并。
     */
    private suspend fun permitFor(gateway: PairedGateway): Permit? {
        val generation = policy.generation
        return if (policy.current(gateway.identity).textMayEnterGateway) Permit(generation) else null
    }

    private inner class Permit(private val generation: Long) : RequestGate {
        override suspend fun <T : Any> gated(request: suspend () -> T): T? = PolicyGeneration.handoff({ policy.generation == generation }, request)
    }

    /** 连上之后、第一个管理请求之前：确认这台网关说的是手机说的合同版本（评审 R7）。太旧、没问成，一个管理请求都不发。 */
    private suspend fun verified(gateway: PairedGateway, address: GatewayAddress, gate: RequestGate): Connect {
        val client = GatewayClient(http.forPin(gateway.pin))
        val answer = gate.gated { contract.check(gateway, address, client) } ?: return Connect.Blocked(ServiceFailure.AutomaticReadsOff)
        return when (answer) {
            ContractAnswer.COMPATIBLE -> {
                if (gateway.needsUser == NeedsUser.GATEWAY_TOO_OLD) pairing.clearGatewayTooOld(gateway) // 网关已经更新：同步页一起恢复
                Connect.Ready(Session(gateway, address, client))
            }

            ContractAnswer.TOO_OLD -> {
                pairing.markGatewayTooOld(gateway)
                Connect.Blocked(ServiceFailure.NeedsRepair(NeedsUser.GATEWAY_TOO_OLD))
            }

            ContractAnswer.AUTH_REJECTED -> Connect.Blocked(ServiceFailure.NeedsRepair(NeedsUser.AUTH_REJECTED))
            ContractAnswer.KEY_MISMATCH -> Connect.Blocked(ServiceFailure.NeedsRepair(NeedsUser.KEY_MISMATCH))
            ContractAnswer.UNCONFIRMED -> Connect.Blocked(ServiceFailure.Unreachable) // 没问成不是“太旧”：按连不上处理，请求一个都没发
        }
    }

    /**
     * 管理请求没成功（连接断了、被拒绝、读不懂）：网关可能已不是原来的版本（进程活着时被换回旧版本），作废“兼容”的记忆，
     * 下一次操作重新问版本，不拿过期的结论继续发（再评 S6）。成功的回答原样放过。
     */
    private fun <T> GatewayResult<T>.noteOutcome(): GatewayResult<T> = also { if (it !is GatewayResult.Success) contract.invalidate() }

    /** 请求途中用户重新配对了：旧配对的回答不能写进新配对的缓存。 */
    private suspend fun stillPaired(gateway: PairedGateway): Boolean {
        val current = (pairing.load() as? PairingState.Paired)?.gateway ?: return false
        return current.gatewayId == gateway.gatewayId && current.deviceId == gateway.deviceId && current.pairedAt == gateway.pairedAt && current.pin == gateway.pin
    }

    /**
     * 一次管理操作：连上、发请求、**确认还是同一个配对**之后才把回答写进缓存（请求途中重新配对了，旧网关的回答不能写进新网关的缓存）。
     * 确认与写缓存在配对存储的同一把锁里（复评 R5）：不会在“刚确认完、还没写”的缝里被重新配对插进来。
     */
    private suspend fun <T> gatewayCall(
        name: String,
        request: suspend (Session) -> GatewayResult<T>,
        apply: suspend (Session, T) -> Unit,
        gate: RequestGate = RequestGate.Open,
        only: PairedGateway? = null,
    ): ServiceResult<T> = exchange.withLock {
        val session = when (val connected = connect(gate, only)) {
            is Connect.Ready -> connected.session
            is Connect.Blocked -> return@withLock ServiceResult.Failed(connected.failure)
        }
        val answered = gate.gated { request(session) } ?: return@withLock ServiceResult.Failed(ServiceFailure.AutomaticReadsOff)
        val result = answered.noteOutcome()
        when (result) {
            is GatewayResult.Success -> {
                pairing.whileCurrent(session.gateway) { apply(session, result.value) } ?: return@withLock ServiceResult.Failed(ServiceFailure.NotPaired)
                log.event("destination.$name ok")
                ServiceResult.Ok(result.value)
            }

            else -> {
                if (!stillPaired(session.gateway)) return@withLock ServiceResult.Failed(ServiceFailure.NotPaired)
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
