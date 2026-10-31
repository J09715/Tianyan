package top.tianyan.app.harness.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.common.logging.SensitiveDataRedactor
import top.tianyan.app.core.datastore.AgentPreferences
import top.tianyan.app.core.datastore.SettingsDataStore
import top.tianyan.app.core.model.RunMode
import top.tianyan.app.core.database.AgentApprovalRepository
import top.tianyan.app.core.database.AgentSkillRepository
import top.tianyan.app.core.database.AgentSubagentRepository
import top.tianyan.app.core.database.AgencyAgentCatalogLoader
import top.tianyan.app.core.database.AppDatabase
import top.tianyan.app.core.database.McpServerRepository
import top.tianyan.app.core.database.RoomAgentContextRepository
import top.tianyan.app.core.database.RoomHarnessRuntimeRepository
import top.tianyan.app.core.security.SecretManager
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.CapabilityEvent
import top.tianyan.app.harness.HarnessTool
import top.tianyan.app.harness.ModelConfig
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolCallMode
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.harness.UserMessage
import top.tianyan.app.harness.WorkspaceFileAccess
import top.tianyan.app.core.tools.ToolRegistry
import top.tianyan.app.core.tools.ToolRepository
import top.tianyan.app.harness.compaction.CompactionManager
import top.tianyan.app.harness.prompt.MemoryRecallSelector
import top.tianyan.app.harness.prompt.PrivilegeSectionRenderer
import top.tianyan.app.harness.prompt.PromptAssetLoader
import top.tianyan.app.harness.prompt.PromptRouter
import top.tianyan.app.harness.prompt.SystemPromptBuilder
import kotlinx.serialization.json.JsonPrimitive
import top.tianyan.app.core.database.AgentMemoryEntity
import top.tianyan.app.core.database.AgentPlanEntity
import top.tianyan.app.core.database.AgentSkillEntity
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.session.SessionTreeStore
import top.tianyan.app.harness.operation.OperationCoordinator
import top.tianyan.app.harness.operation.ReplayPolicy
import top.tianyan.app.harness.events.HarnessEventBus

