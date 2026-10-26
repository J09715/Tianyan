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
class PersonaBotMigration61To62Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun `upgrade retains existing conversation and does not enqueue historical work`() {
        val path = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath("bot-61-62.db").absolutePath
        helper.createDatabase(path, 61).use { db ->
            db.execSQL("INSERT INTO persona_bots VALUES ('bot','Chief','lead','persona','seed',NULL,NULL,'dm',1,'#123456',1,1)")
            db.execSQL("INSERT INTO agent_approval_settings VALUES (1,'assisted','build')")
            db.execSQL("INSERT INTO harness_sessions (id,title,createdAt,updatedAt,modelId,workspace,projectType,approvalMode) VALUES ('old','chat',1,1,NULL,'','','assisted')")
            db.execSQL("INSERT INTO group_chats VALUES ('group','Team','bot','',1,'default')")
            db.execSQL("""INSERT INTO group_messages (id,groupId,senderId,senderName,senderType,content,timestamp,isStreaming,delegatedTaskId,attachmentsJson,reactionsJson,stepsJson)
                VALUES ('source','group','user','Me','USER','existing message',1,0,NULL,'[]','[]','[]')""")
        }
        helper.runMigrationsAndValidate(path, 62, true, MIGRATION_61_62).use { db ->
            db.query("SELECT content FROM group_messages").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("existing message", cursor.getString(0))
            }
            db.query("SELECT approvalMode FROM harness_sessions WHERE id = 'old'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("full_access", cursor.getString(0))
            }
            db.query("SELECT mode FROM agent_approval_settings").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("full_access", cursor.getString(0))
            }
            db.query("SELECT COUNT(*) FROM group_deliveries").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }
}
