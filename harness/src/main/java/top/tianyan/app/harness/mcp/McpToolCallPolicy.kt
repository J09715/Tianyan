package top.tianyan.app.harness.mcp

import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import top.tianyan.app.core.database.McpServerRepository
import top.tianyan.app.core.datastore.McpToolPolicyPreferences
import top.tianyan.app.core.model.McpToolInfo
import top.tianyan.app.core.model.McpToolPolicy
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolResult

/**
 * 按 (server, tool) 解析 MCP 调用并应用本地工具策略。
 *
 * 只读缓存（不启动进程）；解析不到说明工具尚未发现，调用方保持旧行为，
 * 由执行层在发现后再次校验。
 */
class McpToolCallPolicy(
    private val mcpManager: McpManager,
    private val repository: McpServerRepository,
    private val preferences: McpToolPolicyPreferences,
) {
    data class Resolved(
        val serverId: String,
        val serverName: String,
        val tool: McpToolInfo,
        val policy: McpToolPolicy,
    ) {
        val enabled: Boolean get() = policy.enabled
        val riskOverride: String? get() = policy.riskOverride

        fun disabledMessage(): String =
            "MCP 工具「$serverName · ${tool.name}」已在设置中禁用，本次调用未执行。" +
                "请改用其他能力；如需恢复，让用户在 MCP 服务详情中重新启用该工具。"

        fun disabledResult(call: ToolCall, now: Long): ToolResult = ToolResult(
            id = UUID.randomUUID().toString(),
            createdAt = now,
            toolCallId = call.id,
            success = false,
            errorCode = "TOOL_DISABLED",
            output = "⛔ ${disabledMessage()}",
        )
    }

    suspend fun resolve(args: JsonObject, rawToolName: String?): Resolved? {
        if (rawToolName == "use_capability") {
            if (args.stringArg("action")?.lowercase() != "call") return null
            val serverId = args.stringArg("server") ?: return null
            val toolName = args.stringArg("tool") ?: return null
            val tool = mcpManager.cachedToolsOf(serverId).firstOrNull { it.name == toolName } ?: return null
            return resolved(serverId, tool)
        }
        if (rawToolName == null || !rawToolName.startsWith("mcp__") || rawToolName == "mcp") return null
        val servers = runCatching { repository.servers.first() }.getOrDefault(emptyList())
            .filter { it.isEnabled }
        for (server in servers) {
            val tool = mcpManager.cachedToolsOf(server.id)
                .firstOrNull { McpToolApiName.matches(it, rawToolName) }
                ?: continue
            return resolved(server.id, tool)
        }
        return null
    }

    suspend fun filterEnabled(serverId: String, tools: List<McpToolInfo>): List<McpToolInfo> =
        tools.filterEnabledBy(preferences.snapshot()[serverId])

    suspend fun policyFor(serverId: String, toolName: String): McpToolPolicy =
        preferences.policyFor(serverId, toolName)

    private suspend fun resolved(serverId: String, tool: McpToolInfo): Resolved =
        Resolved(serverId, tool.serverName, tool, preferences.policyFor(serverId, tool.name))
}

private fun JsonObject.stringArg(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

/** 按工具名应用启用策略；null 策略映射视为全部启用。 */
internal fun List<McpToolInfo>.filterEnabledBy(policies: Map<String, McpToolPolicy>?): List<McpToolInfo> =
    filter { policies?.get(it.name)?.enabled != false }
