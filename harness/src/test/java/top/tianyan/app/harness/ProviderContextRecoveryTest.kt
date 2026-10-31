package top.tianyan.app.harness

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.common.logging.SensitiveDataRedactor
import top.tianyan.app.core.database.AppDatabase
import top.tianyan.app.core.database.RoomHarnessRuntimeRepository
import top.tianyan.app.core.datastore.AgentPreferences
import top.tianyan.app.core.datastore.SettingsDataStore
import top.tianyan.app.core.security.SecretManager
import top.tianyan.app.harness.compaction.CompactionManager
import top.tianyan.app.harness.events.AgentEventLogger
import top.tianyan.app.harness.session.SessionTreeStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderContextRecoveryTest {
    private fun model(window: Int = 128_000) = ModelConfig(
        "test", "custom", "test", "https://example.invalid", null, contextTokens = window,
    )

    @Test fun `near full history folds before old threshold even with saved 100 percent`() {
        val messages = buildList<HarnessMessage> {
            repeat(8) {
                add(UserMessage("u$it", it * 2L, "x".repeat(17_000)))
                add(AssistantText("a$it", it * 2L + 1, "y".repeat(17_000)))
            }
        }
        val estimated = messages.sumOf {
            when (it) {
                is UserMessage -> ContextWindowPolicy.estimateTokens(it.text)
                is AssistantText -> ContextWindowPolicy.estimateTokens(it.text)
                else -> 0
            }
        }
        assertTrue(estimated < 128_000 - ContextWindowPolicy.RESERVED_OUTPUT_TOKENS)
        assertTrue(estimated > ContextWindowPolicy.foldingLimitFor(128_000, 100, toolSchemaReserveTokens = 0))
        assertTrue(ContextWindowPolicy.computeKeepFromIndex(messages, 128_000, 0,
            foldingRatioPercent = 100, toolSchemaReserveTokens = 0) > 0)
        assertEquals(ContextWindowPolicy.foldingLimitFor(128_000, 90), ContextWindowPolicy.foldingLimitFor(128_000, 100))
        assertTrue(ContextWindowPolicy.foldingLimitFor(128_000, 70) < ContextWindowPolicy.foldingLimitFor(128_000, 90))
    }

    @Test fun `empty response recovery uses pressure rather than blindly compacting every error`() {
        assertFalse(shouldCompactEmptyResponse(1_000, model()))
        assertTrue(shouldCompactEmptyResponse(110_000, model()))
        assertTrue(shouldCompactEmptyResponse(24_000, model(32_000)))
        assertFalse(shouldCompactEmptyResponse(10_000, model(32_000)))
    }

    @Test fun `output recovery grows a temporary budget and reservation with a hard ceiling`() {
        val original = model().copy(maxTokens = 2_500, compactionReserveTokens = 1_000)
        val recovered = outputRecoveryModel(original)!!
        assertEquals(5_000, recovered.maxTokens)
        assertEquals(5_000, recovered.compactionReserveTokens)
        assertEquals(2_500, original.maxTokens)
        assertEquals(16_384, outputRecoveryModel(model())!!.maxTokens)
        assertEquals(32_768, outputRecoveryModel(model().copy(maxTokens = 20_000))!!.maxTokens)
        assertNull(outputRecoveryModel(model().copy(maxTokens = Int.MAX_VALUE)))
        assertNull(outputRecoveryModel(model().copy(maxTokens = 32_768)))
    }

    @Test fun `emergency recovery makes a smaller replay and preserves original history without a provider`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repository = RoomHarnessRuntimeRepository(database.harnessRuntimeDao())
            val log = AppLogger(context, SensitiveDataRedactor { it })
            val preferences = AgentPreferences(SettingsDataStore(context, SecretManager()))
            val logger = AgentEventLogger(preferences, log)
            val store = SessionTreeStore(repository, Json, log)
            val compaction = CompactionManager(repository, Json, store)
            repeat(30) { index ->
                store.append("s", if (index % 2 == 0)
                    UserMessage("m$index", index.toLong(), "x".repeat(8_000))
                else AssistantText("m$index", index.toLong(), "y".repeat(8_000)))
            }
            val before = compaction.project("s")
            val smallMessages = listOf(ApiMessage("user", "hello"))
            val enlarged = recoverFromOutputLimit("s", model(), smallMessages, 0, compaction, logger)
            assertEquals(16_384, enlarged!!.maxTokens)
            assertEquals("output-only exhaustion must not fold history", before, compaction.project("s"))
            val pressureModel = model(32_000)
            val largeMessages = listOf(ApiMessage("user", "x".repeat(55_000)))
            val recovered = recoverFromOutputLimit("s", pressureModel, largeMessages, 3_600, compaction, logger)
            assertNotNull(recovered)
            val after = compaction.project("s")
            assertTrue(after.messages.size < before.messages.size)
            assertFalse(after.summary.isNullOrBlank())
            assertEquals("m29", after.messages.last().id)
            assertEquals(30, repository.listEntries("s").count { it.entryType == "message" })
            val repeated = recoverFromContextOverflow("s", model(), compaction, logger)
            assertNull("already minimal history must not cause endless replay", repeated)
        } finally {
            database.close()
        }
    }
}
