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
class GroupWorkMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test fun `82 to 83 retains legacy task identity and adds nullable correlation and receipts`() {
        val path = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath("group-work-migration.db").absolutePath
        helper.createDatabase(path, 82).use { db ->
            db.execSQL("INSERT INTO agent_tasks(id, title, description, status, createdAt, updatedAt, progress) VALUES ('old', 'old', 'keep me', 'RUNNING', 1, 2, 0)")
        }
        helper.runMigrationsAndValidate(path, 83, true, MIGRATION_82_83).use { db ->
            db.query("SELECT description, groupWorkId, groupId, groupProgressJson FROM agent_tasks WHERE id = 'old'").use {
                assertTrue(it.moveToFirst())
                assertEquals("keep me", it.getString(0))
                assertTrue(it.isNull(1) && it.isNull(2) && it.isNull(3))
            }
        }
    }
}
