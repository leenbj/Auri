package app.auri.syncplatform.dispatch

import app.auri.sync.testing.DispatchFixture
import app.auri.sync.testing.DispatchStoreContract

/**
 * Android 版发送账的 SQL 与逻辑（[DispatchSql]）通过与内存版同一套行为合同：文字入库不等决定、预览确认的 Delivery 确认后才欠、决定幂等、
 * 意图在构造提交时冻结、网关上有没有 Delivery 的账重启（重新建表加回填）后还在、被撤销的目标不拦别人、用户修订只入库……
 * 跑在真正的 SQLite 引擎上（sqlite-jdbc，仅测试类路径）。
 * 限制：证明的是 SQL 与逻辑；Android 自带的 SQLite 更旧、线程与连接由 SqliteRecordLibrary 管，那些在设备上（仪器测试，本机 NOT_RUN）。
 */
class SqliteDispatchStoreContractTest : DispatchStoreContract() {
    override fun newFixture(): DispatchFixture = JdbcDispatchFixture()
}
