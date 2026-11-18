package app.auri.capture.scheduler

import app.auri.capture.testing.JobStoreContract

/** 内存任务库（测试替身与默认实现）也要守任务库的合同，否则用它的测试证明不了什么。 */
class InMemoryJobStoreTest : JobStoreContract() {
    override fun create(): JobStore = InMemoryJobStore()

    override fun reopen(previous: JobStore): JobStore = previous
}
