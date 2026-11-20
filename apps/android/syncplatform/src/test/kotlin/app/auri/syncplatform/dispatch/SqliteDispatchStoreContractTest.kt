package app.auri.syncplatform.dispatch

import app.auri.sync.testing.DispatchFixture
import app.auri.sync.testing.DispatchStoreContract

/**
 * Android 版发送账的 SQL 与逻辑（[DispatchSql]）通过与内存版同一套行为合同：预览确认之前没有同步任务、决定幂等、重启（重新建表加回填）后仍然拦着、
 * 被撤销的目标不拦别人、用户修订只入库……跑在真正的 SQLite 引擎上（sqlite-jdbc，仅测试类路径）。
 * 限制：证明的是 SQL 与逻辑；Android 自带的 SQLite 更旧、线程与连接由 SqliteRecordLibrary 管，那些在设备上（仪器测试，本机 NOT_RUN）。
 */
class SqliteDispatchStoreContractTest : DispatchStoreContract() {
    override fun newFixture(): DispatchFixture = JdbcDispatchFixture()
}
