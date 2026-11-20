package app.auri.sync.engine

import app.auri.sync.gateway.Intent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * 向网关追加一份 Delivery：给已入库的 [key] 补上 [destinationId] 的 Delivery，标签是冻结了的 [intent]。
 * 一次只带一个 Destination：网关的追加是“要么全成、要么一份也不建”，一个有问题的目的地不能拖累别的。
 */
data class DeliveryAddition(val key: JobKey, val intent: Intent, val destinationId: String)

/** 网关为什么没有（也不会）为这个目标建 Delivery。 */
enum class Refusal {
    /** 网关明确拒绝了这条追加（目的地被撤销、Record 已删除、不是来源设备……）：重试也不会成功。 */
    REJECTED,

    /** 网关答复成功，但回答里没有这个 Destination：它没有为它建。 */
    NOT_CREATED,
}

/**
 * 发送账里“该有、网关上却还没有的 Delivery”（评审 B2）。同步任务只管把 Revision 送进网关（它一定会送，不等任何人的决定）；
 * 预览确认的 Destination 在用户确认之后才有 Delivery，这件事由引擎在每一轮同步的最后向网关追加——对同一个已入库的、不可变的 Revision。
 * 追加在网关上是幂等的，所以这里“多问一次”只是多一个请求，不会多一份 Delivery；账里记下来是为了不再问。
 *
 * 实现必须持久：进程在任何一步被杀，重启后 [due] 还能算出还欠哪些。
 */
interface DeliveryAdditions {
    /** 现在就该向网关追加的：Revision 已经入库、目标还在并且会收到它（已授权或已确认）、网关上还没有它的 Delivery、也没被拒绝过。最早的在前。 */
    suspend fun due(): List<DeliveryAddition>

    /** 网关上这个 Revision 已经有这些 Destination 的 Delivery（入库的回答、状态查询、追加的回答里看到的）。幂等；不认识的目标忽略。 */
    suspend fun gatewayHas(key: JobKey, destinationIds: Collection<String>)

    /** 网关不会为这些目标建 Delivery：以后不再追加，界面如实说没有送达。 */
    suspend fun refused(key: JobKey, destinationIds: Collection<String>, reason: Refusal)

    /** 任何可能改变 [due] 的变化（决定、入库、目的地缓存）之后发出一个递增的数。 */
    fun changes(): Flow<Long>

    companion object {
        /** 没有接发送账时的样子（Ticket 21）：没有什么要追加。 */
        val None: DeliveryAdditions = object : DeliveryAdditions {
            override suspend fun due(): List<DeliveryAddition> = emptyList()

            override suspend fun gatewayHas(key: JobKey, destinationIds: Collection<String>) = Unit

            override suspend fun refused(key: JobKey, destinationIds: Collection<String>, reason: Refusal) = Unit

            override fun changes(): Flow<Long> = emptyFlow()
        }
    }
}

/** [DeliveryAdditions.due] 的变化流：每次 [DeliveryAdditions.changes] 之后重算，只在集合真的变了时才发出（带着启动时的第一个）。 */
fun DeliveryAdditions.dueUpdates(): Flow<List<DeliveryAddition>> = changes().onStart { emit(0L) }.map { due() }.distinctUntilChanged()
