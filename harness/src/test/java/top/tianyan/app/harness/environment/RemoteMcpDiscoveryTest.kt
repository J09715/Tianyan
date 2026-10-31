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
class RemoteMcpDiscoveryTest {
    @Test fun `remote discovery works with no phone RootFS in bound sessions and settings checks`() = runBlocking {
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
        val factory = object : McpStdioChannelFactory {
            override suspend fun open(server: McpServerConfig): McpStdioChannel {
                openedScopes += server.id
                val scopedTool = toolName
                val scopedFailure = failDiscovery
                return object : McpStdioChannel {
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
                    val result = if (request["method"]?.jsonPrimitive?.content == "tools/list")
                        """{"tools":[{"name":"$scopedTool","inputSchema":{"type":"object"}}]}"""
                    else """{"protocolVersion":"2025-03-26","capabilities":{}}"""
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
        // Outside a session, settings discovery follows the selected SSH backend.
        assertEquals(listOf("remote_tool"), manager.getActiveMcpTools().map { it.name })
        withTimeout(2_000) { manager.connectionStates.first { it[row.id] == McpConnectionState.ONLINE } }
        active.value = "other"
        withTimeout(2_000) { manager.connectionStates.first { it[row.id] == null } }
        toolName = "other_tool"
        assertTrue(manager.cachedToolsOf(row.id).isEmpty())
        assertEquals(listOf("other_tool"), manager.getActiveMcpTools().map { it.name })
        assertTrue(manager.checkConnection(McpServerConfig(row.id, row.name, transportType = McpTransportType.STDIO, command = "mcp")))
        assertEquals(2, openedScopes.distinct().size)
        profiles.value = profiles.value.map { if (it.id == "other") it.copy(host = "server-c") else it }
        toolName = "edited_tool"
        assertTrue(manager.cachedToolsOf(row.id).isEmpty())
        assertEquals(listOf("edited_tool"), manager.getActiveMcpTools().map { it.name })
        assertTrue(manager.checkConnection(McpServerConfig(row.id, row.name, transportType = McpTransportType.STDIO, command = "mcp")))
        assertEquals(3, openedScopes.distinct().size)
        val registry = SessionExecutionEnvironments(ExecutionEnvironmentFactory { owner, workspace, _ ->
            FakeWorld(ExecutionEnvironmentId("ssh", "ssh:host", workspace, owner))
        })
        toolName = "bound_tool"
        registry.activity("one", "/srv/demo") {
            assertTrue(manager.cachedToolsOf(row.id).isEmpty())
            assertEquals(listOf("bound_tool"), manager.getActiveMcpTools().map { it.name })
        }
        registry.closeSession("one")
        manager.invalidateServer(row.id)
        active.value = profile.id; failDiscovery = true
        assertTrue(manager.getActiveMcpTools().isEmpty())
        active.value = "other"; failDiscovery = false; toolName = "healthy_tool"
        assertEquals(listOf("healthy_tool"), manager.getActiveMcpTools().map { it.name })
        stdio.closeConnection(row.id)
    }
}
