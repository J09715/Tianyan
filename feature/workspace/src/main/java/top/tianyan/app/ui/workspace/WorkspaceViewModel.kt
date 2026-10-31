package top.tianyan.app.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.tianyan.app.runtime.WorkspaceFileItem
import top.tianyan.app.runtime.WorkspaceManager
import top.tianyan.app.runtime.WorkspaceProject
import top.tianyan.app.runtime.WorkspaceStorage
import top.tianyan.app.core.common.result.AppResult
import top.tianyan.app.template.InstalledProjectTemplate
import top.tianyan.app.template.ProjectTemplateStore
import android.content.Context
import top.tianyan.app.feature.workspace.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import top.tianyan.app.core.datastore.FirstUseGuidePreferences
import top.tianyan.app.core.datastore.WorkshopKeystore
import top.tianyan.app.core.datastore.WorkshopPreferences
import top.tianyan.app.core.model.BuiltinPluginBundles
import top.tianyan.app.core.model.PluginBundle
import top.tianyan.app.core.model.PluginComponent
import top.tianyan.app.core.model.RuntimeState
import top.tianyan.app.core.tools.ToolManager
import top.tianyan.app.runtime.ApkImportSource
import top.tianyan.app.runtime.GitTransport
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.ProjectArchiveSource
import top.tianyan.app.runtime.ProjectTemplate
import top.tianyan.app.runtime.ProjectType
import top.tianyan.app.runtime.build.BuildRunProgress
import top.tianyan.app.runtime.build.WorkshopBuildType
import top.tianyan.app.runtime.tools.InstallEvent

