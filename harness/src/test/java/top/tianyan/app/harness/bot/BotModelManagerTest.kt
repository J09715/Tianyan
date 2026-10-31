package top.tianyan.app.harness.bot

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.database.*
import top.tianyan.app.core.database.repository.*
import top.tianyan.app.core.model.bot.*
import top.tianyan.app.harness.plugins.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BotModelManagerTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val models = RoomAiModelRepository(db.aiModelDao())
    private val bots = RoomPersonaBotRepository(db.personaBotDao())
    private val sessions = RoomHarnessSessionRepository(db.harnessSessionDao())
    private val groups = RoomGroupChatRepository(db.groupChatDao(), Json)
    private val tasks = RoomBotAssignmentRepository(db.botAssignmentDao(), Json)
    private val manager = BotModelManager(models, bots, sessions, RoomBotGroupSessionRepository(db.botGroupSessionDao()), tasks)
    @After fun close() = db.close()

    private suspend fun seed(allowModelSelection: Boolean = true) {
        models.upsert(AiModelEntity("one", "First", "Provider A", "a,b", secretRef = "private-key-ref", isActive = true, createdAt = 1))
        models.upsert(AiModelEntity("two", "Second", "Provider B", "x,y", createdAt = 2))
        val bot = BotEntity("member", "Member", "coder", "persona", "seed", "one", null, "dm", false, "#123456", 1, 1,
            allowModelSelection = allowModelSelection)
        db.personaBotDao().upsert(bot)
        db.personaBotDao().upsert(bot.copy(id = "other", name = "Other", sessionId = "other-dm"))
        db.groupChatDao().upsertGroup(GroupChatEntity("group", "Team", "other", "", 1))
        groups.addMember("group", "member", 0)
        groups.addMember("group", "other", 1)
        sessions.upsert(HarnessSessionEntity("dm", "Direct", 1, 1, "one"))
        sessions.upsert(HarnessSessionEntity("group-session", "Group", 1, 1, "one"))
        db.botGroupSessionDao().upsert(BotGroupSessionEntity("member", "group", "group-session", 1))
        tasks.createIndependent(BotAssignment("task", "group", "member", "Member", "Work", "Work",
            BotAssignmentStatus.QUEUED, emptyList(), 1, 1, sessionId = "task-session", independent = true, reportToBotId = "other"),
            HarnessSessionEntity("task-session", "Task", 1, 1, "one", workspace = "/workspace/project", executionTargetJson = "frozen"),
            BotAssignmentRequest("initial:task", "task", "Work", 1))
    }

    @Test fun `member switches any saved provider and variant across owned sessions without changing other bots or global activation`() = runBlocking {
        seed()
        manager.select("member", "two", "Y")
        assertEquals("two", bots.getBot("member")!!.modelId)
        assertEquals("y", bots.getBot("member")!!.modelVariant)
        listOf("dm", "group-session", "task-session").forEach { id ->
            assertEquals("two", sessions.findById(id)!!.modelId)
            assertEquals("y", sessions.findById(id)!!.modelVariant)
        }
        assertEquals("frozen", sessions.findById("task-session")!!.executionTargetJson)
        assertEquals("/workspace/project", sessions.findById("task-session")!!.workspace)
        assertEquals("one", bots.getBot("other")!!.modelId)
        assertEquals("one", models.activeModel()!!.id)
        manager.select("member", null, null)
        assertNull(bots.getBot("member")!!.modelId)
        assertNull(sessions.findById("task-session")!!.modelVariant)
    }

    @Test fun `invalid or deleted model selections reject without altering persisted bindings`() = runBlocking {
        seed()
        assertTrue(runCatching { manager.select("member", "missing", "y") }.isFailure)
        assertTrue(runCatching { manager.select("member", "two", "not-added") }.isFailure)
        assertTrue(runCatching { manager.select("member", null, "y") }.isFailure)
        assertEquals("one", bots.getBot("member")!!.modelId)
        assertEquals("one", sessions.findById("dm")!!.modelId)
    }

    @Test fun `locked members can inspect models but cannot change any owned session until user opts in`() = runBlocking {
        seed(allowModelSelection = false)
        val tools = botModelTools(bots, manager)
        val select = tools.single { it.id == "select-model" }
        val arguments = JsonObject(mapOf("profile_id" to JsonPrimitive("two"), "model" to JsonPrimitive("y")))
        for (session in listOf("dm", "group-session", "task-session")) {
            val result = select.execute(PluginToolRequest(arguments, session, "/workspace", null))
            assertFalse(result.success)
            assertTrue(result.text.contains("模型已锁定"))
            assertFalse(select.execute(PluginToolRequest(JsonObject(emptyMap()), session, "/workspace", null)).success)
            assertEquals("one", sessions.findById(session)!!.modelId)
        }
        assertTrue(runCatching { manager.select("member", "two", "y") }.isFailure)
        assertEquals("one", bots.getBot("member")!!.modelId)
        val catalog = tools.single { it.id == "list-models" }
            .execute(PluginToolRequest(JsonObject(emptyMap()), "group-session", "/workspace", null))
        assertTrue(catalog.success)
        assertTrue(catalog.text.contains("模型已锁定"))
        bots.saveBot(bots.getBot("member")!!.copy(allowModelSelection = true))
        assertTrue(select.execute(PluginToolRequest(arguments, "task-session", "/workspace", null)).success)
        bots.saveBot(bots.getBot("member")!!.copy(allowModelSelection = false))
        assertFalse(select.execute(PluginToolRequest(JsonObject(emptyMap()), "dm", "/workspace", null)).success)
        listOf("dm", "group-session", "task-session").forEach { assertEquals("two", sessions.findById(it)!!.modelId) }
        assertEquals("one", bots.getBot("other")!!.modelId)
        assertEquals("one", models.activeModel()!!.id)
    }

    @Test fun `self service identifies members in task sessions and exposes no credential metadata`() = runBlocking {
        seed()
        val tools = botModelTools(bots, manager)
        suspend fun call(tool: String, session: String, args: Map<String, String> = emptyMap()) = tools.single { it.id == tool }
            .execute(PluginToolRequest(JsonObject(args.mapValues { JsonPrimitive(it.value) }), session, "/workspace", null))
        val catalog = call("list-models", "task-session")
        assertTrue(catalog.success)
        assertTrue(catalog.text.contains("Provider B"))
        assertTrue(catalog.text.contains("x, y"))
        assertFalse(catalog.text.contains("private-key-ref"))
        assertFalse(call("select-model", "unknown", mapOf("profile_id" to "two", "model" to "x")).success)
        assertTrue(call("select-model", "task-session", mapOf("profile_id" to "two", "model" to "x")).success)
        assertEquals("x", sessions.findById("task-session")!!.modelVariant)
        assertEquals("one", bots.getBot("other")!!.modelId)
    }

    @Test fun `members can page through every model of a large supplier without changing bindings`() = runBlocking {
        seed()
        models.upsert(AiModelEntity("large", "Large supplier", "Provider C", (1..75).joinToString(",") { "model-$it" }, createdAt = 3))
        val tool = botModelTools(bots, manager).single { it.id == "list-models" }
        val page = tool.execute(PluginToolRequest(JsonObject(mapOf("profile_id" to JsonPrimitive("large"),
            "offset" to JsonPrimitive(50), "limit" to JsonPrimitive(50))), "group-session", "/workspace", null))
        assertTrue(page.success)
        assertTrue(page.text.contains("model=model-51"))
        assertTrue(page.text.contains("model=model-75"))
        assertFalse(page.text.contains("next_offset="))
        assertEquals("one", bots.getBot("member")!!.modelId)
    }
}
