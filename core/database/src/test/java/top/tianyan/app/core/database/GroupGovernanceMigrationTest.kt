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
class GroupGovernanceMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())
    @Test fun `migration preserves old groups without creating proposals or executions`() {
        val path = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath("governance-migration").absolutePath
        helper.createDatabase(path, 76).use { db ->
            db.execSQL("""INSERT INTO persona_bots (id,name,role,systemPrompt,avatarSeed,sessionId,isMainBot,colorHex,createdAt,updatedAt)
                VALUES ('chief','Chief','lead','','seed','dm',1,'#123456',1,1)""")
            db.execSQL("INSERT INTO group_chats(id,name,adminBotId,description,createdAt,background) VALUES ('g','Old','chief','',1,'default')")
            db.execSQL("INSERT INTO group_members(groupId,botId,displayOrder,joinedAt) VALUES ('g','chief',0,1)")
        }
        helper.runMigrationsAndValidate(path, 77, true, MIGRATION_76_77).use { db ->
            db.query("SELECT name FROM group_chats WHERE id='g'").use { assertTrue(it.moveToFirst()); assertEquals("Old", it.getString(0)) }
            listOf("group_governance", "group_proposals", "bot_assignment_requests").forEach { table ->
                db.query("SELECT COUNT(*) FROM $table").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            }
        }
    }
}
