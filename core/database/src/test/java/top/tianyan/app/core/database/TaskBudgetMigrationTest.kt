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
class TaskBudgetMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())
    @Test fun `upgrade creates empty accounting without charging or executing historical tasks`() {
        val path = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath("task-budget-migration").absolutePath
        helper.createDatabase(path, 75).close()
        helper.runMigrationsAndValidate(path, 76, true, MIGRATION_75_76).use { db ->
            db.query("SELECT COUNT(*) FROM bot_budget_requests").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        }
    }
}
