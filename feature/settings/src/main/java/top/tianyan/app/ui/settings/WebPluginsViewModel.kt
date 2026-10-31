package top.tianyan.app.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.tianyan.app.core.model.ToolManifest
import top.tianyan.app.core.tools.LocalPluginPreview
import top.tianyan.app.core.tools.WebPluginRepository

data class WebPluginImportConfirmation(val uri: Uri, val preview: LocalPluginPreview)
data class WebPluginManagementState(
    val busy: Boolean = false,
    val failed: Boolean = false,
    val pending: WebPluginImportConfirmation? = null,
    val bootstrapTooLarge: Boolean = false,
)

class WebPluginsViewModel(val repository: WebPluginRepository) : ViewModel() {
    val packages = repository.packages
    private val mutableState = MutableStateFlow(WebPluginManagementState())
    val state = mutableState.asStateFlow()
    init { refresh() }

    fun refresh() = perform { repository.refresh() }
    fun preview(uri: Uri) = perform {
        val preview = repository.preview(uri)
        mutableState.value = mutableState.value.copy(pending = WebPluginImportConfirmation(uri, preview))
    }
    fun cancelPreview() { if (!mutableState.value.busy) mutableState.value = WebPluginManagementState() }
    fun confirmImport(confirmed: WebPluginImportConfirmation) {
        if (mutableState.value.pending !== confirmed) return
        perform {
            repository.import(confirmed.uri, confirmed.preview.manifest)
            mutableState.value = WebPluginManagementState(busy = true)
        }
    }
    fun remove(expected: ToolManifest) = perform { repository.remove(expected) }

    private fun perform(action: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true, failed = false, bootstrapTooLarge = false)
        viewModelScope.launch(Dispatchers.IO) {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: top.tianyan.app.runtime.service.ProotWebBootstrapLimitException) {
                mutableState.value = WebPluginManagementState(busy = true, failed = true, bootstrapTooLarge = true)
            }
            catch (_: Exception) { mutableState.value = WebPluginManagementState(busy = true, failed = true) }
            finally { mutableState.value = mutableState.value.copy(busy = false) }
        }
    }
}
