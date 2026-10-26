package top.tianyan.app.harness.environment

import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.emptyFlow
import top.tianyan.app.core.model.McpServerConfig
import top.tianyan.app.core.model.McpTransportType
import top.tianyan.app.harness.mcp.LinuxMcpStdioChannelFactory
import top.tianyan.app.harness.mcp.McpCommandBuilder
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.environment.*
import top.tianyan.app.runtime.shell.*

internal class McpStartupFixture {
    val server = McpServerConfig("server", "server", transportType = McpTransportType.STDIO, command = "mcp")
    val worlds = mutableListOf<World>()
    var beforeEnvironment: suspend (World) -> Unit = {}
    var beforeSession: suspend (World) -> Unit = {}
    val runtime = Proxy.newProxyInstance(LinuxRuntime::class.java.classLoader, arrayOf(LinuxRuntime::class.java)) {
            _, method, _ -> error("local fallback: ${method.name}")
    } as LinuxRuntime
    val factory = LinuxMcpStdioChannelFactory(runtime, McpCommandBuilder(), ExecutionEnvironmentFactory { owner, workspace, _ ->
        World(owner, workspace).also { worlds += it; beforeEnvironment(it) }
    })

    inner class World(owner: String = "owner", workspace: String = "/workspace") : ExecutionEnvironment by
        FakeWorld(ExecutionEnvironmentId("remote", "linux", workspace, owner)) {
        var closes = 0
        var closeFailures = 0
        var opens = 0
        val session = Session()
        override suspend fun openSession(config: SessionConfig): LinuxSession {
            opens++; beforeSession(this); return session
        }
        override suspend fun close() {
            closes++
            if (closeFailures-- > 0) error("environment close")
        }
    }

    class Session : LinuxSession {
        var closes = 0
        var closeFailures = 0
        override var isAlive = true
        override val output = emptyFlow<TerminalOutput>()
        override suspend fun write(data: ByteArray) {}
        override suspend fun resize(columns: Int, rows: Int) {}
        override suspend fun interrupt() {}
        override suspend fun close() {
            closes++
            if (closeFailures-- > 0) error("session close")
            isAlive = false
        }
    }
}
