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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BotMemberGroupMigration64To65Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun `migration adds reusable member groups without touching bots`() {
        val path = InstrumentationRegistry.getInstrumentation().targetContext
            .getDatabasePath("bot-64-65.db").absolutePath
        helper.createDatabase(path, 64).use { db ->
            db.execSQL(
                """INSERT INTO persona_bots
                (id,name,role,systemPrompt,avatarSeed,modelId,providerId,sessionId,isMainBot,colorHex,createdAt,updatedAt,modelVariant)
                VALUES ('bot','Bot','coder','persona','seed',NULL,NULL,'session',0,'#123456',1,1,NULL)""",
            )
        }
        helper.runMigrationsAndValidate(path, 65, true, MIGRATION_64_65).use { db ->
            db.execSQL("INSERT INTO bot_member_groups (id,name,createdAt) VALUES ('group','开发组',1)")
            db.execSQL(
                "INSERT INTO bot_member_group_members (memberGroupId,botId,displayOrder,addedAt) " +
                    "VALUES ('group','bot',0,1)",
            )
            db.query(
                "SELECT g.name, m.botId FROM bot_member_groups g " +
                    "INNER JOIN bot_member_group_members m ON m.memberGroupId = g.id",
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals("开发组", it.getString(0))
                assertEquals("bot", it.getString(1))
            }
            db.query("SELECT name FROM persona_bots WHERE id = 'bot'").use {
                assertTrue(it.moveToFirst())
                assertEquals("Bot", it.getString(0))
            }
        }
    }
}
