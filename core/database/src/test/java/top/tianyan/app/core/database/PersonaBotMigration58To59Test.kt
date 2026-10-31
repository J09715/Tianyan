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

/** MIGRATION_58_59：Bot × 群聊独立会话映射。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersonaBotMigration58To59Test {

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
    fun `creates bot group session mapping and enforces one row per bot and group`() {
        helper.createDatabase(dbPath, 58).close()

        helper.runMigrationsAndValidate(dbPath, 59, true, MIGRATION_58_59).use { db ->
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
                """INSERT INTO bot_group_sessions (botId, groupId, sessionId, createdAt)
                   VALUES ('bot_1', 'group_1', 'sess_g1', 300)""",
            )
            db.execSQL(
                """INSERT OR REPLACE INTO bot_group_sessions (botId, groupId, sessionId, createdAt)
                   VALUES ('bot_1', 'group_1', 'sess_g2', 400)""",
            )

            db.query(
                "SELECT sessionId FROM bot_group_sessions WHERE botId = 'bot_1' AND groupId = 'group_1'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("sess_g2", cursor.getString(0))
                assertTrue(!cursor.moveToNext())
            }
        }
    }

    private companion object {
        const val TEST_DB = "persona-bot-migration-58-59.db"
    }
}
