package top.tianyan.app.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupReplyTaskMigration71To72Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun `legacy watchers survive migration and new watchers retain their native task`() {
        val path = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath("group-reply-71-72.db").absolutePath
        helper.createDatabase(path, 71).use { db ->
            db.execSQL("""INSERT INTO persona_bots
                (id,name,role,systemPrompt,avatarSeed,modelId,providerId,sessionId,isMainBot,colorHex,createdAt,updatedAt,modelVariant)
                VALUES ('bot','Bot','role','persona','seed',NULL,NULL,'dm',0,'#000000',1,1,NULL)""")
            db.execSQL("INSERT INTO group_chats (id,name,adminBotId,description,createdAt,background,replyMode) VALUES ('group','Team','bot','',1,'default','ADMIN_FIRST')")
            db.execSQL("""INSERT INTO group_late_replies
                (id,groupId,workerId,workerName,sessionId,placeholderId,callerBotId,createdAt,status,error)
                VALUES ('late','group','bot','Bot','session','placeholder',NULL,1,'WATCHING',NULL)""")
        }
        helper.runMigrationsAndValidate(path, 72, true, MIGRATION_71_72).use { db ->
            db.query("SELECT taskId, status FROM group_late_replies WHERE id = 'late'").use {
                assertTrue(it.moveToFirst())
                assertTrue(it.isNull(0))
                assertEquals("WATCHING", it.getString(1))
            }
            db.execSQL("UPDATE group_late_replies SET taskId = 'native-task' WHERE id = 'late'")
            db.query("SELECT taskId FROM group_late_replies WHERE id = 'late'").use {
                assertTrue(it.moveToFirst())
                assertEquals("native-task", it.getString(0))
            }
        }
    }
}
