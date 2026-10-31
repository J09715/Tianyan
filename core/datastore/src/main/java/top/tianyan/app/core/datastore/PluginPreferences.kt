package top.tianyan.app.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first

/** Selection only: never persists active generations, calls, permissions or replayable work. */
class PluginPreferences(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.settingsDataStore)

    suspend fun disabledPlugins(): Set<String> = dataStore.data.first()[DISABLED].orEmpty().toSet()

    suspend fun setEnabled(id: String, enabled: Boolean) {
        require(id.matches(Regex("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9-]*)+")))
        dataStore.edit { values ->
            val disabled = values[DISABLED].orEmpty()
            values[DISABLED] = if (enabled) disabled - id else disabled + id
        }
    }

    private companion object { val DISABLED = stringSetPreferencesKey("native_plugin_disabled_ids") }
}
