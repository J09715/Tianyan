package top.tianyan.app.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.map

private val autoReadRepliesKey = booleanPreferencesKey("chat_auto_read_replies")
internal val Context.autoReadRepliesPreference
    get() = settingsDataStore.data.map { it[autoReadRepliesKey] ?: false }

internal suspend fun Context.setAutoReadRepliesPreference(enabled: Boolean) {
    settingsDataStore.edit { it[autoReadRepliesKey] = enabled }
}
