package top.tianyan.app.harness

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.database.*
import top.tianyan.app.core.model.ApprovalMode
import top.tianyan.app.core.model.McpTransportType
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.datastore.AgentPreferences
import top.tianyan.app.core.datastore.SettingsDataStore
import top.tianyan.app.core.security.SecretManager
import top.tianyan.app.core.network.FileDownloader
import top.tianyan.app.core.security.SecretRedactor
import top.tianyan.app.harness.core.ToolCheckpoint
import top.tianyan.app.harness.core.ToolCheckpoints
import top.tianyan.app.harness.core.ToolGateDecision
import top.tianyan.app.harness.directory.*
import top.tianyan.app.harness.events.AgentEventLogger
import top.tianyan.app.harness.mcp.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.atomic.AtomicInteger
import top.tianyan.app.runtime.LinuxRuntime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CapabilityScriptPolicyTest {
    private lateinit var database: AppDatabase
    private lateinit var sessions: RoomHarnessSessionRepository
    private lateinit var approvals: AgentApprovalRepository
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        sessions = RoomHarnessSessionRepository(database.harnessSessionDao())
        approvals = AgentApprovalRepository(database.agentApprovalDao())
    }
    @After fun tearDown() { database.close() }

    private suspend fun seed(mode: ApprovalMode = ApprovalMode.REQUEST, runMode: String = "build") = sessions.upsert(
        HarnessSessionEntity("session", "test", 1, 1, null, approvalMode = mode.id, runMode = runMode),
    )

    private fun executor(hooks: List<ToolCheckpoint<ToolExecutionRequest, ToolResult>> = emptyList(), mcp: McpManager? = null): ToolExecutor {
        val paths = HarnessPathResolver()
        return ToolExecutor(WorkspaceFileAccess(context.cacheDir), paths,
            ApprovalPolicyEngine(paths), SecretRedactor(),
            hostToolBackend = HostCapabilityToolBackend(secretRedactor = SecretRedactor()),
            linuxCommandToolBackend = LinuxCommandToolBackend(unusedPort<LinuxRuntime>(), paths),
            downloadToolBackend = DownloadToolBackend(
                unusedPort<FileDownloader>(), WorkspaceFileAccess(context.cacheDir), WorkspaceMutationSnapshots(),
            ),
            contextMemoryToolBackend = ContextMemoryToolBackend(),
            askUserToolBackend = AskUserToolBackend(ApprovalPolicyEngine(paths), approvals),
            promptAssetToolBackend = PromptAssetToolBackend(),
            harnessServiceToolBackend = HarnessServiceToolBackend(),
            capabilityToolGateway = CapabilityToolGateway(mcp) { _, _, _, _ -> error("unexpected host dispatch") },
            approvalRepository = approvals, sessionDao = sessions, toolCheckpoints = ToolCheckpoints(hooks), mcpManager = mcp)
    }

    private fun script(code: String) = ToolCall("script-parent", 1, HarnessTool.MCP,
        buildJsonObject { put("action", "script"); put("code", code) }, rawToolName = "use_capability")

    private val twoMutations = "capability.call('host','package_disable',{package:'example.first'}); " +
        "capability.call('host','package_disable',{package:'example.second'}); 'done';"

    @Test fun `request mode pauses parent and stores only current call for approval replay`() = runBlocking {
        seed()
        val result = executor().execute(script(twoMutations), "session", operationId = "operation")
        assertTrue(result.awaitingApproval)
        assertFalse(result.success)
        assertEquals("script-parent", result.toolCallId)
        val request = approvals.pendingNow("session").single()
        assertEquals(result.approvalRequestId, request.id)
        assertEquals("script-parent", request.toolCallId)
        assertEquals("operation", request.operationId)
        assertEquals("use_capability", request.toolName)
        val args = Json.parseToJsonElement(request.argumentsJson) as JsonObject
        assertEquals("\"call\"", args["action"].toString())
        assertFalse(args.containsKey("code"))
        assertTrue(args.toString().contains("example.first"))
        assertFalse(args.toString().contains("example.second"))
        assertEquals(1, NestedCalls.read(result.metadata)!!.calls.size)
    }

    @Test fun `plan mode rejects each host mutation without approval or host dispatch`() = runBlocking {
        seed(ApprovalMode.FULL_ACCESS, "plan")
        val result = executor().execute(script("capability.call('host','package_disable',{package:'example.app'});"), "session")
        assertTrue(approvals.pendingNow("session").isEmpty())
        val record = NestedCalls.read(result.metadata)!!.calls.single()
        assertTrue(record.error!!.contains("PLAN"))
        assertEquals(NestedCalls.STATUS_ERROR, record.status)
    }

    @Test fun `background lane retains deferred flag and terminates script without pending UI`() = runBlocking {
        seed()
        val result = executor().execute(script(twoMutations), "session", allowApprovalRequest = false)
        assertTrue(result.approvalDeferred)
        assertFalse(result.awaitingApproval)
        assertFalse(result.success)
        assertTrue(approvals.pendingNow("session").isEmpty())
        assertEquals(1, NestedCalls.read(result.metadata)!!.calls.size)
    }

    @Test fun `parent bypass approval does not authorize every script invocation`() = runBlocking {
        seed()
        val result = executor().execute(script(twoMutations), "session", bypassApproval = true)
        assertTrue(result.awaitingApproval)
        assertEquals(1, approvals.pendingNow("session").size)
    }

    @Test fun `JS catch cannot swallow approval handoff or run a second call`() = runBlocking {
        seed()
        val result = executor().execute(script("try { capability.call('host','package_disable',{package:'first'}); } " +
            "catch(e) {} capability.call('host','package_disable',{package:'second'}); 'done';"), "session")
        assertTrue(result.awaitingApproval)
        assertEquals(1, approvals.pendingNow("session").size)
        assertEquals(1, NestedCalls.read(result.metadata)!!.calls.size)
    }

    @Test fun `script nested calls pass checkpoints before reaching host backend`() = runBlocking {
        seed(ApprovalMode.FULL_ACCESS)
        val seen = mutableListOf<String>()
        val hook = object : ToolCheckpoint<ToolExecutionRequest, ToolResult> {
            override val id = "veto-inner"
            override suspend fun before(request: ToolExecutionRequest): ToolGateDecision {
                val action = request.call.args["action"].toString()
                seen += action
                return if (action == "\"call\"") ToolGateDecision.Block("blocked nested effect") else ToolGateDecision.Allow
            }
            override suspend fun after(request: ToolExecutionRequest, result: ToolResult): String? = null
        }
        val result = executor(listOf(hook)).execute(script("capability.call('host','package_disable',{package:'example.app'});"), "session")
        assertEquals(listOf("\"script\"", "\"call\""), seen)
        assertTrue(NestedCalls.read(result.metadata)!!.calls.single().error!!.contains("blocked nested effect"))
    }

    @Test fun `invalid nested parameters are rejected before creating an approval`() = runBlocking {
        seed()
        val result = executor().execute(script("capability.call('host','package_disable',{});"), "session")
        assertFalse(result.awaitingApproval)
        assertTrue(approvals.pendingNow("session").isEmpty())
        assertEquals(NestedCalls.STATUS_BLOCKED, NestedCalls.read(result.metadata)!!.calls.single().status)
    }

    @Test fun `partial script effects are not replayed when approving its next call`() = runBlocking {
        seed()
        val metadata = mutableMapOf<String, String>()
        val completed = mutableListOf<String>()
        val dispatcher = ScriptCapabilityDispatcher("script-parent", metadata, null, { it }) { call ->
            if (completed.isEmpty()) {
                completed += call.args.toString()
                ToolResult("first-result", 1, call.id, true, "first completed", imageDataUrl = "data:image/png;base64,fake")
            } else executor().execute(call, "session")
        }
        val failure = runCatching { CapabilityScriptRunner(innerCall = dispatcher::call).execute(twoMutations, 10_000) }
            .exceptionOrNull()
        assertTrue(failure is ScriptCallInterrupted)
        assertEquals(2, NestedCalls.read((failure as ScriptCallInterrupted).result.metadata)!!.calls.size)
        assertEquals("data:image/png;base64,fake", failure.result.imageDataUrl)
        assertFalse(failure.result.metadata.containsKey("image_payload"))
        val request = approvals.pendingNow("session").single()
        assertTrue(request.argumentsJson.contains("example.second"))
        val replayed = mutableListOf<String>()
        val veto = object : ToolCheckpoint<ToolExecutionRequest, ToolResult> {
            override val id = "observe-replay"
            override suspend fun before(request: ToolExecutionRequest): ToolGateDecision {
                replayed += request.call.args.toString()
                return ToolGateDecision.Block("fake device")
            }
            override suspend fun after(request: ToolExecutionRequest, result: ToolResult): String? = null
        }
        val replay = executor(listOf(veto)).execute(ToolCall(request.toolCallId, 1, HarnessTool.MCP,
            Json.parseToJsonElement(request.argumentsJson) as JsonObject, rawToolName = request.toolName),
            "session", bypassApproval = true)
        assertEquals(1, completed.size)
        assertEquals(listOf(request.argumentsJson), replayed)
        assertTrue(replay.output.contains("其余部分未继续执行"))
    }

    @Test fun `router without policy dispatch fails closed for script`() = runBlocking {
        val router = CapabilityToolRouter(null) { _, _, _, _ -> error("uncontrolled host dispatch") }
        val result = router.execute(script(twoMutations).args, "", "script-parent", null, "session", mutableMapOf())
        assertFalse(result.first)
        assertTrue(result.second.contains("未初始化"))
    }

    @Test fun `cold MCP discovery applies destructive annotations before request approval`() = runBlocking {
        seed()
        withMcp { manager, calls ->
            val result = executor(mcp = manager).execute(script("capability.call('remote','write_record',{input:'value'});"), "session")
            assertTrue(result.awaitingApproval)
            assertEquals("high", approvals.pendingNow("session").single().riskLevel)
            assertEquals(0, calls.get())
        }
    }

    @Test fun `plan blocks remote MCP execution and malformed input never reaches policy or transport`() = runBlocking {
        seed(ApprovalMode.FULL_ACCESS, "plan")
        withMcp { manager, calls ->
            val blocked = executor(mcp = manager).execute(script("capability.call('remote','write_record',{input:'value'});"), "session")
            assertTrue(NestedCalls.read(blocked.metadata)!!.calls.single().error!!.contains("PLAN"))
            seed()
            val invalid = executor(mcp = manager).execute(script("capability.call('remote','write_record',{input:123});"), "session")
            assertEquals(NestedCalls.STATUS_BLOCKED, NestedCalls.read(invalid.metadata)!!.calls.single().status)
            assertTrue(approvals.pendingNow("session").isEmpty())
            assertEquals(0, calls.get())
        }
    }

    @Test fun `full access remote calls preserve input parameter and create only parent nested audit`() = runBlocking {
        seed(ApprovalMode.FULL_ACCESS)
        withMcp { manager, calls ->
            val result = executor(mcp = manager).execute(script(
                "var a = capability.call('remote','write_record',{input:'one'}); " +
                    "var b = capability.call('remote','write_record',{input:'two'}); a.output + '|' + b.output;"), "session")
            assertTrue(result.success)
            assertEquals(2, calls.get())
            assertEquals(2, NestedCalls.read(result.metadata)!!.calls.size)
            assertTrue(result.output.contains("one|two"))
        }
    }

    private suspend fun withMcp(test: suspend (McpManager, AtomicInteger) -> Unit) {
        val server = MockWebServer()
        val calls = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val payload = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val result = when (payload["method"]!!.jsonPrimitive.content) {
                    "initialize" -> """{"protocolVersion":"$MCP_PROTOCOL_VERSION"}"""
                    "notifications/initialized" -> return MockResponse().setResponseCode(202)
                    "tools/list" -> """{"tools":[{"name":"write_record","description":"fake write","inputSchema":{"type":"object","properties":{"input":{"type":"string"}},"required":["input"]},"annotations":{"destructiveHint":true}}]}"""
                    "tools/call" -> {
                        calls.incrementAndGet()
                        val args = payload["params"]!!.jsonObject["arguments"]!!.jsonObject
                        assertEquals(setOf("input"), args.keys)
                        """{"content":[{"type":"text","text":${args["input"]}}]}"""
                    }
                    else -> return MockResponse().setResponseCode(500)
                }
                return MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"jsonrpc":"2.0","id":${payload["id"]},"result":$result}""")
            }
        }
        server.start()
        val logger = AppLogger(context) { it }
        val secrets = SecretManager()
        val repo = McpServerRepository(database.mcpServerDao(), secrets)
        // No credentials in this fixture: seed the row directly without AndroidKeyStore.
        database.mcpServerDao().upsert(McpServerEntity("remote", "fake remote", "",
            McpTransportType.SSE.name, "", "", "", server.url("/mcp").toString(),
            isEnabled = true, isBuiltin = false))
        val commands = McpCommandBuilder()
        val stdio = McpStdioTransport(Json, commands, unusedPort<McpStdioChannelFactory>())
        val http = McpHttpTransport(OkHttpClient(), Json { ignoreUnknownKeys = true }, logger)
        val manager = McpManager(repo, stdio, http, commands, unusedPort<LinuxRuntime>(), logger,
            AgentEventLogger(AgentPreferences(SettingsDataStore(context, secrets)), logger))
        try { test(manager, calls) } finally { manager.invalidateServer("remote"); server.shutdown() }
    }

    private inline fun <reified T> unusedPort(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, _ -> error("Unexpected backend call: ${method.name}") } as T
}
