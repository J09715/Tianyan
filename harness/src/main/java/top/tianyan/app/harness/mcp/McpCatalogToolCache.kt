package top.tianyan.app.harness.mcp

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import top.tianyan.app.core.common.McpCatalogRevisions
import top.tianyan.app.core.model.McpConnectionState
import top.tianyan.app.core.model.McpToolInfo

internal data class CachedMcpTools(
    val fingerprint: String,
    val tools: List<McpToolInfo>,
    val distribution: String,
    val revision: Long,
    val cachedAtMs: Long = System.currentTimeMillis(),
)

/**
 * 工具目录 TTL：stdio 服务靠 notifications/tools/list_changed 立即失效；
 * HTTP 服务没有可用的通知通道（GET SSE 未实现），靠 TTL 兜底，避免整场会话都用旧清单。
 */
internal const val MCP_CATALOG_TTL_MS = 15L * 60L * 1000L

internal fun CachedMcpTools.isFresh(nowMs: Long = System.currentTimeMillis()): Boolean =
    nowMs - cachedAtMs < MCP_CATALOG_TTL_MS

/** use_capability list 的单服务摘要：不启动进程，工具数为缓存命中数。 */
data class EnabledServerSummary(
    val id: String,
    val name: String,
    val cachedToolCount: Int,
    val connected: Boolean,
    val processState: McpConnectionState? = null,
)

/** 升级只撤销同一发行版的旧工具目录，不取消已派发的工具调用。 */
internal fun CoroutineScope.expireCatalogTools(
    cache: ConcurrentHashMap<String, CachedMcpTools>,
    expired: suspend (String) -> Unit,
) = launch {
    McpCatalogRevisions.changes.collect { changes ->
        cache.entries.toList().forEach { (key, value) ->
            val revision = changes[McpCatalogRevisions.Key(value.distribution, key.mcpServerKey())] ?: 0L
            if (value.distribution.isNotBlank() && revision > value.revision && cache.remove(key, value)) expired(key)
        }
    }
}
