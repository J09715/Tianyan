package top.tianyan.app.harness.bot

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import top.tianyan.app.core.database.*
import top.tianyan.app.core.database.repository.RoomTaskBudgetRepository
import top.tianyan.app.core.model.bot.*
import top.tianyan.app.harness.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskBudgetGuardTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
    private val tasks = RoomBotAssignmentRepository(db.botAssignmentDao(), Json)
    private val budgets = RoomTaskBudgetRepository(db.botAssignmentDao())
    private val server = MockWebServer().apply { start() }
    private val json = Json { ignoreUnknownKeys = true }
    private val guard = TaskBudgetGuard(budgets)
    private val transport = ProviderTransport(OkHttpClient(), json, budgetGuard = guard)
    @After fun close() { server.shutdown(); db.close() }

    private suspend fun seed(limit: Long = 100_000) {
        db.personaBotDao().upsert(BotEntity("bot", "Bot", "role", "", "", null, null, "dm", true, "#123456", 1, 1))
        db.groupChatDao().upsertGroup(GroupChatEntity("g", "Team", "bot", "", 1, taskBudgetTokens = limit))
        tasks.save(BotAssignment("task", "g", "bot", "Bot", "Work", "Work", BotAssignmentStatus.RUNNING,
            createdAt = 1, startedAt = 1, sessionId = "managed", independent = true))
    }
    private fun model(max: Int? = 64) = ModelConfig("fake", "custom", "fake", server.url("/v1").toString().trimEnd('/'),
        "fake-key", maxTokens = max, pureChatMode = true)
    private val messages = listOf(ApiMessage("user", "hello"))
    private fun reply(usage: Boolean = true) = MockResponse().setBody(
        """{"choices":[{"message":{"role":"assistant","content":"ok"}}]${if (usage) ",\"usage\":{\"prompt_tokens\":15,\"completion_tokens\":4}" else ""}}""")

    @Test fun `budget blocks actual HTTP before sending and can resume after a user limit change`() = runBlocking {
        seed(1)
        assertTrue(runCatching { transport.chat(model(), messages, "managed") }.exceptionOrNull() is TaskBudgetExceededException)
        assertEquals(0, server.requestCount)
        val state = budgets.forSession("managed")!!
        budgets.setLimit("g", state.id, 100_000)
        server.enqueue(reply())
        assertEquals("ok", transport.chat(model(), messages, "managed").content)
        assertEquals(19L, budgets.forSession("managed")!!.usedTokens)
        assertEquals(0L, budgets.forSession("managed")!!.heldTokens)
        assertEquals("64", Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["max_tokens"]!!.jsonPrimitive.content)
    }

    @Test fun `unreported usage remains held across another guard instance and cannot be reused`() = runBlocking {
        seed()
        server.enqueue(reply(false))
        transport.chat(model(), messages, "managed")
        val state = budgets.forSession("managed")!!
        assertTrue(state.heldTokens > 0)
        assertEquals(1, state.unknownRequests)
        budgets.setLimit("g", state.id, state.heldTokens)
        val restarted = ProviderTransport(OkHttpClient(), json, budgetGuard = TaskBudgetGuard(budgets))
        assertTrue(runCatching { restarted.chat(model(), messages, "managed") }.exceptionOrNull() is TaskBudgetExceededException)
        assertEquals(1, server.requestCount)
    }

    @Test fun `streaming request and compaction attribution use the same persistent quota`() = runBlocking {
        seed()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n" +
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":15,\"completion_tokens\":4}}\n\n" + "data: [DONE]\n\n"))
        transport.chatStream(model(), messages, sessionKey = "managed", onDelta = {})
        server.enqueue(reply())
        withContext(TaskBudgetContext("managed")) { transport.chat(model(), messages) }
        assertEquals(38L, budgets.forSession("managed")!!.usedTokens)
        assertEquals(0L, budgets.forSession("managed")!!.heldTokens)
    }

    @Test fun `active budget sets a default output ceiling while disabled budget preserves provider settings`() = runBlocking {
        seed()
        server.enqueue(reply())
        transport.chat(model(null), messages, "managed")
        assertEquals("8192", Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["max_tokens"]!!.jsonPrimitive.content)
        val state = budgets.forSession("managed")!!
        budgets.setLimit("g", state.id, 0)
        guard.run("managed", model(null), OkHttpClient()) { selected, _ ->
            assertNull(selected.maxTokens)
            ChatResult("local fake", emptyList())
        }
        server.enqueue(reply())
        transport.chat(model(null), messages, "managed")
        assertEquals("8192", Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["max_tokens"]!!.jsonPrimitive.content)
    }

    @Test fun `credential retry retains failed attempt reservation instead of treating it as free`() = runBlocking {
        seed()
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"rate limit","type":"rate_limit_error"}}"""))
        server.enqueue(reply())
        transport.chat(model().copy(apiKeys = listOf("one", "two")), messages, "managed")
        val state = budgets.forSession("managed")!!
        assertEquals(2, server.requestCount)
        assertEquals(19L, state.usedTokens)
        assertTrue(state.heldTokens > 0)
        assertEquals(1, state.unknownRequests)
    }

    @Test fun `anthropic cached input is charged without treating cached tokens as free`() = runBlocking {
        seed()
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":15,"output_tokens":4,"cache_read_input_tokens":20,"cache_creation_input_tokens":10}}"""))
        transport.chat(model().copy(protocol = ApiProtocol.ANTHROPIC), messages, "managed")
        assertEquals(49L, budgets.forSession("managed")!!.usedTokens)
    }

    @Test fun `anthropic internal continuation reserves every physical request and settles merged usage once`() = runBlocking {
        seed()
        fun stream(input: Int, output: Int, stop: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":$input,\"output_tokens\":1}}}\n\n" +
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}\n\n" +
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"$stop\"},\"usage\":{\"output_tokens\":$output}}\n\n" +
                "data: {\"type\":\"message_stop\"}\n\n")
        server.enqueue(stream(10, 5, "pause_turn"))
        server.enqueue(stream(20, 7, "end_turn"))
        transport.chatStream(model().copy(protocol = ApiProtocol.ANTHROPIC), messages, sessionKey = "managed", onDelta = {})
        assertEquals(2, server.requestCount)
        assertEquals(42L, budgets.forSession("managed")!!.usedTokens)
        assertEquals(0L, budgets.forSession("managed")!!.heldTokens)
        db.query("SELECT COUNT(*) FROM bot_budget_requests WHERE id NOT LIKE 'baseline:%'", emptyArray()).use {
            assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0))
        }
    }

    @Test fun `accounting failure after a response cannot become a retryable network error`() = runBlocking {
        seed()
        val failing = object : top.tianyan.app.core.database.repository.TaskBudgetRepository by budgets {
            override suspend fun settle(requestId: String, tokens: Long, known: Boolean) {
                if (known) throw java.io.IOException("local storage unavailable")
                budgets.settle(requestId, tokens, false)
            }
        }
        val guarded = ProviderTransport(OkHttpClient(), json, budgetGuard = TaskBudgetGuard(failing))
        server.enqueue(reply())
        val failure = runCatching { guarded.chat(model(), messages, "managed") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(1, server.requestCount)
        assertTrue(budgets.forSession("managed")!!.heldTokens > 0)
    }
}
