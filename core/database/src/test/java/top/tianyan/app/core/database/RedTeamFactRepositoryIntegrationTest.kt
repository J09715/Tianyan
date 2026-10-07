package top.tianyan.app.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RedTeamFactRepositoryIntegrationTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RedTeamFactRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomRedTeamFactRepository(database.redTeamFactDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun factsAreIsolatedBySessionAndKind() = runBlocking {
        repository.upsert(RedTeamFactEntity("session-a", "a1", "asset", "host-a", "10.0.0.1", createdAt = 1L, updatedAt = 1L))
        repository.upsert(RedTeamFactEntity("session-b", "b1", "asset", "host-b", "10.0.0.2", createdAt = 2L, updatedAt = 2L))
        repository.upsert(RedTeamFactEntity("session-a", "v1", "vulnerability", "finding-a", severity = "high", createdAt = 3L, updatedAt = 3L))

        assertEquals(listOf("a1", "v1"), repository.recent("session-a").map { it.id })
        assertEquals(listOf("b1"), repository.recent("session-b").map { it.id })
        assertEquals(listOf("a1"), repository.recent("session-a").filter { it.kind == "asset" }.map { it.id })
    }
}
