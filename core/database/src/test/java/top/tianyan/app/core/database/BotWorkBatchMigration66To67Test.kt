package top.tianyan.app.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BotWorkBatchMigration66To67Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun `migration adds batch ownership without touching existing assignments`() {
        val path = InstrumentationRegistry.getInstrumentation().targetContext
            .getDatabasePath("assignment-66-67.db").absolutePath
        helper.createDatabase(path, 66).use { db ->
            db.execSQL(
                """INSERT INTO bot_assignments
                (id,groupId,botId,botName,title,task,status,writePathsJson,createdAt,startedAt,
                 finishedAt,summary,error,sessionId,laneName,reportToBotId,independent,revision,activeRequestId)
                VALUES ('task',NULL,'bot','Bot','Build','Build','RUNNING','[]',1,1,NULL,NULL,NULL,NULL,NULL,NULL,0,0,NULL)""",
            )
        }
        helper.runMigrationsAndValidate(path, 67, true, MIGRATION_66_67).use { db ->
            db.query("SELECT batchId FROM bot_assignments WHERE id = 'task'").use {
                assertTrue(it.moveToFirst())
                assertNull(it.getString(0))
            }
            db.execSQL("UPDATE bot_assignments SET batchId = 'work' WHERE id = 'task'")
            db.query("SELECT batchId FROM bot_assignments WHERE id = 'task'").use {
                assertTrue(it.moveToFirst())
                assertEquals("work", it.getString(0))
            }
            val columns = mutableSetOf<String>()
            db.query("PRAGMA table_info(bot_work_batches)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
            }
            assertTrue(
                columns.containsAll(
                    setOf(
                        "id", "groupId", "requesterBotId", "requesterSessionId", "originOperationId",
                        "revision", "reportRevision", "status", "createdAt", "updatedAt", "settledAt",
                    ),
                ),
            )
        }
    }
}
