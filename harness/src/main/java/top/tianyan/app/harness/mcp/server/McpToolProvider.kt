package top.tianyan.app.harness.mcp.server

import kotlinx.serialization.json.JsonObject

/**
 * 可挂载到 [McpToolDispatcher] 的工具来源。
 *
 * 内置浏览器工具由 dispatcher 直接持有 [top.tianyan.app.runtime.browser.tools.BrowserMcpTools]；
 * 其余来源（如被控端的 Harness 工具）以本接口聚合，避免 dispatcher 反向依赖 harness 执行层。
 *
 * [listTools] 返回 MCP `tools/list` 的元素形状（name / description / inputSchema …），
 * [callTool] 返回 MCP `tools/call` 的 result 形状（content / isError）。
 */
interface McpToolProvider {
    fun listTools(): List<JsonObject>

    suspend fun callTool(name: String, args: JsonObject): JsonObject
}
