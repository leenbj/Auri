package app.auri.syncplatform.dispatch

/** 一行查询结果，按位置取值。 */
interface SqlRow {
    fun string(index: Int): String

    fun stringOrNull(index: Int): String?

    fun long(index: Int): Long

    fun longOrNull(index: Int): Long?
}

/**
 * 发送账用到的最小 SQL 接口：同一份逻辑（[DispatchSql]）在 Android 的 SQLiteDatabase（[AndroidSqlDb]）与 JVM 测试里的 JDBC（真正的 SQLite 引擎）上跑，
 * 所以“预览确认之前不放行、决定幂等、重启后仍然拦着”这些规则能在没有设备的机器上用真实的 SQL 验证。
 * 参数只用 String、Int、Long、null。
 */
interface SqlDb {
    /** INSERT / UPDATE / DELETE，返回受影响的行数（INSERT OR IGNORE 被忽略时为 0）。 */
    fun update(sql: String, vararg args: Any?): Int

    fun <T> query(sql: String, vararg args: Any?, map: (SqlRow) -> T): List<T>

    /** 可重入的事务：最外层决定提交或回滚，块里抛异常就回滚。发布 Revision 的事务里调用时并入那个事务。 */
    fun <T> transaction(block: () -> T): T
}
