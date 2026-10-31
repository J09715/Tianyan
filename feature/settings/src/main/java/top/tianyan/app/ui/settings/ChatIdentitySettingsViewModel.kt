package top.tianyan.app.ui.settings

import android.net.Uri
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.tianyan.app.core.datastore.ChatBackgroundImageStore
import top.tianyan.app.core.datastore.ChatIdentityPreferences
import top.tianyan.app.core.model.ChatIdentityStyle

class ChatIdentitySettingsViewModel(private val preferences: ChatIdentityPreferences,
    private val images: ChatBackgroundImageStore) : ViewModel() {
    val style = preferences.style.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatIdentityStyle())
    private val mutation = Mutex()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _failed = MutableStateFlow(false)
    val failed = _failed.asStateFlow()
    private val _cropBitmap = MutableStateFlow<Bitmap?>(null)
    val cropBitmap = _cropBitmap.asStateFlow()
    fun setName(name: String) = mutate { preferences.setName(name) }
    fun chooseImage(uri: Uri) = mutate {
        _cropBitmap.value = images.loadAvatarForCrop(uri)
    }
    fun cancelCrop() { if (!_busy.value) _cropBitmap.value = null }
    fun confirmCrop(crop: AvatarCrop) = mutate {
        val bitmap = _cropBitmap.value ?: return@mutate
        val bounds = crop.bounds(bitmap.width, bitmap.height)
        val old = preferences.style.first().imageName
        withContext(NonCancellable) {
            val name = images.saveAvatarCrop(bitmap, bounds.left, bounds.top, bounds.side)
            try { preferences.setImage(name) }
            catch (failure: Exception) { images.deleteImage(name); throw failure }
            images.deleteImage(old)
            _cropBitmap.value = null
        }
    }
    fun reset() = mutate {
        val old = preferences.style.first().imageName
        withContext(NonCancellable) { preferences.reset(); images.deleteImage(old) }
    }
    private fun mutate(action: suspend () -> Unit) = viewModelScope.launch {
        mutation.withLock {
            _busy.value = true; _failed.value = false
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _failed.value = true }
            finally { _busy.value = false }
        }
    }
}
