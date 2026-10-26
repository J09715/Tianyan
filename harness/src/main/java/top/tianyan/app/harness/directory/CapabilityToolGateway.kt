package top.tianyan.app.harness.directory

import kotlinx.serialization.json.JsonObject
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.harness.mcp.McpManager
import top.tianyan.app.harness.mcp.McpToolCallPolicy

/**
 * 能力统一网关（use_capability 与 MCP 直连的统一入口）：
 * - use_capability：交给 [CapabilityToolRouter] 分发（list/inspect/call/decline/script）。
 *   script 动作在此装配 [ScriptCapabilityDispatcher]，每条内层调用经 [reenter] 重入
 *   ToolExecutor 的完整管道（Schema 校验、审批、检查点、脱敏），并保留嵌套留痕——
 *   脚本是编排层，不是绕过审批的通道。
 * - legacy `mcp__<server>__<tool>` 直调：schema 已不再宣告，但对话历史/模型习惯中
 *   仍可能出现，转发 [McpManager.executeTool]。
 */
class CapabilityToolGateway(
    private val mcpManager: McpManager?,
    /** 嵌套记录脱敏（调用方注入 SecretRedactor；目录层保持纯净）。 */
    private val argRedactor: (String) -> String = { it },
    private val plugins: top.tianyan.app.harness.plugins.HarnessPluginRegistry? = null,
    /** 按工具策略拒绝已禁用的 MCP 工具。 */
    private val toolPolicy: McpToolCallPolicy? = null,
    /** Bot 级能力白名单：非 Bot 会话为 null（不限制）。 */
    private val capabilityScope: top.tianyan.app.harness.bot.BotCapabilityScope? = null,
    /** 宿主执行通道：与直接 host 调用共用同一 host 后端（含输出上限与截图 metadata 附带）。 */
    hostExecutor: suspend (JsonObject, String?, String, MutableMap<String, String>) -> Pair<Boolean, String>,
) {
    private val router = CapabilityToolRouter(mcpManager, argRedactor, plugins, toolPolicy, capabilityScope, hostExecutor)

    suspend fun execute(
        args: JsonObject,
        rawToolName: String?,
        workspace: String,
        parentToolCallId: String,
        operationId: String?,
        sessionId: String,
        metadata: MutableMap<String, String>,
        /** 内层调用重入入口：由 ToolExecutor 注入，保留审批/PLAN/检查点语义。 */
        reenter: suspend (ToolCall) -> ToolResult,
    ): Pair<Boolean, String> {
        if (rawToolName != "use_capability") {
            // 兼容路径：对话历史/模型习惯中仍可能出现直接 mcp__ 调用（schema 已不再宣告）
            toolPolicy?.resolve(args, rawToolName)?.takeIf { !it.enabled }?.let { return false to it.disabledMessage() }
            rawToolName?.removePrefix("mcp__")?.substringBefore("__")?.let { serverId ->
                capabilityScope?.allowedMcpServers(sessionId)?.takeIf { serverId.lowercase() !in it }
                    ?.let { return false to "MCP[$serverId] 不在当前 Bot 的能力白名单内，本次调用未执行。" }
            }
            return mcpManager?.executeTool(rawToolName ?: "mcp", args, workspace) ?: (false to "未初始化 MCP 管理器")
        }
        val dispatch = ScriptCapabilityDispatcher(parentToolCallId, metadata, mcpManager, argRedactor, plugins, reenter)
        return router.execute(args, workspace, parentToolCallId, operationId, sessionId, metadata, dispatch::call)
    }
}
