package top.tianyan.app.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.tianyan.app.core.common.result.AppError
import top.tianyan.app.core.common.result.AppResult
import top.tianyan.app.core.common.result.ErrorCode
import top.tianyan.app.runtime.BackupExportResult
import top.tianyan.app.runtime.BackupManager

data class BackupUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val lastExport: BackupExportResult? = null,
    val localBackupName: String? = null,
    val localBackupSize: Long = 0L,
    val localBackupTime: Long = 0L,
    val pendingSections: List<String> = emptyList(),
    val pendingAt: Long = 0L,
)

/**
 * 数据备份页状态机。
 *
 * 导出：用户先选目标 URI，本地自留副本同时落盘（两份同名文件），
 * 所以本地副本用于「上次备份有多大/什么时候」，共享存储那份用于取走。
 *
 * 恢复：只暂存，不改运行中的数据；真正落盘发生在下次启动。
 */
@HiltViewModel
class BackupSettingsViewModel @Inject constructor(
    private val application: Application,
    private val backupManager: BackupManager,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    init {
        refreshLocalBackup()
        refreshPending()
    }

    fun refreshLocalBackup() {
        viewModelScope.launch {
            val latest = backupManager.latestLocalBackup()
            _state.value = _state.value.copy(
                localBackupName = latest?.name,
                localBackupSize = latest?.length() ?: 0L,
                localBackupTime = latest?.lastModified() ?: 0L,
            )
        }
    }

    fun refreshPending() {
        val pending = backupManager.pendingRestoreInfo()
        _state.value = _state.value.copy(
            pendingSections = pending?.sections.orEmpty(),
            pendingAt = pending?.stagedAt ?: 0L,
        )
    }

    /** 备份包默认名：可排序的时间戳，用户在系统保存对话框里能直接看懂。 */
    fun suggestedArchiveName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(System.currentTimeMillis()))
        return "tianyan-backup-$stamp.zip"
    }

    fun export(target: Uri) {
        val context = application.applicationContext
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val archiveName = suggestedArchiveName()
            val result = withContext(Dispatchers.IO) {
                val stream = context.contentResolver.openOutputStream(target, "w")
                    ?: return@withContext AppResult.Failure(
                        AppError(ErrorCode.IO, "无法写入所选位置"),
                    )
                // BackupManager 负责冲刷但也会在失败时关闭流，故不再 use 包一层。
                backupManager.export(stream, archiveName)
            }
            when (result) {
                is AppResult.Success -> {
                    _state.value = _state.value.copy(
                        busy = false,
                        lastExport = result.data,
                        message = "备份完成：${result.data.archiveName}（${formatSize(result.data.totalBytes)}）",
                    )
                    refreshLocalBackup()
                }

                is AppResult.Failure -> _state.value =
                    _state.value.copy(busy = false, message = "备份失败：${result.error.message}")
            }
        }
    }

    fun restore(source: Uri) {
        val context = application.applicationContext
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val result = withContext(Dispatchers.IO) {
                val stream: InputStream = context.contentResolver.openInputStream(source)
                    ?: return@withContext AppResult.Failure(
                        AppError(ErrorCode.IO, "无法读取所选备份包"),
                    )
                stream.use { backupManager.stageRestore(it) }
            }
            when (result) {
                is AppResult.Success -> {
                    _state.value = _state.value.copy(
                        busy = false,
                        message = "备份包已就绪（${formatSize(result.data.totalBytes)}），重启天衍后生效",
                    )
                    refreshPending()
                }

                is AppResult.Failure -> _state.value =
                    _state.value.copy(busy = false, message = "恢复失败：${result.error.message}")
            }
        }
    }

    fun discardPending() {
        backupManager.discardPendingRestore()
        _state.value = _state.value.copy(message = "已取消待恢复备份")
        refreshPending()
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun sectionLabel(section: String): String = when (section) {
        BackupManager.SECTION_DB -> "应用数据库"
        BackupManager.SECTION_PREFS -> "偏好设置"
        BackupManager.SECTION_BLOBS -> "会话大载荷"
        BackupManager.SECTION_FILES -> "附件与工作区"
        else -> section
    }

    fun formatSize(bytes: Long): String = when {
        bytes <= 0L -> "0 B"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    fun formatTime(millis: Long): String = when {
        millis <= 0L -> "从未备份"
        else -> SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
    }
}