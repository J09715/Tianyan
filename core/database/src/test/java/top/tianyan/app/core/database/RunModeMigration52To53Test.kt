package top.tianyan.app.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PLAN 只读规划的运行意图列迁移（52 → 53）。
 *
 * runMigrationsAndValidate 会对照导出的 53.json 校验最终 schema，因此本测试同时守住
 * 「迁移 SQL 的 DEFAULT 子句与实体 @ColumnInfo(defaultValue) 一致」这条铁律——
 * 两者不一致时迁移后校验失败，设备上表现为启动即崩（provideDatabase 未配置 fallback）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RunModeMigration52To53Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        // Room 2.8 默认 JVM driver 与 Robolectric 的 databasePath 解析不一致（裸名 vs 绝对路径），
        // 显式指定 framework factory 走统一的 getDatabasePath 逻辑。
        FrameworkSQLiteOpenHelperFactory(),
    )

    // createDatabase 接受裸名而 runMigrationsAndValidate 内部解析为绝对路径，两边不一致会触发
    // "driver is configured" 守卫；统一改传绝对路径（见 WorkflowMigration48To49Test）。
    private val dbPath: String =
        InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(TEST_DB).absolutePath

    // dflt_value 对无默认值的列是 NULL，故值类型可空。
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
    fun `migration adds run mode columns with build default`() {
        helper.createDatabase(dbPath, 52).close()

        helper.runMigrationsAndValidate(dbPath, 53, true, MIGRATION_52_53).use { db ->
            assertEquals("'build'", defaultsOf(db, "agent_approval_settings")["runMode"])
            assertEquals("'build'", defaultsOf(db, "harness_sessions")["runMode"])
        }
    }

    /** 开发期存在过「52 已含 runMode」的中间构建，迁移必须幂等而不能因列重复失败。 */
    @Test
    fun `migration is idempotent when run mode columns already exist`() {
        helper.createDatabase(dbPath, 52).use { db ->
            db.execSQL("ALTER TABLE `agent_approval_settings` ADD COLUMN `runMode` TEXT NOT NULL DEFAULT 'build'")
            db.execSQL("ALTER TABLE `harness_sessions` ADD COLUMN `runMode` TEXT NOT NULL DEFAULT 'build'")
        }

        helper.runMigrationsAndValidate(dbPath, 53, true, MIGRATION_52_53).use { db ->
            val sessions = defaultsOf(db, "harness_sessions")
            assertEquals("'build'", sessions["runMode"])
            // 列只应存在一份（PRAGMA 结果按名去重后仍是单条 runMode）
            assertTrue(sessions.containsKey("runMode"))
        }
    }

    private companion object {
        const val TEST_DB = "run-mode-migration"
    }
}