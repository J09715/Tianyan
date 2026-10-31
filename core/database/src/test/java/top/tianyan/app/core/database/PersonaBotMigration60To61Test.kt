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
class PersonaBotMigration60To61Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun `adds delivery receipts without changing existing bots or schedules`() {
        val path = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath("bot-60-61.db").absolutePath
        helper.createDatabase(path, 60).use { db ->
            db.execSQL("""INSERT INTO persona_bots VALUES ('bot', 'Chief', 'lead', 'persona', 'seed', NULL, NULL, 'dm', 1, '#123456', 1, 1)""")
            db.execSQL("""INSERT INTO bot_schedules VALUES ('schedule', 'bot', NULL, '* * * * *', 'work', 1, 100, NULL, 1)""")
        }
        helper.runMigrationsAndValidate(path, 61, true, MIGRATION_60_61).use { db ->
            db.query("SELECT instruction, nextRunAt FROM bot_schedules").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("work", cursor.getString(0))
                assertEquals(100L, cursor.getLong(1))
            }
            db.query("SELECT COUNT(*) FROM bot_schedule_deliveries").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }
}
