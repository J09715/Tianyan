package top.tianyan.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import top.tianyan.app.core.datastore.McpToolPolicyPreferences
import top.tianyan.app.core.model.McpToolInfo
import top.tianyan.app.core.model.McpToolPolicy
import top.tianyan.app.harness.mcp.McpManager

/** MCP 详情页的按工具策略状态：缓存清单 + 持久化开关/风险覆盖。 */
class McpToolPolicyViewModel(
    private val mcpManager: McpManager,
    private val preferences: McpToolPolicyPreferences,
) : ViewModel() {
    val policies: StateFlow<Map<String, Map<String, McpToolPolicy>>> =
        preferences.policies.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _cachedTools = MutableStateFlow<Map<String, List<McpToolInfo>>>(emptyMap())
    val cachedTools: StateFlow<Map<String, List<McpToolInfo>>> = _cachedTools.asStateFlow()

    /** 详情页加载过的服务：按当前策略实时重算可用工具数。 */
    val enabledCounts: StateFlow<Map<String, Int>> = combine(_cachedTools, policies) { toolsByServer, policyMap ->
        toolsByServer.mapValues { (serverId, tools) ->
            tools.count { policyMap[serverId]?.get(it.name)?.enabled != false }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    suspend fun loadTools(serverId: String): List<McpToolInfo> =
        mcpManager.cachedToolsOf(serverId).also { _cachedTools.value = _cachedTools.value + (serverId to it) }

    fun setEnabled(serverId: String, toolName: String, enabled: Boolean) {
        viewModelScope.launch { preferences.setEnabled(serverId, toolName, enabled) }
    }

    fun setRiskOverride(serverId: String, toolName: String, risk: String?) {
        viewModelScope.launch { preferences.setRiskOverride(serverId, toolName, risk) }
    }
}
