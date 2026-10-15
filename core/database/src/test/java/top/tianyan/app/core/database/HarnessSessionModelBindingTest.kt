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
class HarnessSessionModelBindingTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: HarnessSessionRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomHarnessSessionRepository(database.harnessSessionDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun changingModelSelectionOnlyUpdatesTargetSession() = runBlocking {
        repository.upsert(session("session-a", "profile-a", "model-a"))
        repository.upsert(session("session-b", "profile-b", "model-b"))

        repository.setModelSelection("session-a", "profile-c", "model-c", updatedAt = 30L)

        val first = requireNotNull(repository.findById("session-a"))
        val second = requireNotNull(repository.findById("session-b"))
        assertEquals("profile-c", first.modelId)
        assertEquals("model-c", first.modelVariant)
        assertEquals(30L, first.updatedAt)
        assertEquals("profile-b", second.modelId)
        assertEquals("model-b", second.modelVariant)
        assertEquals(20L, second.updatedAt)
    }

    /**
     * 会话级智能体绑定：与 modelSelection 同级，只改目标会话，
     * 且 null / 空串都要落回「不绑定」（沿用天衍本体提示词）。
     */
    @Test
    fun agentBindingOnlyUpdatesTargetSessionAndSupportsUnbinding() = runBlocking {
        repository.upsert(session("session-a", "profile-a", "model-a"))
        repository.upsert(session("session-b", "profile-b", "model-b"))

        repository.setAgentBinding("session-a", "engineering-backend-architect", updatedAt = 40L)

        val bound = requireNotNull(repository.findById("session-a"))
        assertEquals("engineering-backend-architect", bound.agentId)
        assertEquals(40L, bound.updatedAt)
        assertEquals(null, requireNotNull(repository.findById("session-b")).agentId)

        repository.setAgentBinding("session-a", null, updatedAt = 50L)
        assertEquals(null, requireNotNull(repository.findById("session-a")).agentId)
    }

    /** 会话创建时写入的绑定值必须原样读回（HarnessLoop.newSession 的落库路径）。 */
    @Test
    fun agentIdPersistsThroughUpsert() = runBlocking {
        repository.upsert(session("session-c", "profile-c", "model-c").copy(agentId = "design-ui-designer"))

        assertEquals("design-ui-designer", requireNotNull(repository.findById("session-c")).agentId)
    }

    private fun session(id: String, modelId: String, modelVariant: String) = HarnessSessionEntity(
        id = id,
        title = id,
        createdAt = 10L,
        updatedAt = 20L,
        modelId = modelId,
        modelVariant = modelVariant,
    )
}