/**
 * API 上下文组装器全栈集成测试：真实 Room（会话树 + 压缩树）+ 真实 DataStore 偏好 +
 * 打包内真实提示词资产。验证 NATIVE / JSON_TEXT 双协议、视觉剥离、思考回传标记、
 * 能力事件剔除、未应答调用的丢弃与压缩摘要注入。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiContextAssemblerTest {

    private lateinit var database: AppDatabase
    private lateinit var store: SessionTreeStore
    private lateinit var assembler: ApiContextAssembler
    private lateinit var operations: OperationCoordinator
    private lateinit var compactionManager: CompactionManager
    private lateinit var agentContextRepository: RoomAgentContextRepository
    private val tempDir = File(System.getProperty("java.io.tmpdir"), "tianyan-assembler-${System.nanoTime()}")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val runtimeRepo = RoomHarnessRuntimeRepository(database.harnessRuntimeDao())
        val logger = AppLogger(context, SensitiveDataRedactor { it })
        val json = Json { ignoreUnknownKeys = true }
        operations = OperationCoordinator(runtimeRepo, json, HarnessEventBus())
        val agentPrefs = AgentPreferences(SettingsDataStore(context, SecretManager()))

        store = SessionTreeStore(runtimeRepo, json, logger)
        compactionManager = CompactionManager(runtimeRepo, json, store)
        agentContextRepository = RoomAgentContextRepository(database.agentContextDao())

        val promptAssets = PromptAssetLoader(context)
        val builder = SystemPromptBuilder(
            context = context,
            settingsDataStore = agentPrefs,
            skillRepository = AgentSkillRepository(database.agentSkillDao()),
            toolRepository = ToolRepository(database.toolDao(), ToolRegistry(context, OkHttpClient(), logger)),
            agentContextDao = agentContextRepository,
            subagentRepository = AgentSubagentRepository(
                database.agentSubagentDao(),
                AgencyAgentCatalogLoader(context, json),
            ),
            mcpServerRepository = McpServerRepository(database.mcpServerDao(), SecretManager()),
            promptAssets = promptAssets,
            fileAccess = WorkspaceFileAccess(tempDir),
            privilegeRenderer = PrivilegeSectionRenderer { "" },
            promptRouter = PromptRouter(promptAssets),
        )
        assembler = ApiContextAssembler(
            compactionManager = compactionManager,
            settingsDataStore = agentPrefs,
            systemPromptBuilder = builder,
            sessionStore = store,
            memoryRecallSelector = MemoryRecallSelector(agentContextRepository),
            agentApprovalRepository = AgentApprovalRepository(database.agentApprovalDao()),
            operationCoordinator = operations,
        )
    }

    @After
    fun tearDown() {
        database.close()
        tempDir.deleteRecursively()
    }

    private fun nativeModel(vision: Boolean = false, tokens: Int = 200_000) = ModelConfig(
        name = "n", provider = "p", model = "gpt-x",
        baseUrl = "https://example.com", apiKey = null,
        contextTokens = tokens,
        visionEnabled = vision,
    )

    private suspend fun push(sessionId: String, vararg messages: HarnessMessage) {
        messages.forEach { store.append(sessionId, it) }
    }

    private fun jsonTextModel(vision: Boolean = false) =
        nativeModel(vision).copy(toolCallMode = ToolCallMode.JSON_TEXT)

    @Test fun `failure memory survives compaction and stays outside the system prefix in both protocols`() = runBlocking {
        val session = "failure-memory"
        val operation = operations.acceptRun(session, UserMessage("memory-input", 1, "goal"))
        val call = ToolCall("memory-call", 2, HarnessTool.BASE, buildJsonObject { }, rawToolName = "base")
        operations.toolIntent(operation, call, "{}", ReplayPolicy.NEVER, 1)
        operations.toolSettled(operation, ToolResult("memory-result", 3, call.id, false, "failed"), 1)
        operations.providerSettled(operation, AssistantText("memory-answer", 4, "continue"), round = 2)
        val before = compactionManager.project(session)
        compactionManager.compact(session, before, 3)
        assertTrue(compactionManager.project(session).messages.none { it is ToolResult })
        for (mode in listOf(ToolCallMode.NATIVE, ToolCallMode.JSON_TEXT)) {
            val request = assembler.assemble(session, nativeModel().copy(toolCallMode = mode), "/workspace/p")
            assertEquals("user", request.last().role)
            assertTrue(request.last().content!!.contains("本次运行的失败记忆"))
            assertTrue(request.last().content!!.contains("memory-call / memory-result"))
            assertFalse(request.filter { it.role == "system" }.any { it.content.orEmpty().contains("本次运行的失败记忆") })
        }
    }

    @Test fun `pure chat and disabled tools do not receive operation failure background`() = runBlocking {
        val session = "memory-disabled"
        val operation = operations.acceptRun(session, UserMessage("disabled-input", 1, "goal"))
        val call = ToolCall("disabled-call", 2, HarnessTool.BASE, buildJsonObject { })
        operations.toolIntent(operation, call, "{}", ReplayPolicy.NEVER, 1)
        operations.toolSettled(operation, ToolResult("disabled-result", 3, call.id, false, "failed"), 1)
        for (model in listOf(nativeModel().copy(pureChatMode = true), nativeModel().copy(toolCallMode = ToolCallMode.DISABLED))) {
            assertFalse(assembler.assemble(session, model, "/workspace/p").any { it.content.orEmpty().contains("本次运行的失败记忆") })
        }
    }

    // ---------- NATIVE 协议 ----------

    @Test
    fun `native injects system prompt and maps user and plain assistant`() = runBlocking {
        push(
            "s-native",
            UserMessage("u1", 1L, "看看文件"),
            AssistantText("a0", 2L, "好的"),
        )
        val out = assembler.assemble("s-native", nativeModel(), workspacePath = "")

        assertEquals("system", out[0].role)
        val systemPrompt = out[0].content!!
        assertTrue(top.tianyan.app.core.model.AgentDepartments.agency.all { systemPrompt.contains(it.localizedName) })
        assertEquals(top.tianyan.app.core.model.AgentDepartments.agency.size, Regex("department=\\\"").findAll(systemPrompt).count())
        assertFalse(systemPrompt.contains("agency_engineering_frontend_developer"))
        assertFalse(systemPrompt.contains("Frontend Developer"))
        assertEquals(2, out.size - 1)
        assertEquals("user", out[1].role)
        assertEquals("assistant", out[2].role)
        assertNull(out[2].tool_calls)
    }

    @Test
    fun `native pairs answered tool calls onto assistant and emits tool role`() = runBlocking {
        val call = ToolCall(
            id = "t1", createdAt = 3L, tool = HarnessTool.READ,
            args = buildJsonObject { }, rawToolName = "read",
        )
        push(
            "s-pair",
            UserMessage("u1", 1L, "读一下"),
            AssistantText("a0", 2L, "我来读"),
            call,
            ToolResult("r1", 4L, "t1", success = true, output = "内容"),
            AssistantText("a1", 5L, "结论"),
        )
        val out = assembler.assemble("s-pair", nativeModel(), "")

        val paired = out.first { it.role == "assistant" && !it.tool_calls.isNullOrEmpty() }
        assertEquals(listOf("t1"), paired.tool_calls!!.map { it.id })
        val toolMsg = out.last { it.role == "tool" }
        assertEquals("t1", toolMsg.tool_call_id)
        assertEquals("内容", toolMsg.content)
        assertEquals("结论", out.last { it.role == "assistant" }.content)
    }

    @Test
    fun `unanswered native tool calls are dropped silently`() = runBlocking {
        push(
            "s-orphan",
            UserMessage("u1", 1L, "x"),
            ToolCall("t9", 3L, HarnessTool.READ, buildJsonObject { }),
        )
        val out = assembler.assemble("s-orphan", nativeModel(vision = false), "")
        assertTrue(out.none { it.role == "tool" })
        assertTrue(out.none { !it.tool_calls.isNullOrEmpty() })
    }

    @Test
    fun `native historical assistant messages retain reasoning regardless of thinking hint`() = runBlocking {
        push("s-think", UserMessage("u1", 1L, "hi"), AssistantText("a1", 2L, "<think>思考中</think>hello", reasoning = "历史思考"))
        val out = assembler.assemble("s-think", nativeModel(), "", thinkingMode = true)
        val assistant = out.last { it.role == "assistant" }
        assertEquals("历史思考", assistant.reasoning_content)
        assertEquals("hello", assistant.content)

        val off = assembler.assemble("s-think", nativeModel(), "", thinkingMode = false)
        assertEquals("历史思考", off.last { it.role == "assistant" }.reasoning_content)
        assertEquals("hello", off.last { it.role == "assistant" }.content)
    }

    @Test
    fun `tool-calling assistant messages must round-trip reasoning content for thinking mode`() = runBlocking {
        // DeepSeek 思考模式：两个 user 之间若有工具调用，中间 assistant 消息的
        // reasoning_content 必须原样传回，否则 400 报错。
        push(
            "s-think-tool",
            UserMessage("u1", 1L, "读文件"),
            ToolCall("t1", 2L, HarnessTool.READ, buildJsonObject { }, reasoning = "需要先看文件", rawToolName = "read"),
            ToolResult("r1", 3L, "t1", success = true, output = "内容"),
            AssistantText("a1", 4L, "文件内容是……", reasoning = "已读到"),
        )
        val out = assembler.assemble("s-think-tool", nativeModel(), "")
        val toolTurn = out.first { it.role == "assistant" && !it.tool_calls.isNullOrEmpty() }
        assertEquals("需要先看文件", toolTurn.reasoning_content)
        // 收尾纯文本 assistant 轮也必须回传
        assertEquals("已读到", out.last { it.role == "assistant" && it.tool_calls == null }.reasoning_content)
    }

    // ---------- JSON_TEXT 协议 ----------

    @Test
    fun `json text converts tool results into user text messages`() = runBlocking {
        val call = ToolCall(id = "j1", createdAt = 3L, tool = HarnessTool.BASE, args = buildJsonObject { }, rawToolName = "base")
        push(
            "s-json",
            UserMessage("u1", 1L, "跑命令"),
            AssistantText("a0", 2L, "模型叙述文本"),
            call,
            ToolResult("r1", 4L, "j1", success = true, output = "输出内容"),
        )
        val out = assembler.assemble("s-json", jsonTextModel(), "")
        assertTrue(out.none { it.role == "tool" })
        assertTrue(out.none { !it.tool_calls.isNullOrEmpty() })

        val asUser = out.filter { it.role == "user" }.last()
        assertTrue(asUser.content!!.contains("【工具 base 执行结果·成功】"))
        assertTrue(asUser.content!!.contains("输出内容"))

        val narration = out.first { it.role == "assistant" }
        assertEquals("模型叙述文本", narration.content)
    }

    // ---------- 视觉与能力事件 ----------

    @Test
    fun `images are stripped when vision disabled and kept when enabled`() = runBlocking {
        val images = listOf("https://img.example/a.png")
        push("s-vision", UserMessage("u1", 1L, "看图", imageUrls = images))
        val stripped = assembler.assemble("s-vision", nativeModel(vision = false), "")
        assertTrue(stripped.first { it.role == "user" }.imageUrls.isEmpty())

        val kept = assembler.assemble("s-vision", nativeModel(vision = true), "")
        assertEquals(images, kept.first { it.role == "user" }.imageUrls)
    }

    @Test
    fun `capability events never reach the provider payload`() = runBlocking {
        push(
            "s-cap",
            UserMessage("u1", 1L, "@skill"),
            CapabilityEvent("cap1", 2L, CapabilityEvent.Kind.SKILL, "技能", "详情"),
        )
        val out = assembler.assemble("s-cap", nativeModel(), "")
        assertFalse(out.any { it.content?.contains("详情") == true })
    }

    // ---------- 压缩摘要 ----------

    @Test
    fun `existing compaction summary is injected after the main system prompt`() = runBlocking {
        push("s-compact", UserMessage("u0", 1L, "早期历史，会被折叠进摘要"))
        val context = compactionManager.project("s-compact")
        compactionManager.compact("s-compact", context, keepFromIndex = 1)

        val expectedSummary = compactionManager.project("s-compact").summary!!
        val out = assembler.assemble("s-compact", nativeModel(), "")

        assertTrue(out.size >= 2)
        assertEquals(expectedSummary, out[1].content)
        assertFalse(out.any { it.role != "system" && it.content == expectedSummary })
    }

    @Test
    fun `persisted summary does not hide retained turns that still fit the model window`() = runBlocking {
        val sessionId = "s-retained"
        repeat(6) { index ->
            push(sessionId, UserMessage("u$index", index.toLong(), "retained request $index"))
        }
        val context = compactionManager.project(sessionId)
        compactionManager.compact(sessionId, context, keepFromIndex = 1)

        val out = assembler.assemble(sessionId, nativeModel(tokens = 200_000), "")
        val providerUsers = out.filter { it.role == "user" }.map { it.content }

        assertEquals((1..5).map { "retained request $it" }, providerUsers)
    }

    @Test
    fun `pure chat skips even persisted history system prompts`() = runBlocking {
        push("s-pure2", UserMessage("u1", 1L, "你好"))
        val out = assembler.assemble("s-pure2", nativeModel().copy(pureChatMode = true), "/ws")
        assertTrue(out.none { it.role == "system" })
        assertEquals("你好", out.single().content)
    }

    @Test
    fun `mcp rawToolName is preserved in native assistant tool calls`() = runBlocking {
        val mcpCall = ToolCall(
            id = "call-mcp",
            createdAt = 2L,
            tool = HarnessTool.MCP,
            args = buildJsonObject { put("query", JsonPrimitive("Kotlin coroutines")) },
            rawToolName = "mcp__mcp_websearch__search",
        )
        val mcpResult = ToolResult(
            id = "res-mcp",
            createdAt = 3L,
            toolCallId = "call-mcp",
            success = true,
            output = "搜索结果: Kotlin Coroutines 指南",
        )
        push("s-mcp", UserMessage("u1", 1L, "搜索一下"), mcpCall, mcpResult)
        val out = assembler.assemble("s-mcp", nativeModel(), "")

        val assistantMsg = out.first { it.role == "assistant" }
        val apiCall = assistantMsg.tool_calls?.single()
        assertEquals("call-mcp", apiCall?.id)
        assertEquals("mcp__mcp_websearch__search", apiCall?.function?.name)
    }

    // ---------- 用户轮记忆召回后缀（prefix cache 稳定性） ----------

    @Test fun `background allocation omits oversized recall without changing durable history`() = runBlocking {
        val session = "budget-recall"
        val text = "current task"
        val recall = "<recalled_memory>" + "background evidence ".repeat(20_000) + "</recalled_memory>"
        push(session, UserMessage("budget-user", 1, text))
        assertTrue(store.appendRecallBlock(session, "budget-user", recall))
        for (mode in listOf(ToolCallMode.NATIVE, ToolCallMode.JSON_TEXT)) {
            val trace = top.tianyan.app.harness.diagnostics.ContextGovernanceTrace()
            val messages = assembler.assemble(session, nativeModel(tokens = 32_000).copy(toolCallMode = mode),
                "/ws", trace = trace)
            assertEquals(text, messages.last { it.role == "user" }.content)
            val allocation = trace.report().allocations.single { it.kind == "recall" }
            assertTrue(allocation.beforeTokens > 0)
            assertEquals(0, allocation.allocatedTokens)
            assertEquals("BACKGROUND_BUDGET", allocation.reason)
            assertEquals(recall, compactionManager.project(session).recallBlocks["budget-user"])
            assertEquals(text, compactionManager.project(session).messages.filterIsInstance<UserMessage>().single().text)
        }
    }

    private suspend fun saveProjectMemory(sessionId: String, keyword: String) {
        agentContextRepository.saveMemory(
            AgentMemoryEntity(
                id = "mem-$sessionId-$keyword",
                scope = "project",
                ownerId = "/ws",
                kind = "fact",
                key = "topic.$keyword",
                value = "关于 $keyword 的事实内容：使用 suspend 函数与 Flow 组合。",
            ),
        )
    }

    @Test
    fun `recall suffix is persisted once and attached to user turn never system prompt`() = runBlocking {
        saveProjectMemory("s-recall", "coroutines")
        push("s-recall", UserMessage("u1", 1L, "讲讲 coroutines 的用法"), AssistantText("a1", 2L, "好的"))

        val first = assembler.assemble("s-recall", nativeModel(), "/ws")
        val systemPrompt = first.first { it.role == "system" }.content.orEmpty()
        // 召回后缀随 user 轮注入，且不进入 system prompt
        val userMsg = first.last { it.role == "user" }
        assertTrue(userMsg.content!!.contains("<recalled_memory>"))
        assertTrue(userMsg.content!!.contains("coroutines"))
        assertFalse(systemPrompt.contains("<recalled_memory>"))
        // 持久化：project() 能读出该轮的召回块
        assertTrue(compactionManager.project("s-recall").recallBlocks.containsKey("u1"))

        // 同一轮的第二次组装（多轮工具循环的重入）字节级一致——前缀缓存的前提
        val second = assembler.assemble("s-recall", nativeModel(), "/ws")
        assertEquals(first, second)
    }

    @Test
    fun `historical recall suffix stays frozen when new memories arrive later`() = runBlocking {
        saveProjectMemory("s-frozen", "coroutines")
        push("s-frozen", UserMessage("u1", 1L, "讲讲 coroutines"), AssistantText("a1", 2L, "好的"))
        val first = assembler.assemble("s-frozen", nativeModel(), "/ws")

        // 之后新增另一条记忆，并推进到下一轮
        saveProjectMemory("s-frozen", "room 迁移")
        push("s-frozen", UserMessage("u2", 3L, "讲讲 room 迁移"), AssistantText("a2", 4L, "好的"))
        val second = assembler.assemble("s-frozen", nativeModel(), "/ws")

        val users = second.filter { it.role == "user" }
        assertEquals(2, users.size)
        // 历史轮的后缀与首次组装时完全一致（从会话树读取，不重算）
        val firstUserOld = first.last { it.role == "user" }.content
        assertEquals(firstUserOld, users[0].content)
        // 新一轮命中新记忆
        assertTrue(users[1].content!!.contains("room 迁移"))
    }

    // ---------- 任务计划看板（prefix cache 稳定性） ----------

    private suspend fun saveActivePlan(sessionId: String, goal: String, stepsJson: String) {
        agentContextRepository.savePlan(
            AgentPlanEntity(
                sessionId = sessionId,
                goal = goal,
                stepsJson = stepsJson,
                status = "active",
            ),
        )
    }

    @Test
    fun `plan board rides on user turn prefix block instead of system prompt`() = runBlocking {
        saveActivePlan("s-plan", "重构认证模块", """["步骤1: 只读排查","步骤2: 实现"]""")
        push("s-plan", UserMessage("u1", 1L, "继续推进计划"), AssistantText("a1", 2L, "好的"))

        val out = assembler.assemble("s-plan", nativeModel(), "/ws")
        val systemPrompt = out.first { it.role == "system" }.content.orEmpty()
        val userMsg = out.last { it.role == "user" }.content.orEmpty()
        // 计划看板挂在用户轮上；system prompt 不再含计划文本（否则每步推进都废掉整段前缀）
        assertTrue(userMsg.contains("<task_plan>"))
        assertTrue(userMsg.contains("重构认证模块"))
        assertFalse(systemPrompt.contains("<task_plan>"))
        assertFalse(systemPrompt.contains("重构认证模块"))
    }

    @Test
    fun `plan alone can produce a prefix block even when no memory matches`() = runBlocking {
        saveActivePlan("s-plan-only", "清理死代码", """["步骤1: 扫描"]""")
        // "继续" 是泛化轮次，不触发任何记忆召回
        push("s-plan-only", UserMessage("u1", 1L, "继续"), AssistantText("a1", 2L, "好的"))

        val out = assembler.assemble("s-plan-only", nativeModel(), "/ws")
        assertTrue(out.last { it.role == "user" }.content.orEmpty().contains("<task_plan>"))
        assertTrue(compactionManager.project("s-plan-only").recallBlocks.containsKey("u1"))
    }

    @Test
    fun `plan advance inside one turn does not rewrite the frozen prefix block`() = runBlocking {
        saveActivePlan("s-plan-frozen", "重构认证模块", """["步骤1: 只读排查"]""")
        push("s-plan-frozen", UserMessage("u1", 1L, "继续推进计划"), AssistantText("a1", 2L, "好的"))
        val first = assembler.assemble("s-plan-frozen", nativeModel(), "/ws")

        // 同一用户轮内推进计划（plan 工具的效果）：已发出的字节不得改写
        saveActivePlan("s-plan-frozen", "重构认证模块", """["步骤1: 已排查","步骤2: 实现"]""")
        val second = assembler.assemble("s-plan-frozen", nativeModel(), "/ws")
        assertEquals(first, second)
    }

    @Test
    fun `next user turn picks up the advanced plan`() = runBlocking {
        saveActivePlan("s-plan-next", "目标A", """["a"]""")
        push("s-plan-next", UserMessage("u1", 1L, "开始"), AssistantText("a1", 2L, "好的"))
        assembler.assemble("s-plan-next", nativeModel(), "/ws")

        saveActivePlan("s-plan-next", "目标A", """["a","b"]""")
        push("s-plan-next", UserMessage("u2", 3L, "继续"), AssistantText("a2", 4L, "好的"))
        val out = assembler.assemble("s-plan-next", nativeModel(), "/ws")

        val users = out.filter { it.role == "user" }
        assertEquals(2, users.size)
        assertTrue(users[1].content.orEmpty().contains(""""b""""))
    }

    // ---------- 运行意图（PLAN 只读规划） ----------

    @Test
    fun `plan run mode injects the read-only contract into the system prompt`() = runBlocking {
        push("s-runmode", UserMessage("u1", 1L, "看看这个项目要怎么改"), AssistantText("a1", 2L, "好的"))

        val build = assembler.assemble("s-runmode", nativeModel(), "", sessionRunMode = "build")
        assertFalse(build.first { it.role == "system" }.content.orEmpty().contains("只读规划模式"))

        val plan = assembler.assemble("s-runmode", nativeModel(), "", sessionRunMode = "plan")
        val planPrompt = plan.first { it.role == "system" }.content.orEmpty()
        assertTrue(planPrompt.contains("只读规划模式"))
        // 与宿主侧硬拦截的文案对齐：不得重试、不得声称已完成
        assertTrue(planPrompt.contains("不要重试"))
    }

    @Test
    fun `missing session run mode falls back to the global default`() = runBlocking {
        AgentApprovalRepository(database.agentApprovalDao()).setRunMode(RunMode.PLAN)
        push("s-runmode-global", UserMessage("u1", 1L, "看看这个项目要怎么改"), AssistantText("a1", 2L, "好的"))

        val out = assembler.assemble("s-runmode-global", nativeModel(), "", sessionRunMode = null)
        assertTrue(out.first { it.role == "system" }.content.orEmpty().contains("只读规划模式"))
    }

    @Test
    fun `routed rule blocks accumulate across the session instead of following latest message`() = runBlocking {
        val promptAssets = PromptAssetLoader(ApplicationProvider.getApplicationContext())
        val workflowBlock = promptAssets.read("prompts/system/workflow.md")
        push(
            "s-routed",
            UserMessage("u1", 1L, "请帮我实现一个功能"),
            AssistantText("a1", 2L, "好的"),
            UserMessage("u2", 3L, "继续"),
        )
        val out = assembler.assemble("s-routed", nativeModel(), "")
        val systemPrompt = out.first { it.role == "system" }.content.orEmpty()
        // 信号出现在历史轮（u1），最新轮（u2）无信号——规则块仍应常驻（累计语义）
        assertTrue(systemPrompt.contains(workflowBlock.trim().take(40)))
    }

    @Test
    fun `skill mentioned once stays injected for the session`() = runBlocking {
        // 累计提及语义：u1 提及技能，u2 未提及——技能章节仍应常驻本会话 system prompt
        database.agentSkillDao().upsert(
            AgentSkillEntity(
                id = "skill-reviewer", name = "reviewer", description = "审查",
                systemPrompt = "REVIEWER_MARKER_PROMPT 专项审查规则", triggerCommand = "/reviewer",
                iconName = "", isEnabled = true, isBuiltin = false, isImmutable = false,
                category = "review", resourcePath = null,
            ),
        )
        push(
            "s-skill",
            UserMessage("u1", 1L, "@reviewer 帮我审一下"),
            AssistantText("a1", 2L, "好的"),
            UserMessage("u2", 3L, "继续"),
        )
        val out = assembler.assemble("s-skill", nativeModel(), "")
        val systemPrompt = out.first { it.role == "system" }.content.orEmpty()
        assertTrue(systemPrompt.contains("REVIEWER_MARKER_PROMPT"))
    }

    @Test
    fun `split turn compaction bridges retained suffix with a leading user message`() = runBlocking {
        val session = "s-split-turn"
        push(session, UserMessage("u1", 1L, "超长任务：连续很多步"))
        repeat(40) { index ->
            store.append(
                session,
                AssistantText("a-$index", 2L + index, "步骤 $index " + "x".repeat(4_000)),
            )
        }

        // 小上下文窗口强制触发压缩；CompactionManager 无 summarizer → 机械摘要，无 LLM 调用。
        val out = assembler.assemble(session, nativeModel(tokens = 40_000), workspacePath = "")

        val firstNonSystem = out.first { it.role != "system" }
        assertEquals("user", firstNonSystem.role)
        assertTrue(
            "split-turn 保留段应从合成 user 桥接开始，而非直接以 assistant 开头",
            firstNonSystem.content.orEmpty().contains("中途继续"),
        )
    }
}
