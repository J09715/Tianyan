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

/** MIGRATION_56_57：PersonaBot 名册、群聊、群消息与 Bot 定时任务。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersonaBotMigration56To57Test {

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
    fun `creates persona bot group tables and accepts rows`() {
        helper.createDatabase(dbPath, 56).close()

        helper.runMigrationsAndValidate(dbPath, 57, true, MIGRATION_56_57).use { db ->
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
                """INSERT INTO group_members (groupId, botId, displayOrder, joinedAt)
                   VALUES ('group_1', 'bot_1', 0, 200)""",
            )
            db.execSQL(
                """INSERT INTO group_messages
                   (id, groupId, senderId, senderName, senderType, content, timestamp, isStreaming, delegatedTaskId)
                   VALUES ('msg_1', 'group_1', 'user_me', '我', 'USER', '你好', 300, 0, NULL)""",
            )
            db.execSQL(
                """INSERT INTO bot_schedules
                   (id, botId, groupId, cron, instruction, enabled, nextRunAt, lastRunAt, createdAt)
                   VALUES ('sched_1', 'bot_1', 'group_1', '0 9 * * *', '巡检', 1, 400, NULL, 300)""",
            )

            db.query(
                "SELECT isMainBot, sessionId FROM persona_bots WHERE id = 'bot_1'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
                assertEquals("sess_1", cursor.getString(1))
            }
            db.query(
                "SELECT g.name, m.botId FROM group_chats g JOIN group_members m ON g.id = m.groupId",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("协作群", cursor.getString(0))
                assertEquals("bot_1", cursor.getString(1))
            }
            db.query(
                "SELECT senderType, isStreaming FROM group_messages WHERE id = 'msg_1'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("USER", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
            }
            db.query(
                "SELECT cron, enabled FROM bot_schedules WHERE id = 'sched_1'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("0 9 * * *", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
            }
        }
    }

    private companion object {
        const val TEST_DB = "persona-bot-migration-56-57.db"
    }
}
