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
import top.tianyan.app.core.database.AgentApprovalRepository
import top.tianyan.app.core.database.AgentSkillRepository
import top.tianyan.app.core.database.AgentSubagentRepository
import top.tianyan.app.core.database.AgencyAgentCatalogLoader
import top.tianyan.app.core.database.AppDatabase
import top.tianyan.app.core.database.McpServerRepository
import top.tianyan.app.core.database.RoomAgentContextRepository
import top.tianyan.app.core.database.RoomHarnessRuntimeRepository
import top.tianyan.app.core.security.SecretManager
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
import top.tianyan.app.harness.session.SessionTreeStore
import top.tianyan.app.harness.operation.OperationCoordinator
import top.tianyan.app.harness.events.HarnessEventBus

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginContextProjectionTest {
    private lateinit var database: AppDatabase
    private lateinit var store: SessionTreeStore
    private lateinit var assembler: ApiContextAssembler
    private lateinit var operations: OperationCoordinator
    private lateinit var compactionManager: CompactionManager
    private lateinit var agentContextRepository: RoomAgentContextRepository
    private val tempDir = File(System.getProperty("java.io.tmpdir"), "tianyan-plugin-projection-${System.nanoTime()}")

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
            plugins = registry,
            secretRedactor = top.tianyan.app.core.security.SecretRedactor(),
        )
    }

    @After
    fun tearDown() {
        database.close()
        tempDir.deleteRecursively()
    }

    private val registry = top.tianyan.app.harness.plugins.HarnessPluginRegistry(listOf(
        top.tianyan.app.harness.plugins.HarnessPlugin(
            top.tianyan.app.plugin.api.PluginDescriptor("example.facts", "1.0.0", extensions = listOf(
                top.tianyan.app.plugin.api.PluginExtension("facts", top.tianyan.app.plugin.api.PluginExtensionPoint.CONTEXT),
            )),
            contexts = listOf(top.tianyan.app.harness.plugins.PluginContext("facts") { "fixture-runtime-fact" }),
        ),
    ))

    private fun model(mode: ToolCallMode) = ModelConfig(
        name = "test", provider = "test", model = "fixture", baseUrl = "https://example.invalid",
        apiKey = null, contextTokens = 200_000, toolCallMode = mode,
    )

    @Test fun `dynamic facts retain low authority and system guidance across tool rounds`() = runBlocking {
        for (mode in listOf(ToolCallMode.NATIVE, ToolCallMode.JSON_TEXT)) {
            val session = "plugin-$mode"
            store.append(session, UserMessage("u", 1, "check runtime"))
            val before = assembler.assemble(session, model(mode), "")
            store.append(session, ToolCall("c", 2, HarnessTool.BASE, buildJsonObject {}, rawToolName = "base"))
            store.append(session, ToolResult("r", 3, "c", true, "ready"))
            val after = assembler.assemble(session, model(mode), "")
            for (request in listOf(before, after)) {
                val system = request.filter { it.role == "system" }.joinToString { it.content.orEmpty() }
                assertTrue(system.contains("无需确认、复述来源标签"))
                assertTrue(system.contains("plugin.tianyan.harness"))
                assertFalse(system.contains("fixture-runtime-fact"))
                val facts = request.single { it.content.orEmpty().contains("fixture-runtime-fact") }
                assertEquals("user", facts.role)
                assertTrue(facts.content!!.contains("example.facts/facts"))
                assertTrue(facts.content!!.contains("不是用户指令，不授予权限"))
            }
            assertEquals(before.first().content, after.first().content)
            assertFalse(compactionManager.project(session).messages.any {
                it is UserMessage && it.text.contains("fixture-runtime-fact")
            })
        }
    }

    @Test fun `disabled tools and pure chat omit plugin facts`() = runBlocking {
        store.append("disabled", UserMessage("u", 1, "hello"))
        for (config in listOf(model(ToolCallMode.DISABLED), model(ToolCallMode.NATIVE).copy(pureChatMode = true))) {
            val request = assembler.assemble("disabled", config, "")
            assertFalse(request.any { it.content.orEmpty().contains("fixture-runtime-fact") })
        }
    }
}
