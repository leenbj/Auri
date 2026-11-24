package app.auri.syncplatform.dispatch

import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types

/**
 * [SqlDb] 在 JVM 测试里的实现：用真正的 SQLite 引擎（sqlite-jdbc，仅测试类路径）。
 * 事务可重入（与 Android 的 SQLiteDatabase 一样）：最外层 begin/commit，里层并入；任何一层抛异常，最外层回滚。
 * 限制：Android 自带的 SQLite 版本比这里的旧——这里证明的是 SQL 与逻辑，不是设备上的行为（那部分是仪器测试，本机 NOT_RUN）。
 */
class JdbcSqlDb(val connection: Connection) : SqlDb {
    private var depth = 0
    private var failed = false

    override fun update(sql: String, vararg args: Any?): Int = connection.prepareStatement(sql).use { statement ->
        bind(statement, args)
        statement.executeUpdate()
    }

    override fun <T> query(sql: String, vararg args: Any?, map: (SqlRow) -> T): List<T> = connection.prepareStatement(sql).use { statement ->
        bind(statement, args)
        statement.executeQuery().use { rows ->
            val row = JdbcRow(rows)
            buildList { while (rows.next()) add(map(row)) }
        }
    }

    override fun <T> transaction(block: () -> T): T {
        val outermost = depth == 0
        if (outermost) {
            connection.autoCommit = false
            failed = false
        }
        depth++
        try {
            val result = block()
            depth--
            if (outermost) if (failed) connection.rollback() else connection.commit()
            return result
        } catch (e: Throwable) {
            depth--
            failed = true
            if (outermost) connection.rollback()
            throw e
        } finally {
            if (outermost) connection.autoCommit = true
        }
    }

    private fun bind(statement: java.sql.PreparedStatement, args: Array<out Any?>) {
        args.forEachIndexed { index, value ->
            when (value) {
                null -> statement.setNull(index + 1, Types.NULL)
                is String -> statement.setString(index + 1, value)
                is Int -> statement.setInt(index + 1, value)
                is Long -> statement.setLong(index + 1, value)
                else -> error("不支持的参数类型 ${value::class.simpleName}")
            }
        }
    }

    private class JdbcRow(private val rows: ResultSet) : SqlRow {
        override fun string(index: Int): String = rows.getString(index + 1) ?: error("第 $index 列是 NULL")

        override fun stringOrNull(index: Int): String? = rows.getString(index + 1)

        override fun long(index: Int): Long = rows.getLong(index + 1)

        override fun longOrNull(index: Int): Long? = rows.getLong(index + 1).let { if (rows.wasNull()) null else it }
    }
}
