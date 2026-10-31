package top.tianyan.app.harness.mcp

import top.tianyan.app.core.common.shell.ShellQuote
import top.tianyan.app.core.model.McpServerConfig

/**
 * Quoting helpers for MCP STDIO subprocesses. Kept separate so unit tests can lock down
 * command-line construction without spinning up a Linux session.
 */
class McpCommandBuilder() {
    fun commandLine(server: McpServerConfig, configureTty: Boolean = true): String {
        require(server.command.isNotBlank()) { "MCP STDIO server requires a non-blank command" }
        val argv = (listOf(server.command) + server.args).joinToString(" ", transform = ShellQuote::of)
        return (if (configureTty) "stty raw -echo; " else "") + "exec " + argv
    }

    fun fingerprint(server: McpServerConfig) =
        server.command + "|" + server.args.joinToString(",") + "|${server.catalogRevision}|" +
            // B9: env 按 key 排序后再拼接，消除 map 迭代序不稳定导致的指纹抖动（与 McpManager 的 toSortedMap 对齐）
            server.env.entries.sortedBy { it.key }.joinToString(",") { it.key + "=" + it.value }

    /**
     * 将内置 MCP 的默认 `--repository /workspace` 绑定到当前会话工作区。
     * 用户自定义路径保持不动；工作区为空或仍是 `/workspace` 时原样返回。
     */
    fun bindWorkspaceRepository(server: McpServerConfig, workspace: String): McpServerConfig {
        val repo = workspace.trim().trimEnd('/').takeIf { it.isNotBlank() } ?: return server
        val idx = server.args.indexOf("--repository")
        if (idx < 0 || idx + 1 >= server.args.size) return server
        if (server.args[idx + 1] != DEFAULT_REPOSITORY) return server
        if (repo == DEFAULT_REPOSITORY) return server
        return server.copy(args = server.args.toMutableList().also { it[idx + 1] = repo })
    }

    private companion object {
        const val DEFAULT_REPOSITORY = "/workspace"
    }
}
