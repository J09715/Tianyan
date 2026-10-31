package top.tianyan.app.harness.mcp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import top.tianyan.app.core.model.McpServerConfig
import top.tianyan.app.core.datastore.RemoteServerRepository
import top.tianyan.app.runtime.environment.ExecutionEnvironmentContext

/** Settings checks have no session owner: scope them to the selected profile instead. */
class StandaloneMcpScope(private val servers: RemoteServerRepository, private val localDistribution: (() -> String)? = null,
    private val localSelections: Flow<String>? = null) {
    val changes: Flow<String?> = combine(servers.activeId, servers.servers, localSelections?.onStart { emit("") } ?: flowOf("")) { _, _, _ ->
        runCatching { key() }.getOrDefault("unavailable")
    }.distinctUntilChanged()
    fun key(): String? {
        val id = servers.activeId.value ?: return localDistribution?.invoke()?.let { "local:$it" }
        val profile = servers.servers.value.singleOrNull { it.id == id } ?: error("当前连接档案不可用")
        return top.tianyan.app.runtime.remote.remoteConnectionScope(profile)
    }
    suspend fun captureKey(): String? {
        if (servers.activeId.value != null) return key()
        localSelections?.first()
        return localDistribution?.invoke()?.let { "local:$it" }
    }
}

internal suspend fun scopedMcpKey(server: McpServerConfig, standalone: StandaloneMcpScope?): String {
    currentCoroutineContext()[McpConnectionScope]?.scopeKey?.takeIf { it.mcpServerKey() == server.id }?.let { return it }
    if (currentCoroutineContext()[ExecutionEnvironmentContext] != null) return server.forExecutionEnvironment().id
    return standalone?.captureKey()?.let { "${server.id}@environment:$it" } ?: server.id
}

/** Freeze the scope before any suspend point in a settings probe/discovery. */
internal class McpConnectionScope(val scopeKey: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<McpConnectionScope>
}
