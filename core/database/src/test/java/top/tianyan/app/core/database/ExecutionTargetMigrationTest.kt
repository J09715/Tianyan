package top.tianyan.app.core.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExecutionTargetMigrationTest {
    @get:Rule val folder = org.junit.rules.TemporaryFolder()
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory())
    @Test fun `upgrade preserves pre SSH local history for lazy binding`() {
        val path = java.io.File(folder.root, "upgrade-54.db").absolutePath
        helper.createDatabase(path, 54).use {
            it.execSQL("INSERT INTO harness_sessions(id,title,createdAt,updatedAt,workspace,projectType,approvalMode) VALUES('old','history',1,1,'/workspace/demo','','assisted')")
        }
        helper.runMigrationsAndValidate(path, 56, true, MIGRATION_54_55, MIGRATION_55_56).use { db ->
            db.query("SELECT title, executionTargetJson FROM harness_sessions WHERE id='old'").use {
                assertTrue(it.moveToFirst()); assertEquals("history", it.getString(0)); assertEquals("unbound", it.getString(1))
            }
        }
    }
    @Test fun `upgrade from earlier review build retains saved chat host and marks old terminal unknown`() {
        val path = java.io.File(folder.root, "upgrade-55.db").absolutePath
        helper.createDatabase(path, 55).use {
            it.execSQL("INSERT INTO harness_sessions(id,title,createdAt,updatedAt,workspace,projectType,approvalMode,executionTargetJson) VALUES('saved','chat',1,1,'','','assisted','saved-host')")
            it.execSQL("INSERT INTO terminal_sessions(id,label,workingDirectory,createdAt,sortOrder,distributionId) VALUES('old-shell','shell','/srv/projects',1,0,'ssh:a')")
        }
        helper.runMigrationsAndValidate(path, 56, true, MIGRATION_55_56).use { db ->
            db.query("SELECT executionTargetJson FROM harness_sessions WHERE id='saved'").use {
                assertTrue(it.moveToFirst()); assertEquals("saved-host", it.getString(0))
            }
            db.query("SELECT executionTargetJson FROM terminal_sessions WHERE id='old-shell'").use {
                assertTrue(it.moveToFirst()); assertTrue(it.isNull(0))
            }
        }
    }
    @Test fun `competing first bindings cannot overwrite the winning target and reopen retains it`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = java.io.File(folder.root, "durable.db").absolutePath
        java.io.File(name).parentFile!!.mkdirs()
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).openHelperFactory(FrameworkSQLiteOpenHelperFactory())
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE).build()
        val db = open()
        val repository = RoomHarnessSessionRepository(db.harnessSessionDao())
        repository.upsert(HarnessSessionEntity("one", "one", 1, 1, null, executionTargetJson = "unbound"))
        val results = (1..20).map { async(kotlinx.coroutines.Dispatchers.IO) { repository.bindExecutionTarget("one", "target-$it") } }.awaitAll()
        assertEquals(1, results.distinct().size)
        val target = results.first()
        // A workflow's stale full-row update must not clear or replace the winning binding.
        repository.upsert(HarnessSessionEntity("one", "renamed", 1, 2, null))
        repository.upsert(HarnessSessionEntity("one", "renamed", 1, 3, null, executionTargetJson = "another-host"))
        assertEquals(target, repository.findById("one")!!.executionTargetJson)
        db.terminalSessionDao().upsert(TerminalSessionEntity("terminal", "shell", "/srv/projects", 1, 0, "ssh:a", "saved-host"))
        db.close()
        val reopened = open()
        try {
            assertEquals(target, RoomHarnessSessionRepository(reopened.harnessSessionDao()).findById("one")!!.executionTargetJson)
            assertEquals("saved-host", reopened.terminalSessionDao().listAll().single().executionTargetJson)
        }
        finally { reopened.close() }
    }
}
