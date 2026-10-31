package top.tianyan.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import top.tianyan.app.core.datastore.AppearancePreferences
import top.tianyan.app.core.model.ThemePalette
import top.tianyan.app.core.model.ThemePaletteStyle

class ThemePaletteViewModel(private val preferences: AppearancePreferences) : ViewModel() {
    val palette = preferences.themePalette.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemePalette())
    fun selectColor(id: String) { viewModelScope.launch { preferences.selectThemeColor(id) } }
    fun selectStyle(style: ThemePaletteStyle) { viewModelScope.launch { preferences.setPaletteStyle(style) } }
}
