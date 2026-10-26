package top.tianyan.app.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.model.BackupRecords

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyDatabaseBackupTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: RoomDatabaseBackupRepository

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = RoomDatabaseBackupRepository(db)
    }

    @After fun close() = db.close()

    @Test fun `pre SSH backup restores history without authorizing local execution`() = runBlocking {
        val legacy = legacyBackup()
        assertEquals("备份字段与当前版本不兼容", runCatching { repository.validate(legacy) }.exceptionOrNull()?.message)
        val upgraded = BackupRecordPolicy.upgradeLegacy(legacy)
        repository.validate(upgraded)
        assertEquals(1, repository.merge(repository.snapshot(), BackupRecordPolicy.portable(upgraded), "legacy") {})
        val restored = db.harnessSessionDao().findById("legacy-session")!!
        assertEquals("旧会话", restored.title)
        assertEquals("restore-required", restored.executionTargetJson)
        assertTrue(repository.committed("legacy"))
    }

    @Test fun `legacy upgrade still rejects missing required fields`() = runBlocking {
        val legacy = legacyBackup()
        val invalid = legacy.copy(tables = legacy.tables + ("harness_sessions" to
            legacy.tables.getValue("harness_sessions").map { JsonObject(it - "title") }))
        assertEquals("备份字段与当前版本不兼容",
            runCatching { repository.validate(BackupRecordPolicy.upgradeLegacy(invalid)) }.exceptionOrNull()?.message)
    }

    @Test fun `current archives are unchanged by legacy upgrade`() = runBlocking {
        val current = repository.snapshot()
        assertEquals(current, BackupRecordPolicy.upgradeLegacy(current))
    }

    private suspend fun legacyBackup(): BackupRecords {
        db.harnessSessionDao().insert(HarnessSessionEntity("legacy-session", "旧会话", 1L, 2L, null))
        val snapshot = repository.snapshot()
        db.openHelper.writableDatabase.execSQL("DELETE FROM harness_sessions")
        return snapshot.copy(tables = snapshot.tables + ("harness_sessions" to
            snapshot.tables.getValue("harness_sessions").map { JsonObject(it - "executionTargetJson") }))
    }
}
