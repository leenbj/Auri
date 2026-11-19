package app.auri.sync.pairing

import app.auri.sync.testing.InMemoryPairingStore
import app.auri.sync.testing.SyncSamples
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 界面与同步状态汇总要跟着“配对状态”变：配对完成、引擎因公钥不符打上“要用户处理”、用户重新配对，都要让订阅者立刻看到。
 * 没有这个信号，同步状态页会一直停在旧的标题上，用户看不到“需要你处理”。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObservablePairingStoreTest {
    private val paired = SyncSamples.pairedGateway()

    @Test
    fun `还没读过之前没有任何状态，读了一次才发出：订阅者不会先看到一个猜的“未配对”`() = runTest(UnconfinedTestDispatcher()) {
        val store = ObservablePairingStore(InMemoryPairingStore(paired))
        val seen = mutableListOf<PairingState>()
        val collector = launch { store.states.toList(seen) }

        assertTrue("没读过的时候不该有状态：真实状态可能是已配对", seen.isEmpty())
        store.refresh()
        assertEquals(listOf<PairingState>(PairingState.Paired(paired)), seen)
        collector.cancel()
    }

    @Test
    fun `保存、更新、清除之后订阅者各看到一次新状态`() = runTest(UnconfinedTestDispatcher()) {
        val store = ObservablePairingStore(InMemoryPairingStore())
        val seen = mutableListOf<PairingState>()
        val collector = launch { store.states.toList(seen) }
        store.refresh()

        store.save(paired)
        store.update { it.copy(needsUser = NeedsUser.KEY_MISMATCH) }
        store.clear()

        assertEquals(
            listOf(
                PairingState.NotPaired,
                PairingState.Paired(paired),
                PairingState.Paired(paired.copy(needsUser = NeedsUser.KEY_MISMATCH)),
                PairingState.NotPaired,
            ),
            seen,
        )
        collector.cancel()
    }

    @Test
    fun `更新函数说“不改”时什么都不发出，也不重写`() = runTest(UnconfinedTestDispatcher()) {
        val inner = InMemoryPairingStore(paired)
        val store = ObservablePairingStore(inner)
        store.refresh()
        val seen = mutableListOf<PairingState>()
        val collector = launch { store.states.toList(seen) }
        val savesBefore = inner.saves.get()

        assertFalse(store.update { null })

        assertEquals(1, seen.size) // 只有订阅时拿到的当前状态
        assertEquals(savesBefore, inner.saves.get())
        collector.cancel()
    }

    @Test
    fun `底层存不下：异常原样抛出，已发出的状态不变（不会谎称配好了）`() = runTest(UnconfinedTestDispatcher()) {
        val failing = object : PairingStore by InMemoryPairingStore() {
            override suspend fun save(gateway: PairedGateway) = throw IOException("磁盘满了")
        }
        val store = ObservablePairingStore(failing)
        store.refresh()
        val seen = mutableListOf<PairingState>()
        val collector = launch { store.states.toList(seen) }

        try {
            store.save(paired)
            fail("保存失败必须让调用方知道")
        } catch (expected: IOException) {
            // 对的
        }

        assertEquals(listOf<PairingState>(PairingState.NotPaired), seen)
        collector.cancel()
    }

    @Test
    fun `配对记录读不出来（Lost）也如实发出，不当成未配对`() = runTest(UnconfinedTestDispatcher()) {
        val lost = object : PairingStore by InMemoryPairingStore() {
            override suspend fun load(): PairingState = PairingState.Lost
        }
        val store = ObservablePairingStore(lost)
        val seen = mutableListOf<PairingState>()
        val collector = launch { store.states.toList(seen) }

        store.refresh()

        assertEquals(listOf<PairingState>(PairingState.Lost), seen)
        collector.cancel()
    }

    @Test
    fun `保存之后发出的是读回来的记录：读不回来就是 Lost，不是写进去的那份`() = runTest(UnconfinedTestDispatcher()) {
        val unreadable = object : PairingStore by InMemoryPairingStore() {
            override suspend fun load(): PairingState = PairingState.Lost
        }
        val store = ObservablePairingStore(unreadable)
        store.refresh()
        val seen = mutableListOf<PairingState>()
        val collector = launch { store.states.toList(seen) }

        store.save(paired)

        assertEquals("盘上读不回来时界面不能显示“已配对”", listOf<PairingState>(PairingState.Lost), seen)
        collector.cancel()
    }

    @Test
    fun `读一次也会发出读到的状态：底层被别处改过时订阅者跟上`() = runTest(UnconfinedTestDispatcher()) {
        val inner = InMemoryPairingStore()
        val store = ObservablePairingStore(inner)
        store.refresh()
        val seen = mutableListOf<PairingState>()
        val collector = launch { store.states.toList(seen) }

        inner.save(paired) // 绕开包装层写（例如别的组件拿着同一份底层存储）
        assertEquals(PairingState.Paired(paired), store.load())

        assertEquals(listOf(PairingState.NotPaired, PairingState.Paired(paired)), seen)
        collector.cancel()
    }

    @Test
    fun `同时进行的读写排队：读到旧状态后迟迟才发出的那次，不会盖掉后来写入的新状态`() = runTest(UnconfinedTestDispatcher()) {
        val inner = InMemoryPairingStore()
        val gate = CompletableDeferred<Unit>()
        var firstRead = true
        val slowFirstRead = object : PairingStore by inner {
            override suspend fun load(): PairingState {
                val state = inner.load() // 读到的是旧状态
                if (firstRead) {
                    firstRead = false
                    gate.await() // 停在“读到了、还没发出”这一刻
                }
                return state
            }
        }
        val store = ObservablePairingStore(slowFirstRead)
        val seen = mutableListOf<PairingState>()
        val collector = launch { store.states.toList(seen) }

        val refreshing = launch { store.refresh() } // 读到“未配对”，停在闸门前
        val saving = launch { store.save(paired) } // 没有锁的话，这里会直接写盘并发出“已配对”
        gate.complete(Unit)
        refreshing.join()
        saving.join()

        assertEquals("最后发出的必须是盘上的最新状态", PairingState.Paired(paired), seen.last())
        assertEquals(PairingState.Paired(paired), inner.load())
        collector.cancel()
    }
}
