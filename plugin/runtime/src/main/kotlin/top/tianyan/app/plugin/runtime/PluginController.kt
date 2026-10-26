package top.tianyan.app.plugin.runtime

import kotlinx.coroutines.flow.StateFlow
import top.tianyan.app.plugin.api.PluginCatalog

enum class PluginPhase { READY, STARTING, RUNNING, DRAINING, STOPPED, BLOCKED, FAILED }
enum class PluginFailure { PREFERENCES, START, RELEASE }

data class PluginStatus(
    val phase: PluginPhase = PluginPhase.READY,
    val selected: Boolean = true,
    val generation: Long = 0,
    val failure: PluginFailure? = null,
)

/** Only user selection is persisted. Execution results, leases and side effects are never replayed. */
interface PluginSelectionStore {
    suspend fun disabledPlugins(): Set<String>
    suspend fun setEnabled(id: String, enabled: Boolean)
}

interface PluginController {
    val catalog: PluginCatalog
    val states: StateFlow<Map<String, PluginStatus>>
    val initialized: StateFlow<Boolean>
    val preferenceFailure: StateFlow<Boolean>
    suspend fun initialize()
    suspend fun setEnabled(id: String, enabled: Boolean)
    fun acquire(id: String): PluginLease?
    suspend fun shutdown(): Boolean
}
