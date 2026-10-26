package top.tianyan.app.harness.environment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Proxy
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.common.logging.SensitiveDataRedactor
import top.tianyan.app.core.database.*
import top.tianyan.app.core.datastore.AgentPreferences
import top.tianyan.app.core.datastore.RemoteServerRepository
import top.tianyan.app.core.model.*
import top.tianyan.app.core.security.SecretManager
import top.tianyan.app.harness.events.AgentEventLogger
import top.tianyan.app.harness.mcp.*
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.environment.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class McpMetadataLifecycleTest {
    @Test fun `closed execution owners release scoped discovery cache`() = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        val row = McpServerEntity("remote-mcp", "remote", "", "STDIO", "mcp", "", "", "", isEnabled = true, isBuiltin = false)
        val dao = Proxy.newProxyInstance(McpServerDao::class.java.classLoader, arrayOf(McpServerDao::class.java)) { _, method, _ ->
            check(method.name == "observeAll"); flowOf(listOf(row))
        } as McpServerDao
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = AgentPreferences(top.tianyan.app.core.datastore.SettingsDataStore(context, SecretManager()))
        val runtime = Proxy.newProxyInstance(LinuxRuntime::class.java.classLoader, arrayOf(LinuxRuntime::class.java)) { _, method, _ ->
            error("local RootFS accessed: ${method.name}")
        } as LinuxRuntime
        val logger = AppLogger(context, SensitiveDataRedactor { it })
        val openedScopes = mutableListOf<String>()
        var toolName = "remote_tool"
        var failDiscovery = false
        var toolCalls = 0
        val factory = object : McpStdioChannelFactory {
            override suspend fun open(server: McpServerConfig): McpStdioChannel {
                openedScopes += server.id
                val scopedTool = toolName
                val scopedFailure = failDiscovery
                val binding = kotlinx.coroutines.currentCoroutineContext()[ExecutionEnvironmentContext]
                return object : McpStdioChannel {
                init { binding?.own { close() } }
                override val incoming = Channel<String>(Channel.UNLIMITED)
                override var isAlive = true
                override suspend fun close() { isAlive = false; incoming.close() }
                override suspend fun writeLine(line: String) {
                    val request = json.parseToJsonElement(line).jsonObject
                    val id = request["id"] ?: return
                    if (scopedFailure && request["method"]?.jsonPrimitive?.content == "tools/list") {
                        incoming.send("""{"jsonrpc":"2.0","id":$id,"error":{"code":-32000,"message":"host unavailable"}}""")
                        return
                    }
                    val result = when (request["method"]?.jsonPrimitive?.content) {
                        "tools/list" -> """{"tools":[{"name":"$scopedTool","inputSchema":{"type":"object"}}]}"""
                        "tools/call" -> { toolCalls++; """{"content":[{"type":"text","text":"executed"}]}""" }
                        else -> """{"protocolVersion":"2025-03-26","capabilities":{}}"""
                    }
                    incoming.send("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
                }
            }
            }
        }
        val profile = RemoteServer("ssh-host", "Host", "server-a", username = "agent", workspace = "/srv/projects")
        val active = kotlinx.coroutines.flow.MutableStateFlow<String?>(profile.id)
        val profiles = kotlinx.coroutines.flow.MutableStateFlow(listOf(profile, profile.copy(id = "other", host = "server-b")))
        val remoteServers = Proxy.newProxyInstance(RemoteServerRepository::class.java.classLoader, arrayOf(RemoteServerRepository::class.java)) { _, method, _ ->
            when (method.name) { "getActiveId" -> active; "getServers" -> profiles; else -> error("unexpected repository call") }
        } as RemoteServerRepository
        val stdio = McpStdioTransport(json, McpCommandBuilder(), factory, StandaloneMcpScope(remoteServers))
        val manager = McpManager(McpServerRepository(dao, SecretManager()), stdio,
            McpHttpTransport(OkHttpClient(), json, logger), McpCommandBuilder(), runtime, logger,
            AgentEventLogger(preferences, logger), remoteServers)
        val registry = SessionExecutionEnvironments(ExecutionEnvironmentFactory { owner, workspace, _ ->
            FakeWorld(ExecutionEnvironmentId("ssh", "ssh:host", workspace, owner))
        })
        registry.activity("live", "/srv/demo") { assertTrue(manager.getActiveMcpTools().isNotEmpty()) }
        registry.activity("live", "/srv/demo") {
            stdio.rewindIdleForTest(McpStdioTransport.IDLE_TIMEOUT_MS + 60_000)
            assertEquals(1, stdio.sweepIdleOnce(stdio.clock()))
            val opened = openedScopes.size
            val cached = manager.getActiveMcpTools().single()
            assertEquals(toolName, cached.name)
            manager.refreshConnections(probeSleeping = false)
            assertEquals("Discovery and opening settings must not wake cached sleeping MCP", opened, openedScopes.size)
            assertEquals(true to "executed", manager.executeTool(McpToolApiName.encode(cached), JsonObject(emptyMap()), "/srv/demo"))
            assertEquals(opened + 1, openedScopes.size)
            assertEquals(1, toolCalls)
        }
        repeat(12) { index ->
            val owner = "closed-$index"
            failDiscovery = index % 2 == 0
            registry.activity(owner, "/srv/demo") {
                assertEquals(!failDiscovery, manager.getActiveMcpTools().isNotEmpty())
            }
            registry.closeSession(owner)
        }
        registry.activity("live", "/srv/demo") { assertTrue(manager.cachedToolsOf(row.id).isNotEmpty()) }
        registry.closeSession("live")
        stdio.closeConnection(row.id)
        listOf("cache", "discoveryMutexes", "lastErrors", "discoveryCooldownUntil", "discoveryFailureStreak").forEach { name ->
            val field = McpManager::class.java.getDeclaredField(name).apply { isAccessible = true }
            assertEquals("Closed owners must not retain $name", 0, (field.get(manager) as Map<*, *>).size)
        }
        val statuses = McpManager::class.java.getDeclaredField("statuses").apply { isAccessible = true }.get(manager)
        val all = statuses.javaClass.getDeclaredField("all").apply { isAccessible = true }.get(statuses) as kotlinx.coroutines.flow.StateFlow<*>
        assertTrue((all.value as Map<*, *>).isEmpty())
        assertTrue(stdio.processStates.value.isEmpty())
    }
}
