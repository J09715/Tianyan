package top.tianyan.app.harness.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import top.tianyan.app.core.model.McpConnectionState

/** Settings show the selected environment's status, never another chat owner's connection. */
internal class McpScopedConnectionStates(private val standalone: StandaloneMcpScope?, scope: CoroutineScope,
    processStates: StateFlow<Map<String, McpConnectionState>> = MutableStateFlow(emptyMap())) {
    private val all = MutableStateFlow<Map<String, McpConnectionState>>(emptyMap())
    val visible = combine(all, processStates, standalone?.changes ?: flowOf(null)) { states, processes, selected ->
        val suffix = selected?.let { "@environment:$it" }.orEmpty()
        states.filterKeys { it == it.mcpServerKey() + suffix }.mapValues { (key, state) ->
            val process = processes[key]
            when {
                state == McpConnectionState.CHECKING -> state
                process == McpConnectionState.OFFLINE -> process
                state == McpConnectionState.ONLINE && process in setOf(McpConnectionState.SLEEPING, McpConnectionState.WAKING) -> process!!
                else -> state
            }
        }.mapKeys { it.key.mcpServerKey() }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    fun update(state: McpConnectionState, key: String) { all.update { it + (key to state) } }
    fun forget(serverId: String) { all.update { states -> states.filterKeys { it.mcpServerKey() != serverId } } }
    fun forgetKey(key: String) { all.update { it - key } }
}
