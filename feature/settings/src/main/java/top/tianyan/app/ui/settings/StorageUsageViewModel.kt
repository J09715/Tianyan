package top.tianyan.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.tianyan.app.runtime.StorageCategory
import top.tianyan.app.runtime.StorageEntry
import top.tianyan.app.runtime.StorageRiskLevel
import top.tianyan.app.runtime.StorageManager
import top.tianyan.app.runtime.StorageUsage
import android.util.Log

enum class StorageFilter(val label: String) {
    ALL("全部"),
    SAFE("归档日志"),
    CAUTION("谨慎清理"),
    SYSTEM_AND_SOURCE("保留与管理"),
}

sealed interface CleanupDialogTarget {
    data object QuickSafe : CleanupDialogTarget
    data object AllProjectBuilds : CleanupDialogTarget
    data class CategoryTarget(val category: StorageCategory) : CleanupDialogTarget
    data class EntryTarget(val categoryId: String, val entry: StorageEntry) : CleanupDialogTarget
}

data class StorageUsageUiState(
    val usage: StorageUsage? = null,
    val refreshing: Boolean = false,
    val cleaningAction: String? = null,
    val message: String? = null,
    val messageIsError: Boolean = false,
    val activeFilter: StorageFilter = StorageFilter.ALL,
    val dialogTarget: CleanupDialogTarget? = null,
) {
    val filteredCategories: List<StorageCategory>
        get() {
            val categories = usage?.categories.orEmpty()
            return categories.filter { category ->
                when (activeFilter) {
                    StorageFilter.ALL -> true
                    StorageFilter.SAFE -> category.entries.any { it.riskLevel == StorageRiskLevel.SAFE && it.cleanable }
                    StorageFilter.CAUTION -> category.entries.any { it.riskLevel == StorageRiskLevel.CAUTION && it.cleanable }
                    StorageFilter.SYSTEM_AND_SOURCE -> category.entries.any { !it.cleanable }
                }
            }
        }
}

