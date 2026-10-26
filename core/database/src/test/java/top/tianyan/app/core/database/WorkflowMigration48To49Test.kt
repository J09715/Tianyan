package top.tianyan.app.core.database

import androidx.room.testing.MigrationTestHelper
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
 * MIGRATION_48_49：新增 workflow_schedules 表与 workflow_execution_logs 的
 * triggerSource/workflowName/scheduleId 列。遵循 KNOWN_ISSUES 铁律——只做正向迁移，
 * 永不回退 destructive migration。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkflowMigration48To49Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        // Room 2.8 默认 JVM driver 与 Robolectric 的 databasePath 解析不一致（裸名 vs 绝对路径），
        // 显式指定 framework factory 走统一的 getDatabasePath 逻辑
        FrameworkSQLiteOpenHelperFactory(),
    )

    // createDatabase 接受裸名而 runMigrationsAndValidate 内部解析为绝对路径，两边不一致会触发
    // "driver is configured" 守卫；统一改传绝对路径
    private val dbPath: String =
        InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(TEST_DB).absolutePath

    @Test
    fun `migrates execution logs with default trigger provenance`() {
        helper.createDatabase(dbPath, 48).use { db ->
            db.execSQL(
                """INSERT INTO workflows (id, name, description, category, isBuiltin, slashCommand, jsonContent, updatedAt)
                   VALUES ('wf_test', '测试流程', 'desc', 'general', 0, NULL, '{}', 100)""",
            )
            db.execSQL(
                """INSERT INTO workflow_execution_logs (executionId, workflowId, startTime, endTime, status, finalContextJson)
                   VALUES ('exec_1', 'wf_test', 100, 200, 'SUCCESS', '{}')""",
            )
        }

        helper.runMigrationsAndValidate(dbPath, 49, true, MIGRATION_48_49).use { db ->
            db.query("SELECT triggerSource, workflowName, scheduleId FROM workflow_execution_logs WHERE executionId = 'exec_1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("MANUAL", cursor.getString(0))
                assertEquals("", cursor.getString(1))
                assertTrue(cursor.isNull(2))
            }
        }
    }

    @Test
    fun `migrated schema accepts schedule rows and run info updates`() {
        helper.createDatabase(dbPath, 48).close()

        helper.runMigrationsAndValidate(dbPath, 49, true, MIGRATION_48_49).use { db ->
            db.execSQL(
                """INSERT INTO workflows (id, name, description, category, isBuiltin, slashCommand, jsonContent, updatedAt)
                   VALUES ('wf_test', '测试流程', 'desc', 'general', 0, NULL, '{}', 100)""",
            )
            db.execSQL(
                """INSERT INTO workflow_schedules (id, workflowId, name, enabled, repeatType, hour, minute, intervalMinutes,
                   onceAtEpochMillis, variablesJson, workspacePath, modelId, modelVariant, lastExecutionId, lastRunAt, nextRunAt, createdAt)
                   VALUES ('sched_1', 'wf_test', '每日报表', 1, 'DAILY', 9, 30, NULL, NULL, '{}', '/workspace/demo', NULL, NULL, NULL, NULL, NULL, 100)""",
            )
            db.execSQL(
                "UPDATE workflow_schedules SET lastExecutionId = 'exec_9', lastRunAt = 500, nextRunAt = 600 WHERE id = 'sched_1'",
            )
            db.query("SELECT enabled, repeatType, lastExecutionId, nextRunAt FROM workflow_schedules WHERE id = 'sched_1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
                assertEquals("DAILY", cursor.getString(1))
                assertEquals("exec_9", cursor.getString(2))
                assertEquals(600L, cursor.getLong(3))
            }
            // 注：级联删除由 Room 实体定义在运行时保证；helper 打开的裸连接默认不启用外键
            // pragma，不在迁移测试范围内
        }
    }

    private companion object {
        const val TEST_DB = "migration-48-49-test.db"
    }
}
