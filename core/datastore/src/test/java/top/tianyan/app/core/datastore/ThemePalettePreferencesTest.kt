package top.tianyan.app.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import top.tianyan.app.core.datastore.RequiresAtomicRenameOverwrite
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.tianyan.app.core.model.ThemePalette
import top.tianyan.app.core.model.ThemePaletteStyle

class ThemePalettePreferencesTest {
    @get:Rule val temporary = TemporaryFolder()
    @get:Rule val renameGuard = RequiresAtomicRenameOverwrite()

    @Test fun manualSelectionPersistsAndAtomicallyTurnsOffWallpaperColor() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(temporary.root, "palette.preferences_pb") })
            val preferences = ThemePalettePreferences(store)
            assertEquals(ThemePalette(), preferences.palette.first())
            store.edit { it[booleanPreferencesKey("dynamic_color_enabled")] = true }
            preferences.selectStyle(ThemePaletteStyle.EXPRESSIVE)
            preferences.selectColor("rose")
            assertEquals(ThemePalette("rose", ThemePaletteStyle.EXPRESSIVE), ThemePalettePreferences(store).palette.first())
            assertFalse(store.data.first()[booleanPreferencesKey("dynamic_color_enabled")]!!)
            store.edit { it[booleanPreferencesKey("dynamic_color_enabled")] = true }
            assertEquals("rose", preferences.palette.first().colorId)
        } finally { scope.cancel() }
    }

    @Test fun unknownStoredStyleFallsBackWithoutLosingTheColor() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(temporary.root, "invalid.preferences_pb") })
            store.edit {
                it[stringPreferencesKey("theme_palette_color")] = "jade"
                it[stringPreferencesKey("theme_palette_style")] = "future-style"
            }
            assertEquals(ThemePalette("jade"), ThemePalettePreferences(store).palette.first())
        } finally { scope.cancel() }
    }
}
