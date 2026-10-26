package top.tianyan.app.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.tianyan.app.core.datastore.ChatBackgroundImageStore
import top.tianyan.app.core.datastore.ChatBackgroundPreferences
import top.tianyan.app.core.model.ChatBackgroundStyle

class ChatBackgroundSettingsViewModel(
    private val preferences: ChatBackgroundPreferences,
    private val images: ChatBackgroundImageStore,
) : ViewModel() {
    val style = preferences.style.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatBackgroundStyle())
    private val mutation = Mutex()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _failed = MutableStateFlow(false)
    val failed = _failed.asStateFlow()

    fun chooseImage(uri: Uri) = mutate {
        val previous = preferences.style.first().imageName
        val name = images.importImage(uri)
        var committed = false
        try {
            preferences.setImage(name)
            committed = true
            images.deleteImage(previous)
        } finally {
            if (!committed) withContext(NonCancellable) { images.deleteImage(name) }
        }
    }

    fun removeImage() = mutate {
        val previous = preferences.style.first().imageName
        preferences.setImage(null)
        images.deleteImage(previous)
    }

    fun setVisibility(value: Float) = mutate { preferences.setVisibility(value) }
    fun setBlur(value: Float) = mutate { preferences.setBlur(value) }

    private fun mutate(action: suspend () -> Unit) = viewModelScope.launch {
        mutation.withLock {
            _busy.value = true
            _failed.value = false
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _failed.value = true }
            finally { _busy.value = false }
        }
    }
}
