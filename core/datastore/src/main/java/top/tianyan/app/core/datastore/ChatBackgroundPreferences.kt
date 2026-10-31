package top.tianyan.app.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.map
import top.tianyan.app.core.model.ChatBackgroundStyle

/** Appearance preference facade shared by the settings preview and the chat page. */
class ChatBackgroundPreferences(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.settingsDataStore)

    /** 输入区毛玻璃（haze 模糊）开关；与背景图模糊 [setBlur] 是两回事。 */
    val inputBlurEnabled = dataStore.data.map { it[INPUT_BLUR] ?: false }

    val style = dataStore.data.map { preferences ->
        ChatBackgroundStyle(preferences[IMAGE], preferences[VISIBILITY] ?: 0.65f,
            preferences[BLUR] ?: 0f).normalized()
    }

    suspend fun setInputBlurEnabled(value: Boolean) {
        dataStore.edit { it[INPUT_BLUR] = value }
    }

    suspend fun setImage(name: String?) {
        require(name == null || ChatBackgroundStyle.isValidImageName(name))
        dataStore.edit { if (name == null) it.remove(IMAGE) else it[IMAGE] = name }
    }

    suspend fun setVisibility(value: Float) {
        dataStore.edit { it[VISIBILITY] = ChatBackgroundStyle(visibility = value).normalized().visibility }
    }

    suspend fun setBlur(value: Float) {
        dataStore.edit { it[BLUR] = ChatBackgroundStyle(blurDp = value).normalized().blurDp }
    }

    private companion object {
        val IMAGE = stringPreferencesKey("chat_background_image")
        val VISIBILITY = floatPreferencesKey("chat_background_visibility")
        val BLUR = floatPreferencesKey("chat_background_blur_dp")
        val INPUT_BLUR = booleanPreferencesKey("chat_input_blur_enabled")
    }
}
