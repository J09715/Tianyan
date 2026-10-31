package top.tianyan.app.webchat

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.annotation.Config
import top.tianyan.app.core.database.*
import top.tianyan.app.core.database.task.AgentTaskRepository
import top.tianyan.app.core.model.*
import top.tianyan.app.core.tools.*
import top.tianyan.app.di.tianyanModule
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.harness.mcp.*
import top.tianyan.app.harness.plugins.HarnessPluginRegistry
import top.tianyan.app.harness.session.SessionControl
import top.tianyan.app.runtime.*
import top.tianyan.app.runtime.webchat.WebChatTaskState
import top.tianyan.app.testing.Aarch64AwareRobolectricTestRunner

/** Actual package import/installation + JSON-RPC transport + Web approval/resume; no model API. */
@RunWith(Aarch64AwareRobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class ExternalPackageEndToEndTest {
    @Test fun installedExternalProviderCallsVersionBoundToolOnlyAfterApproval() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.applicationInfo.nativeLibraryDir = context.filesDir.path
        val effects = AtomicInteger()
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        var domain = ""
        val factory = object : McpStdioChannelFactory {
            override suspend fun open(server: McpServerConfig): McpStdioChannel {
                assertEquals("example.external", server.pluginIdentity?.pluginId)
                assertEquals("ubuntu", server.pluginDistribution)
                opens.incrementAndGet()
                return object : McpStdioChannel {
                    override val incoming = Channel<String>(Channel.UNLIMITED)
                    override var isAlive = true
                    override suspend fun close() { if (isAlive) closes.incrementAndGet(); isAlive = false; incoming.close() }
                    override suspend fun writeLine(line: String) {
                        val request = Json.parseToJsonElement(line).jsonObject
                        val id = request["id"] ?: return
                        val result = when (request["method"]?.jsonPrimitive?.content) {
                            "initialize" -> Json.parseToJsonElement("""{"protocolVersion":"2024-11-05","capabilities":{"tools":{}},"serverInfo":{"name":"fixture","version":"1"}}""")
                            "tools/call" -> {
                                val params = request.getValue("params").jsonObject
                                val response = if (params.getValue("name").jsonPrimitive.content == "provider") {
                                    if (params.toString().contains("external-effect")) """{"content":"external-chain-complete","toolCalls":[]}"""
                                    else """{"toolCalls":[{"id":"external-call","name":"use_capability","arguments":{"action":"call","server":"$domain","tool":"echo","arguments":{}}}]}"""
                                } else { effects.incrementAndGet(); "external-effect" }
                                buildJsonObject { putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", response) }) }; put("isError", false) }
                            }
                            else -> buildJsonObject {}
                        }
                        incoming.send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) }.toString())
                    }
                }
            }
        }
        val application = koinApplication {
            androidContext(context)
            modules(tianyanModule, module {
                single<LinuxRuntime> {
                    val paths = get<RuntimePathManager>()
                    object : LinuxRuntime by FakeLinuxRuntime() { override fun workspacePath() = paths.workspaceDir }
                }
                single { OkHttpClient.Builder().addInterceptor { error("Network forbidden") }.build() }
                single<McpStdioChannelFactory> { factory }
            })
        }
        val koin = application.koin
        var sessionId: String? = null
        try {
            val source = File(context.cacheDir, "external-source").apply { mkdirs() }
            File(source, "payload/bin").mkdirs()
            File(source, "payload/bin/external-fixture").writeText("#!/bin/sh\necho fixture\n")
            val schema = Json.parseToJsonElement("""{"type":"object","properties":{},"additionalProperties":false}""").jsonObject
            val manifest = ToolManifest(2, "external-fixture", "External fixture", "Offline test", source = "LOCAL", offlineOnly = true,
                version = "1.0.0", installMethod = "LOCAL_PACKAGE", commandLinks = listOf("external-fixture"),
                installSteps = listOf("mkdir -p \"\$TIANYAN_TOOL_DIR/bin\"; cp \"\$TIANYAN_PLUGIN_PAYLOAD/bin/external-fixture\" \"\$TIANYAN_TOOL_DIR/bin/external-fixture\"; chmod +x \"\$TIANYAN_TOOL_DIR/bin/external-fixture\""),
                plugin = PluginPackageSpec("example.external", PackageApiRange(1, 1), "PROCESS",
                    extensions = listOf(PackageExtension("echo", "TOOL"), PackageExtension("offline", "PROVIDER")),
                    harness = PackageHarnessSpec("external-fixture", tools = listOf(PackageCallable("echo", "Record fixture", schema)),
                        providers = listOf(PackageProvider("offline", "provider")))))
            File(source, "manifest.json").writeText(Json.encodeToString(ToolManifest.serializer(), manifest))
            val archive = PluginDebugPackager.pack(source)
            val manager = koin.get<ToolManager>()
            manager.startLocalPluginImport(Uri.fromFile(archive), archive.name).join()
            assertTrue(manager.localPluginImportState.value.toString(), manager.localPluginImportState.value is LocalPluginImportState.PendingConfirmation)
            assertEquals(0, opens.get())
            manager.confirmLocalPluginImport()!!.join()
            assertTrue(manager.localPluginImportState.value.toString(), manager.localPluginImportState.value is LocalPluginImportState.Succeeded)
            withTimeout(10_000) { koin.get<InstalledPluginCatalog>().installed.first { it.isNotEmpty() } }
            domain = "plugin." + koin.get<HarnessPluginRegistry>().catalog.plugins.single { it.id.startsWith("example.external.v") }.id
            assertEquals(0, opens.get())
            val models = koin.get<AiModelRepository>()
            models.upsert(AiModelEntity("external", "External", "plugin.example.external.offline", "plugin-default", "https://plugin.invalid", isActive = true, createdAt = 1))
            val sessions = koin.get<HarnessSessionRepository>()
            val approvals = koin.get<AgentApprovalRepository>()
            val control = koin.get<SessionControl>()
            val gateway = TianyanWebChatAgentGateway(control, sessions, models, approvals, koin.get<AgentTaskRepository>())
            val id = gateway.createSession("External fixture", "").also { sessionId = it }
            sessions.upsert(sessions.findById(id)!!.copy(approvalMode = ApprovalMode.REQUEST.id))
            val receipt = gateway.send(id, "Run the external fixture", emptyList())
            val request = withTimeout(20_000) { approvals.pendingForSession(id).first { it.isNotEmpty() }.single() }
            assertEquals(0, effects.get())
            assertTrue(gateway.resolveApproval(id, request.id, true))
            val terminal = withTimeout(20_000) { gateway.observeTask(id, receipt.taskId!!).first { it in setOf(WebChatTaskState.COMPLETED, WebChatTaskState.FAILED, WebChatTaskState.SUSPENDED) } }
            assertEquals(WebChatTaskState.COMPLETED, terminal)
            assertEquals(1, effects.get())
            assertTrue(gateway.messages(id).any { it.content.toString().contains("external-chain-complete") })
            assertEquals(1, control.persistedMessages(id).filterIsInstance<ToolResult>().count { it.success && it.output.contains("external-effect") })
            assertEquals(opens.get(), closes.get())
        } finally {
            sessionId?.let { koin.get<SessionControl>().cancel(it) }
            runCatching { koin.get<AppDatabase>().close() }
            application.close()
        }
    }
}
