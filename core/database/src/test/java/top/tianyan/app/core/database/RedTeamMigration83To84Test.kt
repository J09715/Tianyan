package top.tianyan.app.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 会话红队绑定列的迁移（83 → 84）。
 *
 * `runMigrationsAndValidate` 会对照导出的 84.json 校验最终 schema，因此本测试守住
 * 「迁移 SQL 的 DEFAULT 子句与实体 `@ColumnInfo(defaultValue)` 一致」这条铁律。
 *
 * 这条铁律不是洁癖：v1.5.1 就是因为 `redTeamTarget` 的可空列在实体侧没写
 * `@ColumnInfo(defaultValue = "NULL")`、迁移却写了 `DEFAULT NULL`，
 * 导致升级用户的 Room 校验失败、**启动即崩**。本测试即是那次事故的回归护栏。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RedTeamMigration83To84Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val dbPath: String =
        InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(TEST_DB).absolutePath

    private fun defaultsOf(db: SupportSQLiteDatabase, table: String): Map<String, String?> =
        buildMap {
            db.query("PRAGMA table_info(`$table`)").use { cursor ->
                while (cursor.moveToNext()) {
                    put(
                        cursor.getString(cursor.getColumnIndexOrThrow("name")),
                        cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")),
                    )
                }
            }
        }

    @Test
    fun `migration adds red team columns with off defaults`() {
        helper.createDatabase(dbPath, 83).close()

        helper.runMigrationsAndValidate(dbPath, 84, true, MIGRATION_83_84).use { db ->
            val cols = defaultsOf(db, "harness_sessions")
            assertEquals("'off'", cols["redTeamMode"])
            // 可空列：实体声明 defaultValue = "NULL"，故这里必须是字面量 "NULL"
            assertEquals("NULL", cols["redTeamTarget"])
            assertEquals("''", cols["redTeamScope"])
            assertEquals("'idle'", cols["redTeamPhase"])
        }
    }

    /**
     * 老会话升级后不得自动获得红队能力。
     *
     * 红队会改变工具的审批与提示词路径，默认打开等于让所有历史会话突然具备红队语义。
     */
    @Test
    fun `existing sessions stay out of red team after upgrade`() {
        helper.createDatabase(dbPath, 83).use { db ->
            db.execSQL(
                "INSERT INTO harness_sessions (id, title, createdAt, updatedAt, modelId) " +
                    "VALUES ('legacy', 'Legacy', 1, 1, NULL)",
            )
        }

        helper.runMigrationsAndValidate(dbPath, 84, true, MIGRATION_83_84).use { db ->
            db.query(
                "SELECT redTeamMode, redTeamTarget, redTeamScope, redTeamPhase " +
                    "FROM harness_sessions WHERE id = 'legacy'",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals("off", cursor.getString(0))
                assertEquals(null, cursor.getString(1))
                assertEquals("", cursor.getString(2))
                assertEquals("idle", cursor.getString(3))
            }
        }
    }

    /** v1.5.1 的坏 schema 可能让部分设备已带上这些列，迁移必须幂等而不是因列重复失败。 */
    @Test
    fun `migration is idempotent when red team columns already exist`() {
        helper.createDatabase(dbPath, 83).use { db ->
            db.execSQL("ALTER TABLE `harness_sessions` ADD COLUMN `redTeamMode` TEXT NOT NULL DEFAULT 'off'")
            db.execSQL("ALTER TABLE `harness_sessions` ADD COLUMN `redTeamTarget` TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE `harness_sessions` ADD COLUMN `redTeamScope` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `harness_sessions` ADD COLUMN `redTeamPhase` TEXT NOT NULL DEFAULT 'idle'")
        }

        helper.runMigrationsAndValidate(dbPath, 84, true, MIGRATION_83_84).use { db ->
            val cols = defaultsOf(db, "harness_sessions")
            assertEquals("'off'", cols["redTeamMode"])
            assertEquals("NULL", cols["redTeamTarget"])
        }
    }

    private companion object {
        const val TEST_DB = "red-team-migration"
    }
}
