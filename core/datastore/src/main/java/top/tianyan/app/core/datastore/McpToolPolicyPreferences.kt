package top.tianyan.app.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import top.tianyan.app.core.model.McpToolPolicy

typealias McpToolPolicyMap = Map<String, Map<String, McpToolPolicy>>

/**
 * 按工具的策略存储（启用状态 / 风险覆盖）。
 *
 * 放在 DataStore 而不是服务配置里：这是本地用户策略，不属于可导出的 MCP 服务定义；
 * 也避免为一张小表引入 Room 迁移。写入以整体 JSON 快照读改写，服务规模在移动端可控。
 */
class McpToolPolicyPreferences(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.settingsDataStore)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val policies: Flow<McpToolPolicyMap> = dataStore.data.map { decode(it[POLICIES]) }

    suspend fun snapshot(): McpToolPolicyMap = policies.first()

    suspend fun policyFor(serverId: String, toolName: String): McpToolPolicy =
        snapshot()[serverId]?.get(toolName) ?: McpToolPolicy()

    suspend fun setEnabled(serverId: String, toolName: String, enabled: Boolean) =
        update(serverId, toolName) { it.copy(enabled = enabled) }

    suspend fun setRiskOverride(serverId: String, toolName: String, risk: String?) =
        update(serverId, toolName) { it.copy(riskOverride = McpToolPolicy.normalizeRisk(risk)) }

    private suspend fun update(
        serverId: String,
        toolName: String,
        transform: (McpToolPolicy) -> McpToolPolicy,
    ) {
        dataStore.edit { prefs ->
            val current = decode(prefs[POLICIES])
            val server = current[serverId].orEmpty()
            val next = server + (toolName to transform(server[toolName] ?: McpToolPolicy()))
            prefs[POLICIES] = json.encodeToString(current + (serverId to next))
        }
    }

    private fun decode(raw: String?): McpToolPolicyMap =
        raw?.let { runCatching { json.decodeFromString<McpToolPolicyMap>(it) }.getOrNull() } ?: emptyMap()

    private companion object {
        val POLICIES = stringPreferencesKey("mcp_tool_policies_json")
    }
}
