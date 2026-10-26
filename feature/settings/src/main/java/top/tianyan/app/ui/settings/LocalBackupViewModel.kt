package top.tianyan.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.tianyan.app.core.tools.backup.LocalBackupService
import top.tianyan.app.core.tools.backup.PreparedBackup
import java.io.File
import kotlinx.coroutines.CoroutineScope
import top.tianyan.app.feature.settings.R

data class LocalBackupUiState(
    val busy: Boolean = false,
    val prepared: PreparedBackup? = null,
    val exportFile: File? = null,
    val error: Boolean = false,
    val errorMessage: Int = R.string.local_backup_error,
    val completed: Boolean = false,
)

class LocalBackupViewModel(private val context: Context, private val service: LocalBackupService) : ViewModel() {
    private val mutableState = MutableStateFlow(LocalBackupUiState())
    val state = mutableState.asStateFlow()

    fun export() = perform {
        val file = service.export()
        mutableState.value = LocalBackupUiState(exportFile = file)
    }

    fun preview(uri: Uri) = perform {
        service.discard(mutableState.value.prepared)
        val prepared = context.contentResolver.openInputStream(uri)?.use { service.preview(it) }
            ?: error("Backup source unavailable")
        mutableState.value = LocalBackupUiState(prepared = prepared)
    }

    fun restore(preferences: Boolean) {
        val prepared = mutableState.value.prepared ?: return
        perform {
            service.restore(prepared, preferences)
            mutableState.value = LocalBackupUiState(completed = true)
        }
    }

    fun saveExport(uri: Uri?) {
        val file = mutableState.value.exportFile ?: return
        perform {
            try {
                if (uri != null) {
                    val output = context.contentResolver.openOutputStream(uri) ?: error("Backup destination unavailable")
                    output.use { destination -> file.inputStream().use { it.copyTo(destination) } }
                }
                mutableState.value = LocalBackupUiState(completed = uri != null)
            } finally { file.delete() }
        }
    }

    fun clear() {
        if (mutableState.value.busy) return
        val old = mutableState.value
        mutableState.value = LocalBackupUiState()
        viewModelScope.launch(Dispatchers.IO) { service.discard(old.prepared); old.exportFile?.delete() }
    }

    private fun perform(action: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true, error = false, completed = false)
        val old = mutableState.value
        viewModelScope.launch(Dispatchers.IO) {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                service.discard(old.prepared)
                old.exportFile?.delete()
                mutableState.value = LocalBackupUiState(error = true, errorMessage = backupErrorMessage(failure))
            }
            finally { mutableState.value = mutableState.value.copy(busy = false) }
        }
    }

    override fun onCleared() {
        val old = mutableState.value
        // Work already in the service's commit section finishes without cancellation; never delete its source here.
        if (!old.busy) CoroutineScope(Dispatchers.IO).launch { service.discard(old.prepared); old.exportFile?.delete() }
        super.onCleared()
    }
}
