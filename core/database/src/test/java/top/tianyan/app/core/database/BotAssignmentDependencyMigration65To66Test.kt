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
class BotAssignmentDependencyMigration65To66Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun `migration adds an empty dependency list without touching assignments`() {
        val path = InstrumentationRegistry.getInstrumentation().targetContext
            .getDatabasePath("assignment-65-66.db").absolutePath
        helper.createDatabase(path, 65).use { db ->
            db.execSQL(
                """INSERT INTO bot_assignments
                (id,groupId,botId,botName,title,task,status,writePathsJson,createdAt,startedAt,
                 finishedAt,summary,error,sessionId,laneName,reportToBotId,independent,revision,activeRequestId)
                VALUES ('task',NULL,'bot','Bot','Build','Build','RUNNING','[]',1,1,NULL,NULL,NULL,NULL,NULL,NULL,0,0,NULL)""",
            )
        }
        helper.runMigrationsAndValidate(path, 66, true, MIGRATION_65_66).use { db ->
            db.query("SELECT dependsOnJson FROM bot_assignments WHERE id = 'task'").use {
                assertTrue(it.moveToFirst())
                assertEquals("[]", it.getString(0))
            }
            db.execSQL("UPDATE bot_assignments SET dependsOnJson = '[\"task-a\"]' WHERE id = 'task'")
            db.query("SELECT dependsOnJson FROM bot_assignments WHERE id = 'task'").use {
                assertTrue(it.moveToFirst())
                assertEquals("[\"task-a\"]", it.getString(0))
            }
        }
    }
}
