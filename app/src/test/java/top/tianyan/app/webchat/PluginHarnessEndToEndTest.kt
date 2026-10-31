package top.tianyan.app.webchat

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
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
import top.tianyan.app.core.model.ApprovalMode
import top.tianyan.app.di.tianyanModule
import top.tianyan.app.harness.*
import top.tianyan.app.harness.core.LlmApi
import top.tianyan.app.harness.plugins.*
import top.tianyan.app.harness.session.SessionControl
import top.tianyan.app.plugin.api.*
import top.tianyan.app.runtime.FakeLinuxRuntime
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.webchat.WebChatTaskState
import top.tianyan.app.testing.Aarch64AwareRobolectricTestRunner

@RunWith(Aarch64AwareRobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class PluginHarnessEndToEndTest {
    @Test fun webInputFakeProviderPluginToolApprovalResumeAndWebCompletion() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.applicationInfo.nativeLibraryDir = context.filesDir.path
        val executions = AtomicInteger()
        val calls = AtomicInteger()
        val plugin = HarnessPlugin(
            PluginDescriptor("example.e2e", "1.0.0", extensions = listOf(PluginExtension("record", PluginExtensionPoint.TOOL), PluginExtension("fake", PluginExtensionPoint.PROVIDER), PluginExtension("facts", PluginExtensionPoint.CONTEXT))),
            contexts = listOf(PluginContext("facts") { "plugin-fixture-background" }),
            tools = listOf(PluginTool("record", "Record a test action", Json.parseToJsonElement("""{"type":"object","properties":{},"additionalProperties":false}""").jsonObject) {
                assertTrue(it.sessionId.isNotBlank()); executions.incrementAndGet(); PluginToolOutput(true, "plugin-effect-recorded")
            }),
            providers = listOf(PluginProvider("fake", LlmApi.OPENAI_COMPLETIONS, "plugin-test") { _, _ -> object : LlmApiAdapter {
                override val api = LlmApi.OPENAI_COMPLETIONS
                override suspend fun chat(model: ModelConfig, messages: List<ApiMessage>): ChatResult {
                    calls.incrementAndGet(); assertNull(model.apiKey)
                    val backgrounds = messages.filter { it.content.orEmpty().contains("[插件背景资料") }
                    assertTrue("Plugin context must reach the actual provider request", backgrounds.isNotEmpty())
                    assertTrue(backgrounds.all { it.role == "user" })
                    assertTrue(backgrounds.any { it.content.orEmpty().contains("example.e2e/facts") && it.content.orEmpty().contains("plugin-fixture-background") })
                    return if (messages.any { it.content.orEmpty().contains("plugin-effect-recorded") }) ChatResult("plugin-chain-complete", emptyList())
                    else ChatResult(null, listOf(ApiToolCallSpec("plugin-call", "use_capability", """{"action":"call","server":"plugin.example.e2e","tool":"record","arguments":{}}""")))
                }
                override suspend fun chatStream(model: ModelConfig, messages: List<ApiMessage>, onReasoning: (String) -> Unit, onToolProgress: (ToolCallStreamProgress) -> Unit, onDelta: (String) -> Unit): ChatResult =
                    chat(model, messages).also { it.content?.let(onDelta) }
            } }),
        )
        val application = koinApplication {
            androidContext(context)
            modules(tianyanModule, module {
                single<LinuxRuntime> {
                    val paths = get<top.tianyan.app.runtime.RuntimePathManager>()
                    object : LinuxRuntime by FakeLinuxRuntime() { override fun workspacePath() = paths.workspaceDir }
                }
                single { OkHttpClient.Builder().addInterceptor { error("Network is forbidden in this fixture") }.build() }
                single<HarnessPlugin>(org.koin.core.qualifier.named("example.e2e")) { plugin }
            })
        }
        val koin = application.koin
        var sessionId: String? = null
        try {
            val models = koin.get<AiModelRepository>()
            models.upsert(AiModelEntity("fake", "Fake", "plugin-test", "fixture", "https://example.invalid", isActive = true, createdAt = 1))
            val sessions = koin.get<HarnessSessionRepository>()
            val approvals = koin.get<AgentApprovalRepository>()
            val control = koin.get<SessionControl>()
            val gateway = TianyanWebChatAgentGateway(control, sessions, models, approvals, koin.get<AgentTaskRepository>())
            val id = gateway.createSession("Plugin fixture", "")
            sessionId = id
            sessions.upsert(sessions.findById(id)!!.copy(approvalMode = ApprovalMode.REQUEST.id))
            val receipt = gateway.send(id, "Run the plugin fixture", emptyList())
            assertNotNull(receipt.taskId)
            val request = try { withTimeout(15_000) { approvals.pendingForSession(id).first { it.isNotEmpty() }.single() } }
            catch (timeout: TimeoutCancellationException) { throw AssertionError("No approval: calls=${calls.get()}, status=${control.sessionStatuses.value[id]}, messages=${control.persistedMessages(id)}", timeout) }
            assertEquals(0, executions.get())
            assertTrue(gateway.resolveApproval(id, request.id, true))
            val terminal = try { withTimeout(15_000) { gateway.observeTask(id, receipt.taskId!!).first { it in setOf(WebChatTaskState.COMPLETED, WebChatTaskState.FAILED, WebChatTaskState.SUSPENDED) } } }
            catch (timeout: TimeoutCancellationException) { throw AssertionError("No completion: calls=${calls.get()}, effects=${executions.get()}, task=${gateway.observeTask(id, receipt.taskId!!).first()}, status=${control.sessionStatuses.value[id]}, pending=${gateway.pendingApprovals(id)}, messages=${control.persistedMessages(id)}", timeout) }
            assertEquals(WebChatTaskState.COMPLETED, terminal)
            assertEquals(1, executions.get()); assertTrue(calls.get() >= 2)
            assertTrue(gateway.messages(id).any { it.content.toString().contains("plugin-chain-complete") })
            assertEquals(1, control.persistedMessages(id).filterIsInstance<ToolResult>().count { it.success && it.output.contains("plugin-effect-recorded") })
        } finally {
            sessionId?.let { koin.get<SessionControl>().cancel(it) }
            runCatching { koin.get<AppDatabase>().close() }
            application.close()
        }
    }
}
