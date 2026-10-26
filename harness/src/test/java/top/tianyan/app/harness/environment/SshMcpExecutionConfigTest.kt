package top.tianyan.app.harness.environment

import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.McpServerConfig
import top.tianyan.app.core.model.McpTransportType
import top.tianyan.app.harness.mcp.LinuxMcpStdioChannelFactory
import top.tianyan.app.harness.mcp.McpCommandBuilder
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.environment.*
import top.tianyan.app.runtime.shell.*

class SshMcpExecutionConfigTest {
    private val runtime = Proxy.newProxyInstance(LinuxRuntime::class.java.classLoader, arrayOf(LinuxRuntime::class.java)) { _, _, _ ->
        error("phone Linux runtime must not be read")
    } as LinuxRuntime
    private val server = McpServerConfig("git", "git", transportType = McpTransportType.STDIO, command = "git-mcp", args = listOf("--repository", "/workspace/demo"))
    @Test fun `ordinary SSH user probes and starts MCP in remote project with mapped repository`() = runBlocking {
        val fake = FakeWorld(ExecutionEnvironmentId("ssh", "ssh:remote", "/srv/projects/demo", "one"))
        val world = object : ExecutionEnvironment by fake {
            override val logicalWorkspace = "/workspace/demo"
            override fun workingDirectory(explicit: String?) = if (explicit == null || explicit == "/workspace/demo") id.workspace else explicit
            override suspend fun execute(command: ShellCommand): CommandResult {
                assertEquals("/srv/projects/demo", command.workingDirectory)
                assertTrue(command.commandLine.startsWith("command -v"))
                return CommandResult(0, "/usr/bin/git-mcp", "", 1)
            }
            override suspend fun openSession(config: SessionConfig): LinuxSession {
                assertEquals(id.workspace, config.workingDirectory)
                assertTrue(config.commandLine.contains("'/srv/projects/demo'"))
                assertFalse(config.commandLine.contains("stty"))
                assertFalse(config.usePty)
                return fake.openSession(config)
            }
        }
        val registry = SessionExecutionEnvironments(ExecutionEnvironmentFactory { _, _, _ -> world })
        registry.activity("one", "/workspace/demo") { LinuxMcpStdioChannelFactory(runtime, McpCommandBuilder()).open(server).close() }
        registry.closeSession("one")
    }
    @Test fun `repository alias may already equal the actual remote workspace path`() {
        val world = FakeWorld(ExecutionEnvironmentId("ssh", "ssh:host", "/workspace/demo", "one"))
        val prepared = top.tianyan.app.harness.mcp.mcpExecutionConfig(server, world)
        assertEquals(listOf("--repository", "/workspace/demo"), prepared.args)
    }
    @Test fun `missing executable reports remote host and never starts protocol process`() = runBlocking {
        val fake = FakeWorld(ExecutionEnvironmentId("ssh", "ssh:remote", "/srv/projects", "one"))
        val world = object : ExecutionEnvironment by fake {
            override val label = "remote.example"
            override suspend fun execute(command: ShellCommand) = CommandResult(127, "", "not found", 1)
            override suspend fun openSession(config: SessionConfig): LinuxSession = error("must not start missing executable")
        }
        val registry = SessionExecutionEnvironments(ExecutionEnvironmentFactory { _, _, _ -> world })
        registry.activity("one", "/workspace") {
            try { LinuxMcpStdioChannelFactory(runtime, McpCommandBuilder()).open(server.copy(args = emptyList())); fail() }
            catch (e: IllegalStateException) { assertTrue(e.message!!.contains("remote.example")); assertTrue(e.message!!.contains("git-mcp")) }
        }
        registry.closeSession("one")
    }
}
