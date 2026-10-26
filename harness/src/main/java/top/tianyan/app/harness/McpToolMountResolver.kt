package top.tianyan.app.harness

import kotlinx.coroutines.flow.first
import top.tianyan.app.core.database.McpServerRepository
import top.tianyan.app.core.datastore.McpToolPolicyMap
import top.tianyan.app.core.datastore.McpToolPolicyPreferences
import top.tianyan.app.core.model.McpServerConfig
import top.tianyan.app.core.model.McpToolInfo
import top.tianyan.app.harness.events.CapabilityEventWriter
import top.tianyan.app.harness.mcp.McpManager
import top.tianyan.app.harness.mcp.filterEnabledBy

/**
 * @ 提及 → MCP 工具挂载解析。
 *
 * 挂载集合按会话累计并稳定排序：一次提及后整个会话保持同一批 schema，避免每轮
 * tools 数组抖动击穿前缀缓存；新提及只在当轮改变一次。只读缓存，不启动进程；
 * 尚未发现的服务器仍可走 use_capability 代理，由模型 inspect 后调用。
 */
class McpToolMountResolver(
    private val mcpManager: McpManager,
    private val repository: McpServerRepository,
    private val policies: McpToolPolicyPreferences,
    private val capabilityWriter: CapabilityEventWriter,
    /** Bot 级能力白名单：null = 不限制；设置后仅白名单内服务可被该会话挂载。 */
    private val capabilityScope: top.tianyan.app.harness.bot.BotCapabilityScope? = null,
) {
    suspend fun resolve(sessionId: String, messages: List<HarnessMessage>, model: ModelConfig): ModelConfig {
        val userMessages = messages.filterIsInstance<UserMessage>()
        val latest = userMessages.lastOrNull() ?: return model
        val servers = runCatching { repository.servers.first() }.getOrDefault(emptyList())
        val allowed = capabilityScope?.allowedMcpServers(sessionId)
        val scoped = if (allowed == null) servers else servers.filter {
            it.id.lowercase() in allowed || it.name.lowercase() in allowed
        }
        val knownNames = servers.flatMap { listOf(it.id, it.name) }
        val latestMentions = MentionExtractor.parse(latest.text, knownNames)
        val mentioned = userMessages.flatMap { MentionExtractor.parse(it.text, knownNames) }.toSet()
        val candidates = if (mentioned.isEmpty()) emptyList() else scoped
            .filter { it.isEnabled && (it.id.lowercase() in mentioned || it.name.lowercase() in mentioned) }
        val toolsByServer = candidates.associate { server ->
            server.id to runCatching { mcpManager.cachedToolsOf(server.id) }.getOrDefault(emptyList())
        }
        val mounted = selectMountedTools(candidates, policies.snapshot(), toolsByServer)
        val mountedModel = model.copy(dynamicMcpTools = mounted)
        capabilityWriter.writeIfMentioned(sessionId, latest.id, latestMentions, mountedModel)
        return mountedModel
    }
}

/** 纯选择逻辑：服务器与工具都按名称稳定排序，先按工具策略过滤，再套用挂载上限。 */
internal fun selectMountedTools(
    servers: List<McpServerConfig>,
    policies: McpToolPolicyMap,
    toolsByServer: Map<String, List<McpToolInfo>>,
): List<McpToolInfo> {
    val mounted = mutableListOf<McpToolInfo>()
    loop@ for (server in servers.sortedBy { it.id }.take(MAX_MOUNTED_SERVERS)) {
        val tools = toolsByServer[server.id].orEmpty()
            .filterEnabledBy(policies[server.id])
            .sortedBy { it.name }
            .take(MAX_TOOLS_PER_SERVER)
        for (tool in tools) {
            if (mounted.size >= MAX_TOTAL_TOOLS) break@loop
            if (mounted.none { it.serverId == tool.serverId && it.name == tool.name }) mounted += tool
        }
    }
    return mounted
}

private const val MAX_MOUNTED_SERVERS = 3
private const val MAX_TOOLS_PER_SERVER = 24
private const val MAX_TOTAL_TOOLS = 48
