package top.tianyan.app.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.map

/** Tool-call appearance, exposed through the appearance preference facade. */
internal class ToolStatusPreferences(private val store: DataStore<Preferences>) {
    val classicDots = store.data.map { it[CLASSIC_DOTS] ?: false }
    suspend fun setClassicDots(enabled: Boolean) { store.edit { it[CLASSIC_DOTS] = enabled } }

    private companion object {
        val CLASSIC_DOTS = booleanPreferencesKey("chat_tool_classic_status_dots")
    }
}
