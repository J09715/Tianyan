package top.tianyan.app.ui.workspace

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.tianyan.app.core.common.result.ErrorCode
import top.tianyan.app.feature.workspace.R
import top.tianyan.app.runtime.HostFileActions
import top.tianyan.app.runtime.WorkspaceManager
import top.tianyan.app.runtime.WorkspaceFileInspection
import top.tianyan.app.runtime.hostFile
import top.tianyan.app.runtime.inspectFile

/**
 * 代码编辑器会话：持有草稿与其状态（脏标记/版本号/保存冲突），负责打开、编辑、保存与冲突处理。
 * 项目选择与目录加载状态由宿主 ViewModel 共享（文件浏览器也用），因此以引用方式传入。
 * 独立成文件仅为控制宿主的单文件行数（架构棘轮），行为与原内联实现保持一致。
 */
internal class EditorSessionController(
    private val scope: CoroutineScope,
    private val workspaceManager: WorkspaceManager,
    private val context: Context,
    private val notify: (String, Boolean) -> Unit,
    private val selectedProject: MutableStateFlow<String?>,
    private val loadingFiles: MutableStateFlow<Boolean>,
) {
    private val draft = EditorDraft()

    private val _openedFilePath = MutableStateFlow<String?>(null)
    val openedFilePath: StateFlow<String?> = _openedFilePath.asStateFlow()

    private val _fileContent = MutableStateFlow("")
    val fileContent: StateFlow<String> = _fileContent.asStateFlow()

    private val _contentRevision = MutableStateFlow(0L)
    /** 文件内容整文加载/重置版本号：仅在 openFile 或 resetContent 时递增，打字过程中不变化 */
    val contentRevision: StateFlow<Long> = _contentRevision.asStateFlow()

    private val _isDirty = MutableStateFlow(false)
    val isDirty: StateFlow<Boolean> = _isDirty.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _saveConflict = MutableStateFlow(false)
    /** 保存时检测到磁盘版本与编辑器基线不一致：置真后由 UI 提示「覆盖 / 重新加载」。 */
    val saveConflict: StateFlow<Boolean> = _saveConflict.asStateFlow()

    /** 冲突前待执行的保存回调（如「保存并退出」），覆盖保存成功后继续执行。 */
    private var pendingConflictOnSuccess: (() -> Unit)? = null

    private val _binaryFile = MutableStateFlow<WorkspaceFileInspection?>(null)

    /** 非空表示当前文件是二进制（APK/图片/压缩包）：编辑器拒绝载入，改由系统应用打开。 */
    val binaryFile: StateFlow<WorkspaceFileInspection?> = _binaryFile.asStateFlow()

    fun editorText(project: String, path: String): String =
        if (draft.matches(project, path)) draft.text else ""

    fun openFile(projectName: String, relativePath: String) {
        selectedProject.value = projectName
        _openedFilePath.value = relativePath
        _saveConflict.value = false
        _binaryFile.value = null
        pendingConflictOnSuccess = null
        if (draft.matches(projectName, relativePath)) return
        scope.launch {
            loadingFiles.value = true
            // 二进制文件先拦：按 UTF-8 读进来只会得到乱码，保存还会把原文件写坏。
            val inspection = workspaceManager.inspectFile(projectName, relativePath).getOrNull()
            if (selectedProject.value != projectName || _openedFilePath.value != relativePath) return@launch
            if (inspection?.binary == true) {
                _binaryFile.value = inspection
                _fileContent.value = ""
                _isDirty.value = false
                loadingFiles.value = false
                return@launch
            }
            val result = workspaceManager.readFile(projectName, relativePath)
            if (selectedProject.value != projectName || _openedFilePath.value != relativePath) return@launch
            if (result.isSuccess) {
                val content = result.getOrNull().orEmpty()
                draft.load(projectName, relativePath, content)
                _fileContent.value = content
                _isDirty.value = false
                _contentRevision.value++
            } else {
                notify(result.errorOrNull()?.message ?: context.getString(R.string.workspace_open_file_failed), true)
            }
            loadingFiles.value = false
        }
    }

    /** 交给系统应用：APK/图片/压缩包等二进制产物走这里，不走编辑器。 */
    fun openExternally(share: Boolean) {
        val proj = selectedProject.value ?: return
        val path = _openedFilePath.value ?: return
        scope.launch {
            val file = workspaceManager.hostFile(proj, path).getOrNull()
            val launched = file != null &&
                if (share) HostFileActions.share(context, file, subject = file.name) else HostFileActions.open(context, file)
            if (!launched) notify(context.getString(R.string.workspace_open_external_failed), true)
        }
    }

    fun onContentChanged(newText: String) {
        if (loadingFiles.value || !draft.matches(selectedProject.value, _openedFilePath.value)) return
        draft.edit(newText)
        _isDirty.value = draft.isDirty
    }

    fun resetContent() {
        draft.reset()
        _fileContent.value = draft.text
        _isDirty.value = false
        _contentRevision.value++
    }

    fun saveFile(content: String? = null, onSuccess: (() -> Unit)? = null) {
        val proj = selectedProject.value ?: return
        val path = _openedFilePath.value ?: return
        if (_binaryFile.value != null) return
        if (_isSaving.value || loadingFiles.value || !draft.matches(proj, path)) return
        content?.let(draft::edit)
        // 以编辑器最后已知的磁盘内容作为版本基线：磁盘已被外部（如 AI 工具）改动时拒绝覆盖。
        writeDraft(proj, path, draft.snapshot(), draft.savedContent, onSuccess)
    }

    /** 用户选择「覆盖」：忽略磁盘版本差异，强制写入当前草稿。 */
    fun overwriteSaveConflict() {
        val proj = selectedProject.value ?: return
        val path = _openedFilePath.value ?: return
        val onSuccess = pendingConflictOnSuccess
        pendingConflictOnSuccess = null
        _saveConflict.value = false
        if (_isSaving.value || !draft.matches(proj, path)) return
        writeDraft(proj, path, draft.snapshot(), expectedContent = null, onSuccess = onSuccess)
    }

    /** 用户选择「重新加载」：用磁盘上的最新内容替换草稿，放弃本地未保存修改。 */
    fun reloadSaveConflict() {
        val proj = selectedProject.value ?: return
        val path = _openedFilePath.value ?: return
        pendingConflictOnSuccess = null
        _saveConflict.value = false
        if (!draft.matches(proj, path)) return
        scope.launch {
            loadingFiles.value = true
            val result = workspaceManager.readFile(proj, path)
            if (selectedProject.value != proj || _openedFilePath.value != path) {
                loadingFiles.value = false
                return@launch
            }
            if (result.isSuccess) {
                val fresh = result.getOrNull().orEmpty()
                draft.load(proj, path, fresh)
                _fileContent.value = fresh
                _isDirty.value = false
                _contentRevision.value++
            } else {
                notify(result.errorOrNull()?.message ?: context.getString(R.string.workspace_open_file_failed), true)
            }
            loadingFiles.value = false
        }
    }

    /** 关闭冲突提示并保留草稿（用户继续手工对比）。 */
    fun dismissSaveConflict() {
        pendingConflictOnSuccess = null
        _saveConflict.value = false
    }

    private fun writeDraft(
        proj: String,
        path: String,
        snapshot: EditorDraft.SaveSnapshot,
        expectedContent: String?,
        onSuccess: (() -> Unit)?,
    ) {
        _isSaving.value = true
        scope.launch {
            val result = workspaceManager.writeFile(proj, path, snapshot.text, expectedContent)
            if (result.isSuccess) {
                _saveConflict.value = false
                val stillOpen = draft.markSaved(snapshot)
                if (stillOpen) {
                    _fileContent.value = snapshot.text
                    _isDirty.value = draft.isDirty
                }
                notify(context.getString(R.string.workspace_file_saved), false)
                if (stillOpen && !draft.isDirty) onSuccess?.invoke()
            } else if (result.errorOrNull()?.code == ErrorCode.CONFLICT) {
                pendingConflictOnSuccess = onSuccess
                _saveConflict.value = true
            } else {
                notify(result.errorOrNull()?.message ?: context.getString(R.string.workspace_save_failed), true)
            }
            _isSaving.value = false
        }
    }

    fun closeFile() {
        _openedFilePath.value = null
        _fileContent.value = ""
        _binaryFile.value = null
        draft.clear()
        _isDirty.value = false
        _saveConflict.value = false
        pendingConflictOnSuccess = null
    }
}