class WorkspaceViewModel(
    private val context: Context,
    private val workspaceManager: WorkspaceManager,
    private val buildCoordinator: WorkspaceBuildTaskCoordinator,
    private val toolManager: ToolManager,
    private val linuxRuntime: LinuxRuntime,
    private val workshopPreferences: WorkshopPreferences,
    private val projectTemplateStore: ProjectTemplateStore,
    private val firstUseGuidePreferences: FirstUseGuidePreferences,
) : ViewModel() {

    /** 首次使用引导登记（统一存于偏好存储，设置页可整体清空重看）。 */
    val firstUseGuidesShown: StateFlow<Set<String>> = firstUseGuidePreferences.firstUseGuidesShown
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun markFirstUseGuideShown(id: String) {
        viewModelScope.launch { firstUseGuidePreferences.markFirstUseGuideShown(id) }
    }

    private val _projectTemplates = MutableStateFlow<List<InstalledProjectTemplate>>(emptyList())
    val projectTemplates: StateFlow<List<InstalledProjectTemplate>> = _projectTemplates.asStateFlow()
    private val _templateScriptPreview = MutableStateFlow<String?>(null)
    val templateScriptPreview: StateFlow<String?> = _templateScriptPreview.asStateFlow()

    // ==================== 聚合开发套件与子组件状态 ====================
    val pluginBundles: List<PluginBundle> = BuiltinPluginBundles.bundles

    private val _installedComponentIds = MutableStateFlow<Set<String>>(emptySet())
    val installedComponentIds: StateFlow<Set<String>> = _installedComponentIds.asStateFlow()

    /** Linux 沙箱是否已初始化就绪（组件探针依赖它，未就绪时探针结果不可信）。 */
    val runtimeReady: StateFlow<Boolean> = linuxRuntime.state
        .map { it is RuntimeState.Ready }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _activeBundleForSetup = MutableStateFlow<PluginBundle?>(null)
    val activeBundleForSetup: StateFlow<PluginBundle?> = _activeBundleForSetup.asStateFlow()

    private val _selectedComponents = MutableStateFlow<Set<String>>(emptySet())
    val selectedComponents: StateFlow<Set<String>> = _selectedComponents.asStateFlow()

    private val _isInstallingComponents = MutableStateFlow(false)
    val isInstallingComponents: StateFlow<Boolean> = _isInstallingComponents.asStateFlow()

    private val _suiteInstallProgress = MutableStateFlow<String?>(null)
    val suiteInstallProgress: StateFlow<String?> = _suiteInstallProgress.asStateFlow()

    init {
        refreshInstalledStatus()
        refreshProjectTemplates()
    }

    fun refreshProjectTemplates() {
        viewModelScope.launch(Dispatchers.IO) {
            _projectTemplates.value = projectTemplateStore.list()
        }
    }

    fun importProjectTemplate(uri: String) {
        if (_busy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            runCatching {
                val input = requireNotNull(context.contentResolver.openInputStream(Uri.parse(uri))) {
                    "无法读取模板文件"
                }
                input.use(projectTemplateStore::importZip)
            }.onSuccess { template ->
                refreshProjectTemplates()
                notify(context.getString(R.string.workspace_template_imported, template.manifest.name))
            }.onFailure { error ->
                notify(error.message ?: context.getString(R.string.workspace_template_import_failed), isError = true)
            }
            _busy.value = false
        }
    }

    fun exportProjectTemplate(id: String, uri: String) {
        if (_busy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            runCatching {
                val output = requireNotNull(context.contentResolver.openOutputStream(Uri.parse(uri), "wt")) {
                    "无法创建模板文件"
                }
                output.use { projectTemplateStore.exportZip(id, it) }
            }.onSuccess {
                notify(context.getString(R.string.workspace_template_exported))
            }.onFailure { error ->
                notify(error.message ?: context.getString(R.string.workspace_template_export_failed), isError = true)
            }
            _busy.value = false
        }
    }

    fun deleteProjectTemplate(id: String) {
        if (_busy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            runCatching { projectTemplateStore.delete(id) }
                .onSuccess {
                    refreshProjectTemplates()
                    notify(context.getString(R.string.workspace_template_deleted))
                }
                .onFailure { error -> notify(error.message ?: context.getString(R.string.workspace_template_delete_failed), isError = true) }
            _busy.value = false
        }
    }

    fun showTemplateScripts(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _templateScriptPreview.value = runCatching {
                projectTemplateStore.hookScripts(id).joinToString("\n\n") { (path, script) ->
                    "===== $path =====\n$script"
                }.ifBlank { "该模板没有构造脚本" }
            }.getOrElse { error -> "读取脚本失败：${error.message.orEmpty()}" }
        }
    }

    fun dismissTemplateScripts() {
        _templateScriptPreview.value = null
    }

    fun refreshInstalledStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _installedComponentIds.value = toolManager.probeInstalledComponents()
            } catch (_: Exception) {}
        }
    }

    fun openDevEnvironmentDialog(suggestedSuiteId: String? = null) {
        val targetBundle = if (suggestedSuiteId != null) {
            when (suggestedSuiteId) {
                "android_sdk", "android-suite" -> pluginBundles.firstOrNull { it.id == "android-suite" }
                "flutter", "flutter-suite", "flutter-core" -> pluginBundles.firstOrNull { it.id == "android-suite" }
                else -> pluginBundles.firstOrNull { it.id == suggestedSuiteId }
            } ?: pluginBundles.first()
        } else {
            pluginBundles.first()
        }
        val installed = _installedComponentIds.value
        val initial = targetBundle.components.filter { it.isRequired || it.id in installed }.map { it.id }.toSet()
        _selectedComponents.value = if (initial.isEmpty()) targetBundle.components.map { it.id }.toSet() else initial
        _activeBundleForSetup.value = targetBundle
    }

    fun selectBundleTab(bundle: PluginBundle) {
        val installed = _installedComponentIds.value
        val initial = bundle.components.filter { it.isRequired || it.id in installed }.map { it.id }.toSet()
        _selectedComponents.value = if (initial.isEmpty()) bundle.components.map { it.id }.toSet() else initial
        _activeBundleForSetup.value = bundle
    }

    fun closeDevEnvironmentDialog() {
        if (!_isInstallingComponents.value) {
            _activeBundleForSetup.value = null
        }
    }

    fun toggleComponent(component: PluginComponent) {
        if (component.isRequired) return // 锁定必选
        val current = _selectedComponents.value
        _selectedComponents.value = if (component.id in current) current - component.id else current + component.id
    }

    fun installSelectedComponents() {
        val selected = _selectedComponents.value
        if (selected.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            _isInstallingComponents.value = true
            try {
                toolManager.batchInstallComponents(selected).collect { event ->
                    if (event is InstallEvent.Progress) {
                        _suiteInstallProgress.value = event.message
                    }
                }
                refreshInstalledStatus()
                notify(context.getString(R.string.workspace_suite_installed))
                _activeBundleForSetup.value = null
            } catch (e: Exception) {
                notify(context.getString(R.string.workspace_deploy_failed, e.message.orEmpty()), isError = true)
            } finally {
                _isInstallingComponents.value = false
                _suiteInstallProgress.value = null
            }
        }
    }

    // 兼容原 devSuites 接口
    val devSuites: List<PluginBundle> get() = pluginBundles
    val showDevSuiteDialog: StateFlow<Boolean> get() = MutableStateFlow(_activeBundleForSetup.value != null).asStateFlow()
    val selectedDevSuites: StateFlow<Set<String>> get() = _selectedComponents
    val isInstallingSuites: StateFlow<Boolean> get() = _isInstallingComponents

    // ==================== 项目列表状态 ====================
    private val _loadingProjects = MutableStateFlow(true)
    val loadingProjects: StateFlow<Boolean> = _loadingProjects.asStateFlow()

    val projects: StateFlow<List<WorkspaceProject>> = workspaceManager.observeProjects()
        .onEach { if (_loadingProjects.value) _loadingProjects.value = false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 与 [message] 平行的错误标记：UI 依据它决定横幅样式，避免用文案关键字判错。 */
    private val _messageIsError = MutableStateFlow(false)
    val messageIsError: StateFlow<Boolean> = _messageIsError.asStateFlow()

    private fun notify(text: String, isError: Boolean = false) {
        _message.value = text
        _messageIsError.value = isError
    }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * GitHub 拉取进度：仅在进行中的 git clone 期间非空。
     * [text] 为 git 输出的最新一行（remote:/Receiving objects: 等，\r 分隔的进度更新取末段），
     * [percent] 从输出中解析的百分比（0-100），无法解析时为 null（UI 显示不确定进度条）。
     */
    data class GithubImportProgress(val text: String, val percent: Int?)

    private val _githubImportProgress = MutableStateFlow<GithubImportProgress?>(null)
    val githubImportProgress: StateFlow<GithubImportProgress?> = _githubImportProgress.asStateFlow()

    // 运行/构建状态
    val buildProgress: StateFlow<BuildRunProgress?> = buildCoordinator.state
        .map { it?.progress }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), buildCoordinator.state.value?.progress)

    val activeBuildingProjectName: StateFlow<String?> = buildCoordinator.state
        .map { state -> state?.takeIf { it.progress.isRunning }?.project?.name }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _isBuildDialogVisible = MutableStateFlow<Boolean>(false)
    val isBuildDialogVisible: StateFlow<Boolean> = _isBuildDialogVisible.asStateFlow()

    /** 工坊已登记的 Android 签名（Release 构建时选择）。 */
    val keystores: StateFlow<List<WorkshopKeystore>> = workshopPreferences.keystores
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ==================== 文件浏览器状态 ====================
    private val _selectedProject = MutableStateFlow<String?>(null)
    val selectedProject: StateFlow<String?> = _selectedProject.asStateFlow()

    private val _currentPath = MutableStateFlow("")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    private val _fileItems = MutableStateFlow<List<WorkspaceFileItem>>(emptyList())
    val fileItems: StateFlow<List<WorkspaceFileItem>> = _fileItems.asStateFlow()

    private val _loadingFiles = MutableStateFlow(false)
    val loadingFiles: StateFlow<Boolean> = _loadingFiles.asStateFlow()

    /**
     * 当前浏览的项目位于宿主共享存储（/storage/emulated/0），
     * 且缺少"所有文件访问"权限——此时系统会过滤其他应用的文件（只显示文件夹），
     * 文件浏览器需要展示授权引导横幅。
     */
    private val _sharedStorageAccessLimited = MutableStateFlow(false)
    val sharedStorageAccessLimited: StateFlow<Boolean> = _sharedStorageAccessLimited.asStateFlow()

    // ==================== 代码编辑器状态 ====================
    private val editor = EditorSessionController(
        scope = viewModelScope,
        workspaceManager = workspaceManager,
        context = context,
        notify = ::notify,
        selectedProject = _selectedProject,
        loadingFiles = _loadingFiles,
    )
    val openedFilePath: StateFlow<String?> = editor.openedFilePath
    val fileContent: StateFlow<String> = editor.fileContent
    val contentRevision: StateFlow<Long> = editor.contentRevision
    val isDirty: StateFlow<Boolean> = editor.isDirty
    val isSaving: StateFlow<Boolean> = editor.isSaving
    val saveConflict: StateFlow<Boolean> = editor.saveConflict
    /** 非空表示当前文件是二进制：编辑器不载入，改由系统应用打开/分享。 */
    val binaryFile: StateFlow<top.tianyan.app.runtime.WorkspaceFileInspection?> = editor.binaryFile

    init {
        refresh()
    }

    fun clearMessage() {
        _message.value = null
        _messageIsError.value = false
    }

    fun showBuildDialog() { _isBuildDialogVisible.value = true }

    fun hideBuildDialog() { _isBuildDialogVisible.value = false }

    fun dismissBuildProgress() {
        _isBuildDialogVisible.value = false
        buildCoordinator.dismiss()
    }

    fun refresh() {
        viewModelScope.launch {
            workspaceManager.listProjects()
        }
    }

    // ==================== 项目级操作 ====================

    fun create(
        name: String,
        storage: WorkspaceStorage = WorkspaceStorage.INTERNAL,
        directoryPath: String = "",
        template: ProjectTemplate = ProjectTemplate.EMPTY,
        packageName: String = "",
        apkSource: ApkImportSource? = null,
        exportApkToDownload: Boolean = false,
        gitUrl: String = "",
        templateVariables: Map<String, String> = emptyMap(),
        templateId: String = "",
        trustTemplateScripts: Boolean = false,
    ) {
        if (_busy.value) return
        // 同步置 busy：UI 依据它把创建弹窗保持在"进行中"状态（模板实例化可能耗时 60 秒）
        _busy.value = true
        viewModelScope.launch {
            val result = workspaceManager.createProject(
                name,
                storage,
                directoryPath,
                template,
                packageName,
                apkSource,
                exportApkToDownload,
                gitUrl,
                templateVariables,
                templateId,
                trustTemplateScripts,
            )
            _message.value = result.errorOrNull()?.message ?: context.getString(R.string.workspace_project_created)
            _messageIsError.value = result.isFailure
            if (result.isSuccess) workspaceManager.listProjects()
            _busy.value = false
        }
    }

    fun importLocalProject(
        name: String,
        directoryPath: String,
        projectType: ProjectType,
        source: ProjectArchiveSource,
    ) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val result = workspaceManager.importProjectArchive(name, directoryPath, projectType, source)
            _message.value = result.errorOrNull()?.message ?: context.getString(R.string.workspace_project_imported)
            _messageIsError.value = result.isFailure
            if (result.isSuccess) workspaceManager.listProjects()
            _busy.value = false
        }
    }

    fun importGithubProject(
        name: String,
        directoryPath: String,
        projectType: ProjectType,
        gitUrl: String,
        transport: GitTransport,
    ) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _githubImportProgress.value = GithubImportProgress(
                text = context.getString(R.string.workspace_git_clone_connecting),
                percent = null,
            )
            val result = workspaceManager.importGithubProject(name, directoryPath, projectType, gitUrl, transport) { chunk ->
                workspaceImportProgress(chunk)?.let { _githubImportProgress.value = it }
            }
            _message.value = result.errorOrNull()?.message ?: context.getString(R.string.workspace_project_imported)
            _messageIsError.value = result.isFailure
            if (result.isSuccess) workspaceManager.listProjects()
            _githubImportProgress.value = null
            _busy.value = false
        }
    }

    fun exportProject(project: WorkspaceProject, targetTreeUri: String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val result = workspaceManager.exportProject(project.name, targetTreeUri)
            _message.value = result.errorOrNull()?.message
                ?: context.getString(R.string.workspace_project_exported, result.getOrNull().orEmpty())
            _messageIsError.value = result.isFailure
            _busy.value = false
        }
    }

    fun runProject(
        project: WorkspaceProject,
        buildType: WorkshopBuildType = WorkshopBuildType.DEBUG,
        keystore: WorkshopKeystore? = null,
    ) {
        if (buildCoordinator.state.value?.progress?.isRunning == true) {
            _isBuildDialogVisible.value = true
            return
        }
        _isBuildDialogVisible.value = true
        buildCoordinator.start(project, buildType, keystore)
    }

    /** 用户主动取消编译任务 */
    fun cancelBuild() { buildCoordinator.cancel() }

    fun launchInstaller(apkPath: String) {
        buildCoordinator.launchPackageInstaller(apkPath)
    }

    fun delete(name: String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val result = workspaceManager.deleteProject(name)
            _message.value = result.errorOrNull()?.message ?: context.getString(R.string.workspace_project_deleted)
            _messageIsError.value = result.isFailure
            if (result.isSuccess) workspaceManager.listProjects()
            _busy.value = false
        }
    }

    // ==================== 文件浏览器操作 ====================

    fun loadExplorer(projectName: String, relativePath: String = "") {
        val nextPath = computeExplorerPath(
            currentProject = _selectedProject.value,
            currentPath = _currentPath.value,
            targetProject = projectName,
            targetPath = relativePath,
        )
        _selectedProject.value = projectName
        _currentPath.value = nextPath
        refreshDirectory()
    }

    fun navigateToDirectory(relativePath: String) {
        _currentPath.value = relativePath.trim().removePrefix("/")
        refreshDirectory()
    }

    fun navigateUp() {
        val current = _currentPath.value
        if (current.isBlank()) return
        val parent = current.substringBeforeLast('/', "")
        _currentPath.value = parent
        refreshDirectory()
    }

    fun refreshDirectory() {
        val proj = _selectedProject.value ?: return
        val path = _currentPath.value
        viewModelScope.launch {
            _loadingFiles.value = true
            refreshSharedStorageAccessLimited(proj)
            val result = workspaceManager.listFiles(proj, path)
            if (result.isSuccess) {
                _fileItems.value = result.getOrNull().orEmpty()
            } else {
                notify(result.errorOrNull()?.message ?: context.getString(R.string.workspace_read_directory_failed), isError = true)
            }
            _loadingFiles.value = false
        }
    }

    /** 从系统授权页返回后调用：重新评估权限状态并刷新目录。 */
    fun refreshAfterPermissionReturn() {
        val proj = _selectedProject.value ?: return
        viewModelScope.launch {
            refreshSharedStorageAccessLimited(proj)
            refreshDirectory()
        }
    }

    private suspend fun refreshSharedStorageAccessLimited(projectName: String) {
        _sharedStorageAccessLimited.value =
            workspaceManager.usesSharedStorage(projectName) && !hasSharedStorageAccess()
    }

    private fun hasSharedStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }

    /** 文件增删改的统一模板：取项目 → 执行 → 成功刷新目录/失败写消息 → 复位 busy */
    private fun runFileOp(
        successMessage: suspend () -> String,
        fallbackError: Int,
        onSuccess: () -> Unit = {},
        op: suspend () -> AppResult<Unit>,
    ) {
        if (_selectedProject.value == null || _busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val result = op()
                if (result.isSuccess) {
                    notify(successMessage())
                    refreshDirectory()
                    onSuccess()
                } else {
                    notify(result.errorOrNull()?.message ?: context.getString(fallbackError), isError = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notify(e.message ?: context.getString(fallbackError), isError = true)
            } finally {
                _busy.value = false
            }
        }
    }

    private fun validatedEntryName(name: String): String? {
        val trimmed = name.trim()
        if (isValidWorkspaceEntryName(trimmed)) return trimmed
        notify(context.getString(R.string.workspace_invalid_name), isError = true)
        return null
    }

    fun createFile(name: String, onSuccess: () -> Unit = {}) {
        val proj = _selectedProject.value ?: return
        val trimmed = validatedEntryName(name) ?: return
        val fullRelative = if (_currentPath.value.isBlank()) trimmed else "${_currentPath.value}/$trimmed"
        runFileOp(
            successMessage = { context.getString(R.string.workspace_file_created, trimmed) },
            fallbackError = R.string.workspace_create_file_failed,
            onSuccess = onSuccess,
        ) { workspaceManager.createFile(proj, fullRelative) }
    }

    fun createDirectory(name: String, onSuccess: () -> Unit = {}) {
        val proj = _selectedProject.value ?: return
        val trimmed = validatedEntryName(name) ?: return
        val fullRelative = if (_currentPath.value.isBlank()) trimmed else "${_currentPath.value}/$trimmed"
        runFileOp(
            successMessage = { context.getString(R.string.workspace_directory_created, trimmed) },
            fallbackError = R.string.workspace_create_directory_failed,
            onSuccess = onSuccess,
        ) { workspaceManager.createDirectory(proj, fullRelative) }
    }

    fun renameItem(oldRelativePath: String, newName: String, onSuccess: () -> Unit = {}) {
        val proj = _selectedProject.value ?: return
        val trimmed = validatedEntryName(newName) ?: return
        runFileOp(
            successMessage = { context.getString(R.string.workspace_renamed, trimmed) },
            fallbackError = R.string.workspace_rename_failed,
            onSuccess = onSuccess,
        ) { workspaceManager.renameItem(proj, oldRelativePath, trimmed) }
    }

    fun deleteItem(relativePath: String) {
        val proj = _selectedProject.value ?: return
        runFileOp(
            successMessage = { context.getString(R.string.workspace_deleted) },
            fallbackError = R.string.workspace_delete_failed,
        ) { workspaceManager.deleteItem(proj, relativePath) }
    }

    // ==================== 编辑器操作 ====================

    fun editorText(project: String, path: String): String = editor.editorText(project, path)

    fun openFile(projectName: String, relativePath: String) = editor.openFile(projectName, relativePath)

    fun onContentChanged(newText: String) = editor.onContentChanged(newText)

    fun resetContent() = editor.resetContent()

    fun saveFile(content: String? = null, onSuccess: (() -> Unit)? = null) = editor.saveFile(content, onSuccess)

    fun overwriteSaveConflict() = editor.overwriteSaveConflict()
    fun reloadSaveConflict() = editor.reloadSaveConflict()
    fun dismissSaveConflict() = editor.dismissSaveConflict()
    fun closeFile() = editor.closeFile()

    /** 用系统应用打开/分享当前二进制文件（APK、图片、压缩包等）。 */
    fun openCurrentFileExternally(share: Boolean) = editor.openExternally(share)
}
