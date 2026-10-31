package top.tianyan.app.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.map
import top.tianyan.app.core.model.ChatIdentityStyle
import top.tianyan.app.core.model.ChatBackgroundStyle

class ChatIdentityPreferences(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.settingsDataStore)
    val style = dataStore.data.map { ChatIdentityStyle(it[NAME] ?: ChatIdentityStyle.DEFAULT_NAME, it[IMAGE]).normalized() }
    suspend fun setName(name: String) { dataStore.edit { it[NAME] = ChatIdentityStyle(name).normalized().name } }
    suspend fun setImage(name: String?) {
        require(name == null || ChatBackgroundStyle.isValidImageName(name))
        dataStore.edit { if (name == null) it.remove(IMAGE) else it[IMAGE] = name }
    }
    suspend fun reset() { dataStore.edit { it.remove(NAME); it.remove(IMAGE) } }
    private companion object {
        val NAME = stringPreferencesKey("chat_identity_name")
        val IMAGE = stringPreferencesKey("chat_identity_image")
    }
}
