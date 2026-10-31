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

/** MIGRATION_81_82：单条 assistant 消息的用户反馈表（messageId 唯一）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessageFeedbackMigration81To82Test {
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
    fun `creates feedback table with unique message index and accepts rows`() {
        helper.createDatabase(dbPath, 81).close()

        helper.runMigrationsAndValidate(dbPath, 82, true, MIGRATION_81_82).use { db ->
            db.execSQL(
                """INSERT INTO agent_message_feedback (id, sessionId, messageId, rating, note, createdAt, updatedAt)
                   VALUES ('fb_m1', 'sess_1', 'm1', 'up', NULL, 100, 100)""",
            )
            db.query("SELECT rating, note FROM agent_message_feedback WHERE messageId = 'm1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("up", cursor.getString(0))
                assertTrue(cursor.isNull(1))
            }
        }
    }

    private companion object {
        const val TEST_DB = "message-feedback-migration-test.db"
    }
}
