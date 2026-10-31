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
class GroupTeamMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test fun `upgrade keeps membership and historical task ownership without running tasks`() {
        val path = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath("group-team-migration").absolutePath
        helper.createDatabase(path, 74).use { db ->
            db.execSQL("""INSERT INTO persona_bots
                (id,name,role,systemPrompt,avatarSeed,sessionId,isMainBot,colorHex,createdAt,updatedAt)
                VALUES ('chief','Chief','lead','','seed','dm',1,'#123456',1,1)""")
            db.execSQL("INSERT INTO group_chats(id,name,adminBotId,description,createdAt,background) VALUES ('g','Team','chief','',1,'default')")
            db.execSQL("INSERT INTO group_members(groupId,botId,displayOrder,joinedAt) VALUES ('g','chief',0,1)")
        }
        helper.runMigrationsAndValidate(path, 75, true, MIGRATION_74_75).use { db ->
            db.query("SELECT botId,supervisorBotId FROM group_members WHERE groupId='g'").use {
                assertTrue(it.moveToFirst())
                assertEquals("chief", it.getString(0))
                assertTrue(it.isNull(1))
            }
            db.query("SELECT COUNT(*) FROM bot_assignment_requests").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        }
    }
}
