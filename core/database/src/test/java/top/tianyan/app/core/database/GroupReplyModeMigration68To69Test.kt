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
class GroupReplyModeMigration68To69Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun `migration defaults existing groups to admin first and accepts all members`() {
        val path = InstrumentationRegistry.getInstrumentation().targetContext
            .getDatabasePath("group-reply-mode-68-69.db").absolutePath
        helper.createDatabase(path, 68).use { db ->
            db.execSQL(
                """INSERT INTO persona_bots
                (id,name,role,systemPrompt,avatarSeed,modelId,providerId,sessionId,isMainBot,colorHex,createdAt,updatedAt,modelVariant)
                VALUES ('bot','Bot','role','persona','seed',NULL,NULL,'dm',0,'#000000',1,1,NULL)""",
            )
            db.execSQL(
                "INSERT INTO group_chats (id,name,adminBotId,description,createdAt,background) " +
                    "VALUES ('group','Team','bot','',1,'default')",
            )
        }
        helper.runMigrationsAndValidate(path, 69, true, MIGRATION_68_69).use { db ->
            db.query("SELECT replyMode FROM group_chats WHERE id = 'group'").use {
                assertTrue(it.moveToFirst())
                assertEquals("ADMIN_FIRST", it.getString(0))
            }
            db.execSQL("UPDATE group_chats SET replyMode = 'ALL_MEMBERS' WHERE id = 'group'")
            db.query("SELECT replyMode FROM group_chats WHERE id = 'group'").use {
                assertTrue(it.moveToFirst())
                assertEquals("ALL_MEMBERS", it.getString(0))
            }
        }
    }
}
