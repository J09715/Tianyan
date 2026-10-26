package top.tianyan.app.ui.terminal

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.tianyan.app.core.datastore.TerminalPreferences
import top.tianyan.app.runtime.DistributionCatalog
import top.tianyan.app.runtime.WorkspaceManager
import top.tianyan.app.runtime.terminal.TerminalSessionClientRouter
import top.tianyan.app.runtime.terminal.TerminalSessionHandle
import top.tianyan.app.runtime.terminal.TerminalSessionManager
import android.content.Context
import top.tianyan.app.feature.terminal.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import top.tianyan.app.core.datastore.FirstUseGuidePreferences
import top.tianyan.app.core.datastore.RemoteServerRepository
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.WorkspaceProject
import top.tianyan.app.runtime.SessionWorkspaceCatalog

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalViewModel(
    private val context: Context,
    private val terminalManager: TerminalSessionManager,
    val sessionClientRouter: TerminalSessionClientRouter,
    private val workspaceManager: WorkspaceManager,
    private val settingsDataStore: TerminalPreferences,
    private val firstUseGuidePreferences: FirstUseGuidePreferences,
    private val linuxRuntime: LinuxRuntime,
    private val remoteServers: RemoteServerRepository,
    private val workspaceCatalog: SessionWorkspaceCatalog,
) : ViewModel() {
    private var initialized = false

    val firstUseGuidesShown: StateFlow<Set<String>> = firstUseGuidePreferences.firstUseGuidesShown
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun markFirstUseGuideShown(id: String) {
        viewModelScope.launch { firstUseGuidePreferences.markFirstUseGuideShown(id) }
    }

    val installedDistros = linuxRuntime.installedDistros
    val activeDistroId = linuxRuntime.activeDistroId

    /** 当前是否选中远程服务器：远程时终端目标由服务器决定，不再暴露本地发行版选择。 */
    val remoteActive: StateFlow<Boolean> = remoteServers.activeId
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, remoteServers.activeId.value != null)

    val handles: StateFlow<List<TerminalSessionHandle>> = terminalManager.handles
    val activeId: StateFlow<String?> = terminalManager.activeId
    val restoreFailures = terminalManager.restoreFailures
    fun retryFailedRestorations() {
        viewModelScope.launch {
            runCatching { terminalManager.retryFailedRestorations() }
                .onSuccess { _error.value = null }.onFailure { _error.value = it.message ?: "终端恢复失败" }
        }
    }
    fun removeFailedRestoration(id: String) {
        viewModelScope.launch {
            runCatching { terminalManager.removeFailedRestoration(id) }
                .onSuccess { if (handles.value.isNotEmpty()) _error.value = null }
                .onFailure { _error.value = it.message ?: "无法移除终端记录" }
        }
    }

    private val activeHandle: Flow<TerminalSessionHandle?> = terminalManager.activeHandle

    val distributionName: StateFlow<String> = activeHandle
        .flatMapLatest { handle ->
            val label = handle?.environmentLabel
            if (label != null) flowOf(label) else {
                val distroId = handle?.distributionId ?: linuxRuntime.activeDistroId.value
                flowOf(DistributionCatalog.require(distroId).displayName)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, context.getString(R.string.terminal_loading))

    val terminalFontSize: StateFlow<Int> = settingsDataStore.terminalFontSize
        .stateIn(viewModelScope, SharingStarted.Eagerly, 13)

    val terminalColorScheme: StateFlow<String> = settingsDataStore.terminalColorScheme
        .stateIn(viewModelScope, SharingStarted.Eagerly, "obsidian")

    val terminalHapticsEnabled: StateFlow<Boolean> = settingsDataStore.terminalHapticsEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun initialize(project: String) {
        initializeInternal(project, force = false)
    }

    fun retryInitialize(project: String) {
        initializeInternal(project, force = true)
    }

    private fun initializeInternal(project: String, force: Boolean) {
        if (initialized && !force) return
        initialized = true
        _error.value = null
        viewModelScope.launch {
            if (project.isNotBlank()) {
                val workingDirectory = if (remoteServers.activeId.value != null) "/workspace/$project"
                    else runCatching { workspaceManager.linuxWorkingDirectory(project) }.getOrNull() ?: "/root"
                runCatching {
                    terminalManager.openOrSwitchToProject(project, workingDirectory,
                        remoteServers.activeId.value?.let { "ssh:$it" })
                }.onFailure { _error.value = it.message ?: context.getString(R.string.terminal_error_workspace_start) }
            } else {
                runCatching {
                    terminalManager.ensureActive("/root")
                }.onFailure { _error.value = it.message ?: context.getString(R.string.terminal_error_start) }
            }
        }
    }

    private fun activeIdOrNull(): String? = terminalManager.activeId.value?.takeIf { id ->
        terminalManager.handles.value.any { it.id == id }
    }

    fun sendText(text: String) {
        if (text.isEmpty()) return
        activeIdOrNull()?.let { terminalManager.write(it, text.toByteArray(Charsets.UTF_8)) }
    }

    fun pasteText(text: String) {
        if (text.isEmpty()) return
        activeIdOrNull()?.let { terminalManager.paste(it, text) }
    }

    fun setTerminalFontSize(sizeSp: Int) {
        viewModelScope.launch { settingsDataStore.setTerminalFontSize(sizeSp) }
    }

    fun interrupt() {
        activeIdOrNull()?.let { terminalManager.interrupt(it) }
    }

    fun onTerminalKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val ctrl = event.isCtrlPressed
        val alt = event.isAltPressed
        val codePoint = event.utf16CodePoint
        val bytes: ByteArray? = when {
            ctrl && codePoint in 'a'.code..'z'.code -> byteArrayOf((codePoint - 96).toByte())
            ctrl && event.key == Key.Spacebar -> byteArrayOf(0)
            event.key == Key.DirectionUp -> seq(if (alt) "\u001B\u001B[A" else "\u001B[A]")
            event.key == Key.DirectionDown -> seq(if (alt) "\u001B\u001B[B" else "\u001B[B]")
            event.key == Key.DirectionLeft -> seq(if (alt) "\u001B\u001B[D" else "\u001B[D]")
            event.key == Key.DirectionRight -> seq(if (alt) "\u001B\u001B[C" else "\u001B[C]")
            event.key == Key.MoveHome -> seq("\u001B[1~")
            event.key == Key.MoveEnd -> seq("\u001B[4~")
            event.key == Key.PageUp -> seq("\u001B[5~")
            event.key == Key.PageDown -> seq("\u001B[6~")
            event.key == Key.Delete -> seq("\u001B[3~")
            event.key == Key.Insert -> seq("\u001B[2~")
            event.key == Key.Tab -> byteArrayOf(9)
            event.key == Key.Backspace -> byteArrayOf(0x7f)
            event.key == Key.Enter -> byteArrayOf(13)
            event.key == Key.Escape -> byteArrayOf(27)
            codePoint in 0x20..0x7e || codePoint > 0x7f ->
                ((if (alt) "\u001B" else "") + codePoint.toChar()).toByteArray(Charsets.UTF_8)
            else -> null
        }
        if (bytes != null) {
            activeIdOrNull()?.let { terminalManager.write(it, bytes) }
            return true
        }
        return false
    }

    private fun seq(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)

    val workspaces: StateFlow<List<WorkspaceProject>> = workspaceCatalog.observe().map { it.projects }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun createSession(label: String = "", workingDirectory: String = "/root", distroId: String? = null) {
        viewModelScope.launch {
            _error.value = null
            runCatching {
                if (remoteActive.value) {
                    // 远程目标由服务器决定：传 null 跟随当前选中环境，禁用本地发行版语义。
                    val finalLabel = label.trim().ifBlank { "会话 ${handles.value.size + 1}" }
                    terminalManager.createSession(
                        label = finalLabel,
                        workingDirectory = workingDirectory.trim().ifBlank { "/root" },
                        distributionId = null,
                    )
                } else {
                    val targetDistro = distroId?.takeIf { it.isNotBlank() }
                    val distroShortName = targetDistro?.let { DistributionCatalog.require(it).id.replaceFirstChar { it.uppercase() } } ?: "会话"
                    val finalLabel = label.trim().ifBlank { "$distroShortName ${handles.value.size + 1}" }
                    terminalManager.createSession(
                        label = finalLabel,
                        workingDirectory = workingDirectory.trim().ifBlank { "/root" },
                        distributionId = targetDistro,
                    )
                }
            }.onFailure { _error.value = it.message ?: context.getString(R.string.terminal_error_create_session) }
        }
    }

    fun switchSession(id: String) = terminalManager.switchTo(id)

    fun closeSession(id: String, onResult: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            val success = runCatching { terminalManager.closeSession(id) }
                .onFailure { _error.value = it.message ?: context.getString(R.string.terminal_error_close_session) }
                .isSuccess
            onResult?.invoke(success)
        }
    }

    fun clearError() {
        _error.value = null
    }
}
