package top.tianyan.app.core.database

import android.database.Cursor
import androidx.room.withTransaction
import kotlinx.serialization.json.*
import top.tianyan.app.core.model.BackupRecords

interface DatabaseBackupRepository {
    suspend fun snapshot(): BackupRecords
    suspend fun validate(records: BackupRecords)
    suspend fun merge(expected: BackupRecords, records: BackupRecords, operationId: String, prepare: suspend () -> Unit): Int
    suspend fun committed(operationId: String): Boolean
}

/** SQL only lives behind this persistence port. Names and shapes come from a fixed table allowlist. */
class RoomDatabaseBackupRepository(private val database: AppDatabase) : DatabaseBackupRepository {
    private val sql get() = database.openHelper.writableDatabase

    override suspend fun snapshot(): BackupRecords = database.withTransaction {
        check(sql.query("SELECT COUNT(*) FROM harness_operations").use { it.moveToFirst(); it.getLong(0) == 0L }) {
            "请先停止正在运行的智能体任务，再执行备份或恢复"
        }
        BackupRecords(BackupRecordPolicy.tables.associateWith { table ->
            sql.query("SELECT * FROM `$table`").use { cursor -> buildList {
                while (cursor.moveToNext()) {
                    check(size < 100_000) { "记录数量超过备份上限" }
                    add(JsonObject(cursor.columnNames.mapIndexed { index, name -> name to cursor.value(index) }.toMap()))
                }
            } }
        })
    }

    override suspend fun validate(records: BackupRecords) {
        require(records.tables.keys == BackupRecordPolicy.tables.toSet()) { "备份数据分类不完整或不兼容" }
        require(records.tables.values.sumOf { it.size } <= 100_000) { "记录数量超过备份上限" }
        records.tables.forEach { (table, rows) ->
            val columns = sql.query("PRAGMA table_info(`$table`)").use { cursor -> buildList {
                while (cursor.moveToNext()) add(Column(cursor.getString(1), cursor.getString(2), cursor.getInt(3) != 0))
            } }
            val keys = mutableSetOf<String>()
            rows.forEach { row ->
                require(row.keys == columns.map { it.name }.toSet()) { "备份字段与当前版本不兼容" }
                columns.forEach { column ->
                    val value = row.getValue(column.name)
                    require(value is JsonPrimitive && !(column.required && value == JsonNull)) { "备份字段类型无效" }
                    if (value != JsonNull) require(when (column.type) {
                        "INTEGER" -> !value.jsonPrimitive.isString && (value.jsonPrimitive.longOrNull != null || value.jsonPrimitive.booleanOrNull != null)
                        "REAL" -> !value.jsonPrimitive.isString && value.jsonPrimitive.doubleOrNull?.isFinite() == true
                        "TEXT" -> value.jsonPrimitive.isString
                        else -> false
                    }) { "备份字段类型无效" }
                }
                require(keys.add(BackupRecordPolicy.key(table, row))) { "备份存在重复记录" }
            }
        }
        validateLinks(records)
    }

    override suspend fun merge(expected: BackupRecords, records: BackupRecords, operationId: String, prepare: suspend () -> Unit): Int =
        database.withTransaction {
            check(snapshot() == expected) { "数据已变化，请重新选择备份并预览" }
            validate(records)
            var count = 0
            BackupRecordPolicy.tables.forEach { table ->
                records.tables.getValue(table).sortedBy { it["sequence"]?.jsonPrimitive?.longOrNull ?: 0 }.forEach { row ->
                    val values = row.filterKeys { it != "sequence" || table !in setOf("harness_entries", "harness_usage") }
                    sql.execSQL("INSERT INTO `$table` (${values.keys.joinToString { "`$it`" }}) VALUES (${values.keys.joinToString { "?" }})",
                        values.values.map<JsonElement, Any?> { value -> if (value == JsonNull) null else value.jsonPrimitive.let {
                            when {
                                it.isString -> it.content
                                it.booleanOrNull != null -> if (it.booleanOrNull == true) 1 else 0
                                else -> it.longOrNull ?: it.doubleOrNull
                            }
                        } }.toTypedArray())
                    count++
                }
            }
            prepare()
            sql.execSQL("INSERT OR REPLACE INTO backup_restore_receipt(id, operationId) VALUES(1, ?)", arrayOf(operationId))
            database.invalidationTracker.refreshAsync()
            count
        }

    override suspend fun committed(operationId: String): Boolean = sql.query("SELECT operationId FROM backup_restore_receipt WHERE id=1").use {
        it.moveToFirst() && it.getString(0) == operationId
    }

    private fun validateLinks(records: BackupRecords) {
        val sessions = records.tables["harness_sessions"].orEmpty().mapTo(mutableSetOf()) { it["id"] }
        val entries = records.tables["harness_entries"].orEmpty().associateBy { it["id"] }
        BackupRecordPolicy.sessionChildren.forEach { table -> records.tables[table].orEmpty().forEach { row ->
            require(row["sessionId"] in sessions) { "备份会话关联不完整" }
        } }
        entries.values.forEach { row ->
            val parent = row["parentId"]
            require(parent == JsonNull || (parent in entries && entries[parent]?.get("sessionId") == row["sessionId"])) { "备份消息链不完整" }
        }
        records.tables["harness_lanes"].orEmpty().forEach { row ->
            val leaf = row["leafId"]
            require(leaf == JsonNull || entries[leaf]?.get("sessionId") == row["sessionId"]) { "备份会话分支不完整" }
        }
    }

    private data class Column(val name: String, val type: String, val required: Boolean)
    private fun Cursor.value(index: Int): JsonPrimitive = when (getType(index)) {
        Cursor.FIELD_TYPE_NULL -> JsonNull
        Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(getLong(index))
        Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(getDouble(index))
        Cursor.FIELD_TYPE_STRING -> JsonPrimitive(getString(index))
        else -> error("此数据类型不支持备份")
    }
}
