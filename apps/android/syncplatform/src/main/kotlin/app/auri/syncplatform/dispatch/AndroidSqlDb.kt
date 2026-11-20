package app.auri.syncplatform.dispatch

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement

/** Android 的实现：用调用方给的那一条连接（同库里别的表也用它，见 SqliteRecordLibrary.withDatabase）。 */
class AndroidSqlDb(private val db: SQLiteDatabase) : SqlDb {
    override fun update(sql: String, vararg args: Any?): Int = db.compileStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.bind(index + 1, value) }
        statement.executeUpdateDelete()
    }

    override fun <T> query(sql: String, vararg args: Any?, map: (SqlRow) -> T): List<T> =
        // rawQuery 的参数都按文本绑定：与 INTEGER 列比较时 SQLite 按列的亲和性转换，现有的任务表也是这么用的
        db.rawQuery(sql, Array(args.size) { args[it]?.toString() }).use { cursor ->
            val row = object : SqlRow {
                override fun string(index: Int) = cursor.getString(index)

                override fun stringOrNull(index: Int): String? = if (cursor.isNull(index)) null else cursor.getString(index)

                override fun long(index: Int) = cursor.getLong(index)

                override fun longOrNull(index: Int): Long? = if (cursor.isNull(index)) null else cursor.getLong(index)
            }
            buildList { while (cursor.moveToNext()) add(map(row)) }
        }

    override fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    private fun SQLiteStatement.bind(index: Int, value: Any?) = when (value) {
        null -> bindNull(index)
        is String -> bindString(index, value)
        is Int -> bindLong(index, value.toLong())
        is Long -> bindLong(index, value)
        else -> error("不支持的参数类型 ${value::class.simpleName}")
    }
}
