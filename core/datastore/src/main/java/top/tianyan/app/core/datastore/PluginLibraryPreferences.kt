package top.tianyan.app.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import top.tianyan.app.core.model.PluginLibraryChannel

/** Plugin distribution preference facade; never stores credentials or installation state. */
class PluginLibraryPreferences(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.settingsDataStore)
    suspend fun channel(): PluginLibraryChannel = dataStore.data.first()[CHANNEL]
        ?.let { value -> PluginLibraryChannel.entries.find { it.name == value } } ?: PluginLibraryChannel.GITHUB
    suspend fun select(channel: PluginLibraryChannel) { dataStore.edit { it[CHANNEL] = channel.name } }
    private companion object { val CHANNEL = stringPreferencesKey("plugin_library_channel") }
}
