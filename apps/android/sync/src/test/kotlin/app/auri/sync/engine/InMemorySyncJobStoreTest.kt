package app.auri.sync.engine

import app.auri.sync.testing.InMemorySyncJobStore
import app.auri.sync.testing.SyncJobStoreContract

class InMemorySyncJobStoreTest : SyncJobStoreContract() {
    override fun newStore(): SyncJobStore = InMemorySyncJobStore()
}
