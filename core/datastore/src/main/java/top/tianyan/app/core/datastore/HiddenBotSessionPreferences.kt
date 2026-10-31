package top.tianyan.app.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 抽屉里被隐藏的 Bot 会话 id。
 *
 * 只隐藏不删行：既保留历史，也不会被 ensureSession 的自愈重建「复活」到列表里。
 */
private val hiddenBotSessionIdsKey = stringSetPreferencesKey("chat_hidden_bot_session_ids")

val Context.hiddenBotSessionIdsPreference: Flow<Set<String>>
    get() = settingsDataStore.data.map { it[hiddenBotSessionIdsKey] ?: emptySet() }

suspend fun Context.setBotSessionHidden(sessionId: String, hidden: Boolean) {
    settingsDataStore.edit { prefs ->
        val current = prefs[hiddenBotSessionIdsKey] ?: emptySet()
        prefs[hiddenBotSessionIdsKey] = if (hidden) current + sessionId else current - sessionId
    }
}
