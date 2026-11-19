package app.auri.sync.pairing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 给 [PairingStore] 加一个“当前状态”的可观察副本，界面和同步状态汇总靠它跟着变：配对完成、引擎因公钥不符打上“要用户处理”、
 * 用户重新配对，订阅者都立刻看到。所有写操作与它后面的“读回来再发出”在同一把锁里，所以发出的顺序就是落盘的顺序，
 * 不会让旧状态盖过新状态。
 *
 * 读（[load]）也会发出读到的状态：底层被别处改过时，订阅者跟上。
 * 读过一次（[refresh]）之前没有任何状态：真实状态可能是“已配对”，不猜一个“未配对”给界面。
 * 发出的是**读回来的**记录而不是写进去的：读不出来就是 [PairingState.Lost]，界面如实显示，不谎称配好了。
 */
class ObservablePairingStore(private val delegate: PairingStore) : PairingStore {
    private val mutex = Mutex()
    private val current = MutableStateFlow<PairingState?>(null)

    val states: Flow<PairingState> = current.filterNotNull()

    /** 从底层读一次并发出（应用启动时调用）。 */
    suspend fun refresh() {
        mutex.withLock { current.value = delegate.load() }
    }

    override suspend fun load(): PairingState = mutex.withLock { delegate.load().also { current.value = it } }

    override suspend fun save(gateway: PairedGateway) {
        mutex.withLock {
            delegate.save(gateway)
            current.value = delegate.load()
        }
    }

    override suspend fun update(transform: (PairedGateway) -> PairedGateway?): Boolean = mutex.withLock {
        val written = delegate.update(transform)
        if (written) current.value = delegate.load()
        written
    }

    override suspend fun clear() {
        mutex.withLock {
            delegate.clear()
            current.value = delegate.load()
        }
    }
}
