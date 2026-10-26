package top.tianyan.app.harness.mcp

import top.tianyan.app.core.model.McpServerConfig
import top.tianyan.app.runtime.environment.ExecutionEnvironment

/** Map the supported workspace argument only; arbitrary user arguments stay explicit. */
internal fun mcpExecutionConfig(server: McpServerConfig, environment: ExecutionEnvironment?): McpServerConfig {
    if (environment?.id?.backend != "ssh") return server
    val args = server.args.toMutableList()
    val index = args.indexOf("--repository") + 1
    if (index > 0 && index < args.size) {
        val path = args[index]
        if (path == "/workspace") args[index] = environment.workingDirectory()
        else if (path.startsWith("/workspace/")) {
            require(path == environment.logicalWorkspace || path.startsWith("${environment.logicalWorkspace}/")) {
                "MCP 项目路径不属于当前会话工作区，请使用服务器实际路径"
            }
            args[index] = environment.workingDirectory(path)
        }
    }
    return server.copy(args = args)
}
