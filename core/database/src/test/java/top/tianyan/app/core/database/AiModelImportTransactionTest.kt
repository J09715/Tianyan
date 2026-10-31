package top.tianyan.app.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiModelImportTransactionTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: RoomAiModelRepository
    private val original = AiModelEntity("old", "Old", "OpenAI", "model", isActive = true, createdAt = 1)
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = RoomAiModelRepository(db.aiModelDao())
    }
    @After fun close() { db.close() }
    @Test fun `later database failure rolls back earlier imported model and activation changes`() = runBlocking {
        repository.upsert(original)
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_bad_model BEFORE INSERT ON harness_models
            WHEN NEW.id = 'bad' BEGIN SELECT RAISE(ABORT, 'rejected'); END""")
        val result = runCatching { repository.importBatch(listOf(original), listOf(
            original.copy(name = "Changed", isActive = false), original.copy(id = "bad", isActive = true),
        )) }
        assertTrue(result.isFailure)
        assertEquals(listOf(original), db.aiModelDao().listAll())
        assertEquals("old", repository.activeModel()!!.id)
    }
    @Test fun `concurrent change is detected inside the transaction before writing`() = runBlocking {
        repository.upsert(original)
        val concurrent = original.copy(name = "Concurrent")
        repository.upsert(concurrent)
        assertTrue(runCatching { repository.importBatch(listOf(original), listOf(original.copy(name = "Imported"))) }.isFailure)
        assertEquals(listOf(concurrent), db.aiModelDao().listAll())
    }
    @Test fun `successful batch preserves existing activation and commits every record`() = runBlocking {
        repository.upsert(original)
        val imported = listOf(original.copy(name = "Updated"), original.copy(id = "new", isActive = false))
        repository.importBatch(listOf(original), imported)
        assertEquals(imported.associateBy { it.id }, db.aiModelDao().listAll().associateBy { it.id })
        assertEquals("old", repository.activeModel()!!.id)
    }
}
