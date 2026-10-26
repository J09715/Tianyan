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
class GroupRoundMigration69To70Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun `migration creates round and late reply tables`() {
        val path = InstrumentationRegistry.getInstrumentation().targetContext
            .getDatabasePath("group-round-69-70.db").absolutePath
        helper.createDatabase(path, 69).use { db ->
            db.execSQL(
                """INSERT INTO persona_bots
                (id,name,role,systemPrompt,avatarSeed,modelId,providerId,sessionId,isMainBot,colorHex,createdAt,updatedAt,modelVariant)
                VALUES ('bot','Bot','role','persona','seed',NULL,NULL,'dm',0,'#000000',1,1,NULL)""",
            )
            db.execSQL(
                "INSERT INTO group_chats (id,name,adminBotId,description,createdAt,background,replyMode) " +
                    "VALUES ('group','Team','bot','',1,'default','ADMIN_FIRST')",
            )
        }
        helper.runMigrationsAndValidate(path, 70, true, MIGRATION_69_70).use { db ->
            db.execSQL(
                """INSERT INTO group_rounds
                (id,groupId,trigger,imageUrlsJson,slotsJson,status,createdAt,updatedAt)
                VALUES ('round','group','hello','[]','[]','RUNNING',1,1)""",
            )
            db.query("SELECT status FROM group_rounds WHERE id = 'round'").use {
                assertTrue(it.moveToFirst())
                assertEquals("RUNNING", it.getString(0))
            }
            db.execSQL(
                """INSERT INTO group_late_replies
                (id,groupId,workerId,workerName,sessionId,placeholderId,callerBotId,createdAt,status,error)
                VALUES ('late','group','bot','Bot','session','placeholder',NULL,1,'WATCHING',NULL)""",
            )
            db.query("SELECT status FROM group_late_replies WHERE id = 'late'").use {
                assertTrue(it.moveToFirst())
                assertEquals("WATCHING", it.getString(0))
            }
        }
    }
}
