package app.auri.sync.destinations

import app.auri.sync.testing.DispatchFixture
import app.auri.sync.testing.DispatchStoreContract
import app.auri.sync.testing.InMemoryDispatchFixture

/** 内存实现过一遍发送账的行为合同（SQLite 实现在 :syncplatform 用真实的 SQLite 引擎过同一套）。 */
class InMemoryDispatchStoreTest : DispatchStoreContract() {
    override fun newFixture(): DispatchFixture = InMemoryDispatchFixture()
}
