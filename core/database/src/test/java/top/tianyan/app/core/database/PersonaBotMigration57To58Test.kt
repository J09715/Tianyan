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

/** MIGRATION_57_58：委派任务表，支持群聊归属与无群聊的同步委派。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersonaBotMigration57To58Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val dbPath: String =
        InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(TEST_DB).absolutePath

    @Test
    fun `creates assignment table and accepts grouped and standalone rows`() {
        helper.createDatabase(dbPath, 57).close()

        helper.runMigrationsAndValidate(dbPath, 58, true, MIGRATION_57_58).use { db ->
            db.execSQL(
                """INSERT INTO persona_bots
                   (id, name, role, systemPrompt, avatarSeed, modelId, providerId, sessionId, isMainBot, colorHex, createdAt, updatedAt)
                   VALUES ('bot_1', 'Chief', '主控', 'persona', 'seed', NULL, NULL, 'sess_1', 1, '#3D5AFE', 100, 100)""",
            )
            db.execSQL(
                """INSERT INTO group_chats (id, name, adminBotId, description, createdAt)
                   VALUES ('group_1', '协作群', 'bot_1', 'desc', 200)""",
            )
            db.execSQL(
                """INSERT INTO bot_assignments
                   (id, groupId, botId, botName, title, task, status, writePathsJson, createdAt, startedAt, finishedAt, summary, error, sessionId, laneName)
                   VALUES ('task_1', 'group_1', 'bot_1', 'Chief', '巡检', '检查构建', 'RUNNING', '[]', 300, 300, NULL, NULL, NULL, 'sess_1', 'lane_1')""",
            )
            db.execSQL(
                """INSERT INTO bot_assignments
                   (id, groupId, botId, botName, title, task, status, writePathsJson, createdAt, startedAt, finishedAt, summary, error, sessionId, laneName)
                   VALUES ('task_2', NULL, 'bot_1', 'Chief', '同步任务', '只读检查', 'COMPLETED', '["/root/a"]', 400, 400, 500, '完成', NULL, 'sess_1', NULL)""",
            )

            db.query(
                "SELECT groupId, status, summary FROM bot_assignments WHERE id = 'task_1'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("group_1", cursor.getString(0))
                assertEquals("RUNNING", cursor.getString(1))
                assertTrue(cursor.isNull(2))
            }
            db.query(
                "SELECT groupId, status, summary, writePathsJson FROM bot_assignments WHERE id = 'task_2'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertEquals("COMPLETED", cursor.getString(1))
                assertEquals("完成", cursor.getString(2))
                assertEquals("[\"/root/a\"]", cursor.getString(3))
            }
        }
    }

    private companion object {
        const val TEST_DB = "persona-bot-migration-57-58.db"
    }
}
