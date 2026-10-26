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
class PersonaBotMigration62To63Test {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory())
    @Test fun `legacy tasks retain their identity and never acquire an inferred independent session`() {
        val path = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath("bot-62-63.db").absolutePath
        helper.createDatabase(path, 62).use { db ->
            db.execSQL("""INSERT INTO bot_assignments (id,groupId,botId,botName,title,task,status,writePathsJson,createdAt,startedAt,sessionId)
                VALUES ('old',NULL,'bot','Bot','Work','original','FAILED','[]',1,1,'old-group-session')""")
        }
        helper.runMigrationsAndValidate(path, 63, true, MIGRATION_62_63).use { db ->
            db.query("SELECT sessionId, independent, revision FROM bot_assignments WHERE id = 'old'").use {
                assertTrue(it.moveToFirst())
                assertEquals("old-group-session", it.getString(0))
                assertEquals(0, it.getInt(1))
                assertEquals(0, it.getInt(2))
            }
            db.query("SELECT COUNT(*) FROM bot_assignment_requests").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        }
    }
}
