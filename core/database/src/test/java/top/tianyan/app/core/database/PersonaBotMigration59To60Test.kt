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

/** MIGRATION_59_60：群背景、附件、表情回应与工具步骤列。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersonaBotMigration59To60Test {

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
    fun `adds enhanced group columns with usable defaults`() {
        helper.createDatabase(dbPath, 59).close()

        helper.runMigrationsAndValidate(dbPath, 60, true, MIGRATION_59_60).use { db ->
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
                """INSERT INTO group_messages
                   (id, groupId, senderId, senderName, senderType, content, timestamp, isStreaming, delegatedTaskId)
                   VALUES ('msg_1', 'group_1', 'user_me', '我', 'USER', '你好', 300, 0, NULL)""",
            )
            db.execSQL(
                """INSERT INTO group_messages
                   (id, groupId, senderId, senderName, senderType, content, timestamp, isStreaming, delegatedTaskId,
                    attachmentsJson, reactionsJson, stepsJson)
                   VALUES ('msg_2', 'group_1', 'bot_1', 'Chief', 'BOT', '完成', 400, 0, NULL, '[]', '[]', '[]')""",
            )

            db.query("SELECT background FROM group_chats WHERE id = 'group_1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("default", cursor.getString(0))
            }
            db.query(
                "SELECT attachmentsJson, reactionsJson, stepsJson FROM group_messages WHERE id = 'msg_1'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("[]", cursor.getString(0))
                assertEquals("[]", cursor.getString(1))
                assertEquals("[]", cursor.getString(2))
            }
        }
    }

    private companion object {
        const val TEST_DB = "persona-bot-migration-59-60.db"
    }
}
