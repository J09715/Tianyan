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
 * MIGRATION_80_81：审批审计台账表 + harness_entries.payloadVersion 列（旧行回填 1）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApprovalAuditMigration80To81Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        // Room 2.8 默认 JVM driver 与 Robolectric 的 databasePath 解析不一致，须显式 framework factory
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val dbPath: String =
        InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(TEST_DB).absolutePath

    @Test
    fun `legacy entries backfill payload version 1 and new column accepts writes`() {
        helper.createDatabase(dbPath, 80).use { db ->
            db.execSQL(
                """INSERT INTO harness_entries (id, sessionId, parentId, createdAt, entryType, customType, payloadJson)
                   VALUES ('e1', 'sess_1', NULL, 100, 'message', 'user', '{"type":"user"}')""",
            )
        }

        helper.runMigrationsAndValidate(dbPath, 81, true, MIGRATION_80_81).use { db ->
            db.query("SELECT payloadVersion FROM harness_entries WHERE id = 'e1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            db.execSQL(
                """INSERT INTO harness_entries (id, sessionId, parentId, createdAt, entryType, customType, payloadJson, payloadVersion)
                   VALUES ('e2', 'sess_1', 'e1', 200, 'message', 'user', '{"type":"user"}', 1)""",
            )
        }
    }

    @Test
    fun `creates approval audit table and accepts rows`() {
        helper.createDatabase(dbPath, 80).close()

        helper.runMigrationsAndValidate(dbPath, 81, true, MIGRATION_80_81).use { db ->
            db.execSQL(
                """INSERT INTO agent_approval_audits (id, sessionId, kind, toolName, toolCallId,
                   riskLevel, grantKey, approvalRequestId, detail, createdAt)
                   VALUES ('a1', 'sess_1', 'grant_exempted', 'write', 'call_1',
                   'normal', 'dir:/data/ws', NULL, NULL, 100)""",
            )
            db.query("SELECT kind, grantKey, riskLevel FROM agent_approval_audits WHERE id = 'a1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("grant_exempted", cursor.getString(0))
                assertEquals("dir:/data/ws", cursor.getString(1))
                assertEquals("normal", cursor.getString(2))
            }
        }
    }

    private companion object {
        const val TEST_DB = "approval-audit-migration-test.db"
    }
}
