package top.tianyan.app.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.map
import top.tianyan.app.core.model.ThemePalette
import top.tianyan.app.core.model.ThemePaletteStyle

/** Storage implementation exposed to UI only through AppearancePreferences. */
internal class ThemePalettePreferences(private val store: DataStore<Preferences>) {
    val palette = store.data.map {
        ThemePalette(it[COLOR] ?: "default", runCatching {
            ThemePaletteStyle.valueOf(it[STYLE].orEmpty())
        }.getOrDefault(ThemePaletteStyle.TONAL_SPOT))
    }

    suspend fun selectColor(id: String) = store.edit {
        it[COLOR] = id
        it[DYNAMIC] = false
    }.let { }

    suspend fun selectStyle(style: ThemePaletteStyle) = store.edit { it[STYLE] = style.name }.let { }

    private companion object {
        val COLOR = stringPreferencesKey("theme_palette_color")
        val STYLE = stringPreferencesKey("theme_palette_style")
        val DYNAMIC = androidx.datastore.preferences.core.booleanPreferencesKey("dynamic_color_enabled")
    }
}
