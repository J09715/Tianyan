package top.tianyan.app.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 会话级红队绑定字段（83 → 84）。
 *
 * 四个新列全部带默认值：老会话升级后一律是「未开启红队」，不会因为新增列而获得红队能力。
 * 这是有意的 —— 红队是显式开启的能力，不能靠升级默认打开。
 *
 * **DEFAULT 子句必须与实体 `@ColumnInfo(defaultValue)` 逐字一致**：
 * Room 迁移后会用 `PRAGMA table_info` 的 `dflt_value` 对照导出的 84.json 校验，
 * 不一致时抛 `IllegalStateException`，设备上表现为**启动即崩**。
 * 特别是 [HarnessSessionEntity.redTeamTarget]：可空列在实体侧声明了
 * `@ColumnInfo(defaultValue = "NULL")`，因此这里也必须写 `DEFAULT NULL`
 * （写成不带 DEFAULT 会得到 `dflt_value = null`，与 `"NULL"` 不等）。
 *
 * 幂等：v1.5.1 曾以错误的 schema 发布过 84 版，若某台设备的库已经带上部分列，
 * 重复 `ADD COLUMN` 会直接失败；先探测再添加，保证这类设备也能被修复。
 */
val MIGRATION_83_84 = object : Migration(83, 84) {
    override fun migrate(db: SupportSQLiteDatabase) {
        addColumnIfAbsent(db, "redTeamMode", "TEXT NOT NULL DEFAULT 'off'")
        addColumnIfAbsent(db, "redTeamTarget", "TEXT DEFAULT NULL")
        addColumnIfAbsent(db, "redTeamScope", "TEXT NOT NULL DEFAULT ''")
        addColumnIfAbsent(db, "redTeamPhase", "TEXT NOT NULL DEFAULT 'idle'")
    }

    private fun addColumnIfAbsent(db: SupportSQLiteDatabase, column: String, declaration: String) {
        val exists = db.query("PRAGMA table_info(`harness_sessions`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) {
                    found = true
                    break
                }
            }
            found
        }
        if (!exists) {
            db.execSQL("ALTER TABLE `harness_sessions` ADD COLUMN `$column` $declaration")
        }
    }
}
