package top.tianyan.app.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.map
import top.tianyan.app.core.model.McpIdlePolicy

/** Client-side process policy, separate from the externally accessible MCP server preferences. */
class McpClientPreferences(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.settingsDataStore)

    val idleTimeoutMinutes = dataStore.data.map {
        McpIdlePolicy.normalize(it[IDLE_MINUTES] ?: McpIdlePolicy.DEFAULT_MINUTES)
    }

    suspend fun setIdleTimeoutMinutes(minutes: Int) {
        dataStore.edit { it[IDLE_MINUTES] = McpIdlePolicy.normalize(minutes) }
    }

    private companion object {
        val IDLE_MINUTES = intPreferencesKey("mcp_client_stdio_idle_minutes")
    }
}