class StorageUsageViewModel(
    private val storageManager: StorageManager,
) : ViewModel() {
    private val _uiState = MutableStateFlow(StorageUsageUiState())
    val uiState: StateFlow<StorageUsageUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun setFilter(filter: StorageFilter) {
        _uiState.update { it.copy(activeFilter = filter) }
    }

    fun openDialog(target: CleanupDialogTarget) {
        _uiState.update { it.copy(dialogTarget = target) }
    }

    fun dismissDialog() {
        _uiState.update { it.copy(dialogTarget = null) }
    }

    fun refresh() {
        if (_uiState.value.refreshing || _uiState.value.cleaningAction != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(refreshing = true) }
            runCatching { storageManager.inspect() }
                .onSuccess { usage ->
                    _uiState.update { it.copy(usage = usage, refreshing = false) }
                }
                .onFailure {
                    if (it is CancellationException) throw it
                    Log.e("StorageUsage", "Inspect storage failed: ${it.message}", it)
                    _uiState.update {
                        it.copy(
                            refreshing = false,
                            messageIsError = true,
                            message = "读取存储占用失败，请点击刷新重试",
                        )
                    }
                }
        }
    }

    /**
     * 清理扫描计划中的过期归档日志
     */
    fun quickSafeClean() {
        if (_uiState.value.cleaningAction != null || _uiState.value.refreshing) return
        viewModelScope.launch {
            _uiState.update { it.copy(cleaningAction = "quick_safe", dialogTarget = null) }
            val result = storageManager.quickSafeClean()
            val isError = result.isFailure
            val msg = if (result.isSuccess) {
                val released = result.getOrNull() ?: 0L
                if (released > 0) {
                    "归档日志清理完成，已删除 ${released.formatSize()}"
                } else {
                    "暂无符合条件的过期归档日志"
                }
            } else {
                Log.e("StorageUsage", "Quick safe clean failed: ${result.errorOrNull()?.message}")
                result.errorOrNull()?.message ?: "清理失败，请刷新后重试"
            }
            val newUsage = runCatching { storageManager.inspect() }.onFailure { if (it is CancellationException) throw it }.getOrNull() ?: _uiState.value.usage
            _uiState.update {
                it.copy(
                    cleaningAction = null,
                    message = msg,
                    messageIsError = isError,
                    usage = newUsage,
                )
            }
        }
    }

    /**
     * 清理已识别项目的旧缓存，不根据通用目录名删除构建产物
     */
    fun cleanProjectBuilds(projectName: String? = null) {
        if (_uiState.value.cleaningAction != null || _uiState.value.refreshing) return
        val actionKey = if (projectName != null) "project_build_$projectName" else "all_project_builds"
        viewModelScope.launch {
            _uiState.update { it.copy(cleaningAction = actionKey, dialogTarget = null) }
            val result = storageManager.cleanProjectBuildArtifacts(projectName)
            val isError = result.isFailure
            val msg = if (result.isSuccess) {
                val released = result.getOrNull() ?: 0L
                if (projectName != null) {
                    "已清理项目 $projectName 的缓存，删除 ${released.formatSize()}"
                } else {
                    "已清理已识别项目的缓存，删除 ${released.formatSize()}"
                }
            } else {
                Log.e("StorageUsage", "Clean project build failed: ${result.errorOrNull()?.message}")
                result.errorOrNull()?.message ?: "清理项目缓存失败，请刷新后重试"
            }
            val newUsage = runCatching { storageManager.inspect() }.onFailure { if (it is CancellationException) throw it }.getOrNull() ?: _uiState.value.usage
            _uiState.update {
                it.copy(
                    cleaningAction = null,
                    message = msg,
                    messageIsError = isError,
                    usage = newUsage,
                )
            }
        }
    }

    /**
     * 按大类执行清理
     */
    fun clearCategory(categoryId: String) {
        if (_uiState.value.cleaningAction != null || _uiState.value.refreshing) return
        viewModelScope.launch {
            _uiState.update { it.copy(cleaningAction = "category_$categoryId", dialogTarget = null) }
            val result = storageManager.clearCategory(categoryId)
            val isError = result.isFailure
            val msg = if (result.isSuccess) {
                "分类清理完成"
            } else {
                Log.e("StorageUsage", "Clear category failed: ${result.errorOrNull()?.message}")
                result.errorOrNull()?.message ?: "分类清理失败，请刷新后重试"
            }
            val newUsage = runCatching { storageManager.inspect() }.onFailure { if (it is CancellationException) throw it }.getOrNull() ?: _uiState.value.usage
            _uiState.update {
                it.copy(
                    cleaningAction = null,
                    message = msg,
                    messageIsError = isError,
                    usage = newUsage,
                )
            }
        }
    }

    /**
     * 按细项执行清理
     */
    fun clearEntry(categoryId: String, entryId: String, entryName: String) {
        if (_uiState.value.cleaningAction != null || _uiState.value.refreshing) return
        viewModelScope.launch {
            _uiState.update { it.copy(cleaningAction = "entry_$entryId", dialogTarget = null) }
            val result = storageManager.clearEntry(categoryId, entryId)
            val isError = result.isFailure
            val msg = if (result.isSuccess) {
                "已清理 $entryName"
            } else {
                Log.e("StorageUsage", "Clear entry failed: ${result.errorOrNull()?.message}")
                result.errorOrNull()?.message ?: "清理 $entryName 失败，请刷新后重试"
            }
            val newUsage = runCatching { storageManager.inspect() }.onFailure { if (it is CancellationException) throw it }.getOrNull() ?: _uiState.value.usage
            _uiState.update {
                it.copy(
                    cleaningAction = null,
                    message = msg,
                    messageIsError = isError,
                    usage = newUsage,
                )
            }
        }
    }

    /**
     * 清理下载缓存
     */
    fun clearCache() {
        if (_uiState.value.cleaningAction != null || _uiState.value.refreshing) return
        viewModelScope.launch {
            _uiState.update { it.copy(cleaningAction = "download_cache", dialogTarget = null) }
            val result = storageManager.clearCache()
            val isError = result.isFailure
            val msg = if (result.isSuccess) {
                "符合条件的依赖缓存已清理"
            } else {
                Log.e("StorageUsage", "Clear cache failed: ${result.errorOrNull()?.message}")
                result.errorOrNull()?.message ?: "清理缓存失败，请刷新后重试"
            }
            val newUsage = runCatching { storageManager.inspect() }.onFailure { if (it is CancellationException) throw it }.getOrNull() ?: _uiState.value.usage
            _uiState.update {
                it.copy(
                    cleaningAction = null,
                    message = msg,
                    messageIsError = isError,
                    usage = newUsage,
                )
            }
        }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null, messageIsError = false) }
    }

    private fun Long.formatSize(): String = when {
        this < 1024L -> "$this B"
        this < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", this / 1024.0)
        this < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", this / (1024.0 * 1024))
        else -> String.format(Locale.US, "%.2f GB", this / (1024.0 * 1024 * 1024))
    }
}
