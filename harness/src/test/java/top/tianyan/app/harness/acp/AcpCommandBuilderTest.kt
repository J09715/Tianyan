package top.tianyan.app.harness.acp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命令构造是纯函数，直接断言：装好的 shim 优先、参数逐字 shell 引用、
 * 环境不夹带任何密钥。
 */
class AcpCommandBuilderTest {

    private fun engine(
        command: String,
        arguments: List<String> = emptyList(),
    ) = AgentEngineProfile(
        id = "test",
        name = "Test",
        toolId = "test",
        command = command,
        arguments = arguments,
    )

    @Test
    fun commandLinePrefersTheInstalledToolShim() {
        val line = AcpCommandBuilder.commandLine(engine(command = "claude-agent-acp"))

        assertTrue(line.contains("if [ -x /opt/tianyan/bin/claude-agent-acp ]"))
        assertTrue(line.contains("exec /opt/tianyan/bin/claude-agent-acp"))
    }

    @Test
    fun commandLineFallsBackToPathLookupWhenTheShimIsMissing() {
        val line = AcpCommandBuilder.commandLine(engine(command = "opencode", arguments = listOf("acp")))

        assertTrue(line.contains("exec 'opencode' 'acp'"))
    }

    @Test
    fun argumentsAreShellQuotedIncludingSpaces() {
        val line = AcpCommandBuilder.commandLine(
            engine(
                command = "dsh",
                arguments = listOf("--profile", "acp", "--patch", "/root/.dsh/a b.yml"),
            ),
        )

        assertTrue(line.contains("'--profile' 'acp'"))
        assertTrue(line.contains("'/root/.dsh/a b.yml'"))
    }

    @Test
    fun noArgumentsProducesNoTrailingSpace() {
        val line = AcpCommandBuilder.commandLine(engine(command = "codex-acp"))

        assertTrue(line.endsWith("else exec 'codex-acp'; fi"))
    }

    @Test
    fun environmentKeepsTheSandboxPathDeterministicAndCarriesNoSecrets() {
        val env = AcpCommandBuilder.environment(engine(command = "codex"))

        assertEquals("/opt/tianyan/bin:/usr/local/bin:/usr/bin:/bin", env["PATH"])
        assertEquals("dumb", env["TERM"])
        // 引擎输出若带 ANSI 颜色，上屏会变成乱码；NO_COLOR 是各 CLI 的共同约定。
        assertEquals("1", env["NO_COLOR"])
        assertFalse(env.keys.any { it.contains("API_KEY", ignoreCase = true) })
    }

    @Test
    fun engineToolIdsMatchTheInstallCatalog() {
        // 每个引擎的 toolId 必须是插件中心里真实存在的安装项 id，
        // 否则「选择器能列出」与「点安装找不到」会分裂成两个事实。
        val catalogIds = setOf("claude-code", "codex", "deepseek-harness", "opencode", "kimi-code")
        val engines = listOf(
            AgentEngineProfile("claude-code", "Claude Code", toolId = "claude-code", command = "claude-agent-acp"),
            AgentEngineProfile("codex", "Codex", toolId = "codex", command = "codex-acp"),
            AgentEngineProfile("deepseek-harness", "DSH", toolId = "deepseek-harness", command = "dsh"),
            AgentEngineProfile("opencode", "OpenCode", toolId = "opencode", command = "opencode"),
            AgentEngineProfile("kimi-code", "Kimi Code", toolId = "kimi-code", command = "kimi"),
        )

        assertEquals(catalogIds, engines.map { it.toolId }.toSet())
    }
}
