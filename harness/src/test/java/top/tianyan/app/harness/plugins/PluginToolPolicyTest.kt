package top.tianyan.app.harness.plugins

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.database.*
import top.tianyan.app.core.model.*
import top.tianyan.app.core.network.FileDownloader
import top.tianyan.app.core.security.SecretRedactor
import top.tianyan.app.harness.*
import top.tianyan.app.harness.directory.CapabilityToolGateway
import top.tianyan.app.plugin.api.*
import top.tianyan.app.runtime.LinuxRuntime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class PluginToolPolicyTest {
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> error("Unexpected ${method.name}") } as T
    private fun fixture(runMode: RunMode, action: suspend (ToolExecutor, () -> Int) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val sessions = RoomHarnessSessionRepository(database.harnessSessionDao())
            sessions.upsert(HarnessSessionEntity("s", "test", 1, 1, null, approvalMode = ApprovalMode.REQUEST.id, runMode = runMode.id))
            val approvals = AgentApprovalRepository(database.agentApprovalDao())
            var calls = 0
            val plugins = HarnessPluginRegistry(listOf(HarnessPlugin(
                PluginDescriptor("example.policy", "1.0.0", extensions = listOf(PluginExtension("effect", PluginExtensionPoint.TOOL))),
                tools = listOf(PluginTool("effect", "An effect", Json.parseToJsonElement("""{"type":"object","properties":{},"additionalProperties":false}""").jsonObject) { calls++; PluginToolOutput(true, "done") }),
            )))
            val resolver = HarnessPathResolver()
            val files = WorkspaceFileAccess(context.cacheDir)
            val executor = ToolExecutor(files, resolver, ApprovalPolicyEngine(resolver), SecretRedactor(),
                HostCapabilityToolBackend(secretRedactor = SecretRedactor()), LinuxCommandToolBackend(unused<LinuxRuntime>(), resolver),
                DownloadToolBackend(unused<FileDownloader>(), files, WorkspaceMutationSnapshots()), ContextMemoryToolBackend(),
                AskUserToolBackend(ApprovalPolicyEngine(resolver), approvals), PromptAssetToolBackend(), HarnessServiceToolBackend(),
                CapabilityToolGateway(null, plugins = plugins) { _, _, _, _ -> error("Host dispatch") },
                approvalRepository = approvals, sessionDao = sessions)
            action(executor) { calls }
        } finally { database.close() }
    }
    private fun call(action: String = "call") = ToolCall("call", 1, HarnessTool.MCP,
        Json.parseToJsonElement(if (action == "script") """{"action":"script","code":"capability.call('plugin.example.policy','effect',{});"}"""
        else """{"action":"call","server":"plugin.example.policy","tool":"effect","arguments":{}}""").jsonObject, rawToolName = "use_capability")
    @Test fun planBlocksPluginExecution() = fixture(RunMode.PLAN) { executor, calls ->
        val result = executor.execute(call(), "s")
        assertFalse(result.success); assertEquals("PLAN_BLOCKED", result.errorCode); assertEquals(0, calls())
    }
    @Test fun backgroundLaneDefersApprovalWithoutExecuting() = fixture(RunMode.BUILD) { executor, calls ->
        val result = executor.execute(call(), "s", allowApprovalRequest = false)
        assertTrue(result.approvalDeferred); assertEquals(0, calls())
    }
    @Test fun scriptReentryRetainsPluginApprovalBoundary() = fixture(RunMode.BUILD) { executor, calls ->
        val result = executor.execute(call("script"), "s", allowApprovalRequest = false)
        assertEquals(0, calls()); assertTrue(result.approvalDeferred || !result.success)
    }
}
