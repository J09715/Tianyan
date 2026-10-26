package top.tianyan.app.core.tools

import top.tianyan.app.core.database.InstallLogEntity
import top.tianyan.app.core.database.InstallTaskEntity
import top.tianyan.app.core.database.ToolEntity
import top.tianyan.app.core.model.ToolManifest
import top.tianyan.app.core.model.ToolState
import top.tianyan.app.core.security.SecretRedactor
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.BackgroundTaskRegistry
import top.tianyan.app.runtime.service.LocalServiceSpec
import top.tianyan.app.runtime.service.ProotServiceDescriptor
import top.tianyan.app.runtime.tools.InstallEvent
import top.tianyan.app.runtime.shell.LinuxSession
import top.tianyan.app.runtime.shell.ManagedProcess
import top.tianyan.app.runtime.shell.CommandResult
import top.tianyan.app.runtime.shell.SessionConfig
import top.tianyan.app.runtime.shell.ShellCommand
import top.tianyan.app.runtime.tools.GenericRecipeInstaller
import top.tianyan.app.runtime.tools.ToolCommandLinker
import top.tianyan.app.runtime.scripts.RuntimeAssetSynchronizer
import top.tianyan.app.core.model.BuiltinPluginBundles
import top.tianyan.app.core.database.ToolSettingsRepository
import top.tianyan.app.core.datastore.ToolPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.net.Uri
import top.tianyan.app.core.common.result.AppResult

class ToolManager(
    private val toolRepository: ToolRepository,
    private val installLogRepository: InstallLogRepository,
    private val installTaskRepository: InstallTaskRepository,
    private val installTransactionManager: InstallTransactionManager,
    private val dependencyManager: DependencyManager,
    private val linuxRuntime: LinuxRuntime,
    private val backgroundTaskRegistry: BackgroundTaskRegistry,
    private val providerManager: ProviderManager,
    private val toolCommandLinker: ToolCommandLinker,
    private val notificationNotifier: ToolNotificationNotifier,
    private val secretRedactor: SecretRedactor,
    private val toolSettingsRepository: ToolSettingsRepository,
    private val settingsDataStore: ToolPreferences,
    private val assetSynchronizer: RuntimeAssetSynchronizer,
    private val flutterSdkDownloader: FlutterSdkDownloader,
    private val localPluginPayloadManager: LocalPluginPayloadManager,
    private val serviceController: ToolServiceController,
    private val packageOperations: ToolPackageOperations,
    installerAdapters: Set<@JvmSuppressWildcards ToolRuntimeAdapter>,
    private val localAccess: top.tianyan.app.runtime.environment.LocalBackendAccess? = null,
) {
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val installMutex = Mutex()
    private val installJobs = mutableMapOf<String, Job>()
    private val _installProgress = MutableStateFlow<Map<String, ToolInstallProgress>>(emptyMap())
    val installProgress: StateFlow<Map<String, ToolInstallProgress>> = _installProgress.asStateFlow()
    private val _verifications = MutableStateFlow<Map<String, ToolVerification>>(emptyMap())
    val verifications: StateFlow<Map<String, ToolVerification>> = _verifications.asStateFlow()

    private val _bundleInstallState = MutableStateFlow<String?>(null)
    val bundleInstallState: StateFlow<String?> = _bundleInstallState.asStateFlow()
    private val _bundleInstallLog = MutableStateFlow<List<String>>(emptyList())
    val bundleInstallLog: StateFlow<List<String>> = _bundleInstallLog.asStateFlow()

    private val _isBatchInstalling = MutableStateFlow(false)
    val isBatchInstalling: StateFlow<Boolean> = _isBatchInstalling.asStateFlow()
    private val bundleBatch = BundleComponentBatch(
        localAccess = localAccess,
        linuxRuntime = linuxRuntime,
        backgroundTaskRegistry = backgroundTaskRegistry,
        notificationNotifier = notificationNotifier,
        installLogRepository = installLogRepository,
        assetSynchronizer = assetSynchronizer,
        flutterSdkDownloader = flutterSdkDownloader,
        installMutex = installMutex,
        isBatchInstalling = _isBatchInstalling,
        bundleInstallState = _bundleInstallState,
        bundleInstallLog = _bundleInstallLog,
        scope = managerScope,
        currentDistroId = ::currentDistroId,
        probeInstalledComponents = ::probeInstalledComponents,
    )

    private val _localPluginImportState = MutableStateFlow<LocalPluginImportState>(LocalPluginImportState.Idle)
    val localPluginImportState: StateFlow<LocalPluginImportState> = _localPluginImportState.asStateFlow()
    private var localPluginImportJob: Job? = null

    private val staticInstallerById = installerAdapters.associateBy { it.toolId }

    init {
        // Keep the latest bundle log available after a tab/activity recreation.
        managerScope.launch {
            runCatching {
                val persisted = installLogRepository
                    .observeForTool(currentDistroId(), BundleComponentBatch.LOG_TOOL_ID)
                    .first()
                    .map { it.message }
                if (persisted.isNotEmpty()) _bundleInstallLog.value = persisted.takeLast(BundleComponentBatch.MAX_LOG_LINES)
            }
        }
    }

    /** 当前发行版（安装/卸载/验证等操作的作用目标系统）。 */
    private fun currentDistroId(): String = linuxRuntime.activeDistroId.value

    /** 插件状态按当前发行版隔离：切换系统后自动切换为该系统的安装状态。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeTools(): Flow<List<ToolEntity>> =
        linuxRuntime.activeDistroId.flatMapLatest { distroId ->
            toolRepository.observeTools(distroId).map { list ->
                list.filterNot { it.id in ToolRegistry.REMOVED_TOOL_IDS }
            }
        }

    /** Expose manifest metadata for detail screens. */
    fun manifest(toolId: String): ToolManifest? = toolRepository.manifest(toolId)

    suspend fun importLocalPlugin(
        uri: Uri,
        expected: ToolManifest? = null,
        onProgress: (LocalPluginImportProgress) -> Unit = {},
    ): AppResult<ToolManifest> {
        packageOperations.captureLegacy(linuxRuntime.installedDistros.value.map { it.id } + currentDistroId())
        val result = toolRepository.importLocal(uri, onProgress, expected)
        if (result is AppResult.Success) syncRegistry()
        return result
    }

    /** Read and validate package metadata; no extraction or registry mutation occurs yet. */
    @Synchronized
    fun startLocalPluginImport(uri: Uri, fileName: String, requireAvailable: Boolean = false): Job {
        localAccess?.requireLocal()
        if (requireAvailable) requireLocalPluginImportAvailable(_localPluginImportState.value, localPluginImportJob?.isActive == true)
        localPluginImportJob?.takeIf { it.isActive }?.let { return it }
        _localPluginImportState.value = LocalPluginImportState.Reading(fileName)
        return managerScope.launch {
            try {
                val previewDistro = currentDistroId()
                val result = toolRepository.inspectLocal(uri)
                _localPluginImportState.value = when (result) {
                    is AppResult.Success -> if (result.data.alreadyImported) LocalPluginImportState.AlreadyImported(result.data.manifest.name, result.data.manifest.version) else LocalPluginImportState.PendingConfirmation(
                        uri = uri,
                        fileName = fileName,
                        manifest = result.data.manifest,
                        archiveSizeBytes = result.data.archiveSizeBytes,
                        addedCapabilities = packageOperations.addedCapabilities(previewDistro, result.data.manifest),
                        distroId = previewDistro,
                    )
                    is AppResult.Failure -> LocalPluginImportState.Failed(result.error.message)
                }
            } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) {
                _localPluginImportState.value = LocalPluginImportState.Failed(failure.message ?: "插件预览失败")
            }
        }.also { localPluginImportJob = it }
    }

    /** Confirm a previously previewed package and perform extraction/registration. */
    @Synchronized
    fun confirmLocalPluginImport(): Job? {
        localAccess?.requireLocal()
        val pending = _localPluginImportState.value as? LocalPluginImportState.PendingConfirmation ?: return null
        if (pending.distroId != currentDistroId()) {
            _localPluginImportState.value = LocalPluginImportState.Failed("目标系统已变化，请重新导入确认")
            return null
        }
        _localPluginImportState.value = LocalPluginImportState.Importing(
            fileName = pending.fileName,
            progress = null,
            bytesRead = 0L,
            totalBytes = pending.archiveSizeBytes,
            currentEntry = null,
        )
        return managerScope.launch {
            try {
                val result = importLocalPlugin(pending.uri, expected = pending.manifest) { progress ->
                    _localPluginImportState.value = LocalPluginImportState.Importing(
                        fileName = pending.fileName,
                        progress = progress.fraction,
                        bytesRead = progress.bytesRead,
                        totalBytes = progress.totalBytes,
                        currentEntry = progress.currentEntry,
                    )
                }
                _localPluginImportState.value = when (result) {
                is AppResult.Success -> {
                    val manifest = result.data
                    var installCompleted = false
                    var installFailure: String? = null
                    try {
                        installInternal(manifest.id, OPERATION_INSTALL, pending.distroId, manifest).collect { event ->
                            when (event) {
                                is InstallEvent.Progress -> _localPluginImportState.value = LocalPluginImportState.Importing(
                                    fileName = pending.fileName,
                                    progress = event.progress,
                                    bytesRead = pending.archiveSizeBytes ?: 0L,
                                    totalBytes = pending.archiveSizeBytes,
                                    currentEntry = event.message,
                                )
                                is InstallEvent.Completed -> installCompleted = true
                                is InstallEvent.Failed -> installFailure = event.message
                                is InstallEvent.Cancelled -> installFailure = "插件安装已取消"
                                else -> Unit
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (throwable: Throwable) {
                        installFailure = throwable.message ?: "插件安装失败"
                    }
                    if (installCompleted) {
                        LocalPluginImportState.Succeeded(manifest.name, manifest.version)
                    } else {
                        LocalPluginImportState.Failed(installFailure ?: "插件已解压，但安装流程未完成")
                    }
                }
                    is AppResult.Failure -> LocalPluginImportState.Failed(result.error.message)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                _localPluginImportState.value = LocalPluginImportState.Failed(
                    "本地插件安装失败：${throwable.message ?: "未知错误"}",
                )
            }
        }.also { localPluginImportJob = it }
    }

    fun cancelLocalPluginImport() {
        if (_localPluginImportState.value is LocalPluginImportState.PendingConfirmation) {
            _localPluginImportState.value = LocalPluginImportState.Idle
        }
    }

    fun clearLocalPluginImportState() {
        if (localPluginImportJob?.isActive != true) {
            _localPluginImportState.value = LocalPluginImportState.Idle
        }
    }

    /** Check whether a background gateway process is alive AND its port is listening (web services). */
    suspend fun isGatewayRunning(toolId: String): Boolean {
        val spec = serviceSpec(toolId)
        return serviceController.isRunning(toolId, spec)
    }

    /** Stop a running gateway service for the given tool. */
    suspend fun stopGateway(toolId: String) {
        val spec = serviceSpec(toolId)
        serviceController.stop(toolId, spec)
    }

    /**
     * Restart a running gateway service. Used to apply config changes (new token,
     * model environment) that are injected via environment variables at process start.
     */
    suspend fun restartGateway(toolId: String): ManagedProcess {
        localAccess?.requireLocal()
        requireInstalledTool(toolId)
        return serviceController.restart(toolId, requireAdapter(toolId), serviceSpec(toolId))
    }

    /** Observe real-time output logs for a tool's background service. */
    fun observeServiceLogs(toolId: String): Flow<List<String>> =
        serviceController.observeLogs(toolId)

    /** Get snapshot of service logs for a tool. */
    fun getServiceLogs(toolId: String): List<String> =
        serviceController.getLogs(toolId)

    /** Clear service logs for a tool. */
    fun clearServiceLogs(toolId: String) {
        serviceController.clearLogs(toolId)
    }

    fun isToolSupported(toolId: String): Boolean = getAdapter(toolId) != null

    fun startInstall(toolId: String): Job {
        localAccess?.requireLocal()
        val existing = installJobs[toolId]
        if (existing?.isActive == true) return existing
        return managerScope.launch {
            try {
                install(toolId).collect { }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                // Handled in install flow
            }
        }
    }

    fun startUpdate(toolId: String): Job {
        localAccess?.requireLocal()
        val existing = installJobs[toolId]
        if (existing?.isActive == true) return existing
        return managerScope.launch {
            try {
                update(toolId).collect { }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                // Handled in update flow
            }
        }
    }

    private fun getAdapter(toolId: String, fixed: ToolManifest? = null, distroId: String? = null): ToolRuntimeAdapter? {
        if (fixed?.schemaVersion != 2) staticInstallerById[toolId]?.let { return it }
        val manifest = fixed ?: toolRepository.manifest(toolId) ?: return null
        if (!manifest.installScript.isNullOrBlank() || manifest.installMethod.isNotBlank()) {
            return GenericRecipeInstaller(
                manifest = manifest,
                linuxRuntime = distroId?.let { PinnedToolRuntime(linuxRuntime, it, manifest.executionIdentity()) } ?: linuxRuntime,
                dependencyManager = dependencyManager,
                providerManager = providerManager,
                toolCommandLinker = distroId?.let { ToolCommandLinker(PinnedToolRuntime(linuxRuntime, it, manifest.executionIdentity())) } ?: toolCommandLinker,
                localPluginPayloadManager = localPluginPayloadManager,
            )
        }
        return null
    }

    /** Service metadata comes from the signed/validated manifest, not the UI. */
    suspend fun serviceSpec(toolId: String): LocalServiceSpec? {
        val manifest = packageOperations.runtimeManifest(currentDistroId(), toolId) ?: return null
        val port = manifest.servicePort ?: return null
        return LocalServiceSpec(
            serviceId = toolId,
            port = port,
            path = manifest.servicePath,
        )
    }

    /**
     * Native launch plan for a tool service a plugin page may start itself. Port, path, command and
     * environment are all resolved natively from the installed manifest and provider environment,
     * never from Web content, a package field or a page argument.
     */
    suspend fun serviceLaunch(toolId: String): ProotServiceDescriptor? {
        val spec = serviceSpec(toolId) ?: return null
        val distro = currentDistroId()
        val manifest = packageOperations.runtimeManifest(distro, toolId) ?: return null
        val launch = getAdapter(toolId, manifest, distro)?.serviceLaunch() ?: return null
        return ProotServiceDescriptor(
            port = spec.port,
            basePath = spec.path.trimEnd('/') + "/",
            launchCommand = launch.launchCommand,
            environment = launch.environment, pluginIdentity = launch.pluginIdentity,
            ownerPluginId = toolId,
            distroId = distro,
        )
    }

    suspend fun syncRegistry() {
        val liveInstallTools = installJobs.keys.toSet()
        // 中断任务恢复按任务记录的所属系统恢复；元数据同步覆盖所有已安装系统
        val distroIds = linuxRuntime.installedDistros.value.map { it.id }
            .ifEmpty { listOf(currentDistroId()) }
            .distinct()
        installTaskRepository.listByState(TASK_RUNNING)
            .filter { it.toolId !in liveInstallTools }
            .forEach { task ->
                val taskDistro = task.distroId
                installTransactionManager.recover(
                    distroId = taskDistro,
                    toolId = task.toolId,
                    preserveExisting = task.operation == OPERATION_UPDATE,
                )
                installTaskRepository.upsert(
                    task.copy(
                        state = TASK_INTERRUPTED,
                        message = "检测到应用进程被中断，请重试",
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                val interruptedTool = toolRepository.findById(taskDistro, task.toolId)
                val recoveredState = if (
                    task.operation == OPERATION_UPDATE && interruptedTool?.installedVersion != null
                ) {
                    ToolState.INSTALLED.name
                } else {
                    ToolState.FAILED.name
                }
                toolRepository.updateState(taskDistro, task.toolId, recoveredState)
                installLogRepository.insert(
                    InstallLogEntity(
                        distroId = taskDistro,
                        toolId = task.toolId,
                        event = TASK_INTERRUPTED,
                        message = "${task.operation} 任务在应用进程终止后被标记为中断",
                    ),
                )
            }
        // Registry parsing is a recoverable boundary. A malformed optional/remote
        // manifest must not terminate the application or the local-plugin flow.
        val manifests = try {
            toolRepository.manifests()
        } catch (_: Throwable) {
            return
        }
        distroIds.forEach { distroId ->
            manifests.forEach { manifest ->
                val existing = toolRepository.findById(distroId, manifest.id)
                toolRepository.upsert(manifest.toEntity(distroId, existing))
                if (existing?.state == ToolState.INSTALLING.name && manifest.id !in liveInstallTools) {
                    // The process may have been killed while an installer was running.
                    // Never leave a durable INSTALLING state that has no live Job behind it.
                    toolRepository.updateState(distroId, manifest.id, ToolState.FAILED.name)
                    installLogRepository.insert(
                        InstallLogEntity(
                            distroId = distroId,
                            toolId = manifest.id,
                            event = "RECOVERED",
                            message = "检测到上次安装被中断，请重试",
                        ),
                    )
                }
            }
        }
        // 清理已废弃/移除的独立在线插件（如 claude-code、openclaw、hermes-agent）
        toolRepository.deleteByIds(ToolRegistry.REMOVED_TOOL_IDS)
        installTransactionManager.cleanupOrphans(liveInstallTools)
    }

    /** Install and update share the same transactional adapter path. */
    fun install(toolId: String): Flow<InstallEvent> = installInternal(toolId, OPERATION_INSTALL)

    /** Probe component installation in the phone's Linux runtime. */
    suspend fun probeInstalledComponents(): Set<String> {
        val distroId = currentDistroId()
        val allComponents = BuiltinPluginBundles.bundles.flatMap { it.components }
        val installed = mutableSetOf<String>()
        allComponents.forEach { comp ->
            val result = linuxRuntime.execute(
                ShellCommand(
                    commandLine = comp.checkCommand,
                    workingDirectory = "/root",
                    timeoutMs = 5000L,
                ),
                distroId = distroId,
            )
            if (result.isSuccess) {
                installed.add(comp.id)
            }
        }
        return installed
    }

    fun batchInstallComponents(componentIds: Set<String>, reinstall: Boolean = false): Flow<InstallEvent> =
        bundleBatch.install(componentIds, reinstall)
    suspend fun <T> runExclusive(block: suspend () -> T): T = installMutex.withLock { localAccess?.requireLocal(); block() }
    /** 卸载选中组件。仍被其他已装配组件使用的目录和软件包会保留。 */
    fun batchUninstallComponents(componentIds: Set<String>): Flow<InstallEvent> =
        bundleBatch.uninstall(componentIds)

    fun startBackgroundBatchInstall(
        componentIds: Set<String>,
        reinstall: Boolean = false,
        onCompleted: (() -> Unit)? = null,
    ): Job = bundleBatch.startInstall(componentIds, reinstall, onCompleted)

    fun startBackgroundBatchUninstall(
        componentIds: Set<String>,
        onCompleted: (() -> Unit)? = null,
    ): Job = bundleBatch.startUninstall(componentIds, onCompleted)

    fun batchInstallSuites(suiteIds: Set<String>): Flow<InstallEvent> {
        val componentIds = BuiltinPluginBundles.bundles
            .filter { it.id in suiteIds }
            .flatMap { it.components }
            .map { it.id }
            .toSet()
        return batchInstallComponents(if (componentIds.isEmpty()) suiteIds else componentIds)
    }

    private fun installInternal(toolId: String, operation: String, targetDistro: String? = null, expected: ToolManifest? = null): Flow<InstallEvent> = flow {
        localAccess?.requireLocal()
        val distro = targetDistro ?: currentDistroId()
        try {
            packageOperations.install(distro, toolId, expected) { fixed ->
                emitAll(installRecipe(toolId, operation, distro, fixed, checkNotNull(getAdapter(toolId, fixed, distro))))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val event = InstallEvent.Failed(toolId, secretRedactor.redact(failure.message ?: "插件安装检查失败"))
            updateFromEvent(event)
            emit(event)
        }
    }

    private fun installRecipe(toolId: String, operation: String, distroId: String, fixed: ToolManifest, adapter: ToolRuntimeAdapter): Flow<InstallEvent> = flow {
        localAccess?.requireLocal()
        require(isToolSupported(toolId)) { "暂不支持安装工具：$toolId" }
        requireManifestEnabled(toolId)
        val currentJob = coroutineContext[Job]
            ?: error("安装任务必须运行在协程中")
        val previousTool = toolRepository.findById(distroId, toolId)
        val preservePreviousInstall = previousTool?.installedVersion != null
        installMutex.withLock {
            check(toolId !in installJobs) { "工具正在安装：$toolId" }
            installJobs[toolId] = currentJob
            installLogRepository.deleteForTool(distroId, toolId)
            updateProgress(ToolInstallProgress(toolId, "准备安装", 0f))
            toolRepository.updateState(distroId, toolId, ToolState.INSTALLING.name)
            installTaskRepository.upsert(
                InstallTaskEntity(
                    distroId = distroId,
                    toolId = toolId,
                    operation = if (preservePreviousInstall) OPERATION_UPDATE else operation,
                    state = TASK_RUNNING,
                    message = "任务开始",
                ),
            )
        }

        val toolName = previousTool?.name ?: toolRepository.findById(distroId, toolId)?.name ?: toolId
        var cancelled = false
        var transaction: InstallTransaction? = null
        try {
            if (preservePreviousInstall) {
                linuxRuntime.listBackground()
                    .filter { it.toolId == toolId }
                    .forEach { linuxRuntime.stopBackground(it.id) }
            }
            // A snapshot does not require a healthy old installation. Repair and upgrades
            // run the candidate recipe first, then its own post-install verification.
            transaction = installTransactionManager.begin(distroId, toolId, preservePreviousInstall)
            adapter.install().collect { event ->
                val safeEvent = event.redacted(secretRedactor)
                recordEvent(distroId, safeEvent)
                updateFromEvent(safeEvent)
                when (safeEvent) {
                    is InstallEvent.Progress -> {
                        notificationNotifier.showProgress(toolId, toolName, safeEvent.message, safeEvent.progress)
                    }
                    is InstallEvent.Completed -> Unit
                    is InstallEvent.Failed -> {
                        transaction?.let { installTransactionManager.rollback(it) }
                        transaction = null
                        toolRepository.updateStateAndInstalledVersion(
                            distroId = distroId,
                            id = toolId,
                            state = failureState(preservePreviousInstall, previousTool),
                            installedVersion = if (preservePreviousInstall) previousTool.installedVersion else null,
                        )
                        if (!preservePreviousInstall) releaseRuntimeReferences(toolId, distroId)
                        notificationNotifier.showFailed(toolId, toolName, safeEvent.message)
                    }
                    is InstallEvent.Cancelled -> {
                        notificationNotifier.cancel(toolId)
                    }
                    else -> Unit
                }
                when (safeEvent) {
                    is InstallEvent.Failed -> updateTask(distroId, toolId, TASK_FAILED, safeEvent.message)
                    else -> Unit
                }
                if (safeEvent is InstallEvent.Completed) {
                    // 顺序关键：先 commit（删快照 = 磁盘落定），再把 INSTALLED + 新版本写入 DB。
                    // 若反过来（先落库再 commit），进程在窗口期被杀时恢复侧会用旧快照覆盖磁盘，
                    // 而 DB 已记新版本且状态 INSTALLED——磁盘回旧版却永不提示更新，无法自愈。
                    // commit 后落库则任一时刻被杀都可恢复：快照在 → 按旧快照回滚、DB 版本号仍是
                    // 旧值（一致）；快照已删 → 磁盘即新版，最坏多提示一次更新（无害）。
                    val receiptVersion = safeEvent.version?.trim()?.takeIf { it.isNotBlank() } ?: fixed.version
                    packageOperations.record(distroId, fixed, receiptVersion)
                    transaction?.let { installTransactionManager.commit(it) }
                    transaction = null
                    val installedVer = safeEvent.version?.trim()?.takeIf { it.isNotBlank() }
                        ?: fixed.version
                    toolRepository.updateStateAndInstalledVersion(
                        distroId = distroId,
                        id = toolId,
                        state = ToolState.INSTALLED.name,
                        installedVersion = installedVer,
                    )
                    notificationNotifier.showSuccess(toolId, toolName, installedVer)
                    updateTask(distroId, toolId, TASK_COMPLETED, "安装完成")
                }
                emit(safeEvent)
            }
        } catch (throwable: CancellationException) {
            cancelled = true
            notificationNotifier.cancel(toolId)
            withContext(NonCancellable) {
                transaction?.let { tx ->
                    try {
                        installTransactionManager.rollback(tx)
                    } finally {
                        transaction = null
                    }
                }
                toolRepository.updateStateAndInstalledVersion(
                    distroId = distroId,
                    id = toolId,
                    state = failureState(preservePreviousInstall, previousTool, cancelled = true),
                    installedVersion = if (preservePreviousInstall) previousTool.installedVersion else null,
                )
                updateTask(distroId, toolId, TASK_CANCELLED, "用户取消安装")
                if (!preservePreviousInstall) releaseRuntimeReferences(toolId, distroId)
                val event = InstallEvent.Cancelled(toolId)
                recordEvent(distroId, event)
                updateFromEvent(event)
            }
            throw throwable
        } catch (throwable: Throwable) {
            val event = InstallEvent.Failed(
                toolId,
                secretRedactor.redact(throwable.message ?: "安装流程异常终止"),
            )
            notificationNotifier.showFailed(toolId, toolName, event.message)
            withContext(NonCancellable) {
                transaction?.let { tx ->
                    try {
                        installTransactionManager.rollback(tx)
                    } finally {
                        transaction = null
                    }
                }
                toolRepository.updateStateAndInstalledVersion(
                    distroId = distroId,
                    id = toolId,
                    state = failureState(preservePreviousInstall, previousTool),
                    installedVersion = if (preservePreviousInstall) previousTool.installedVersion else null,
                )
                if (!preservePreviousInstall) releaseRuntimeReferences(toolId, distroId)
                updateTask(distroId, toolId, TASK_FAILED, event.message)
                recordEvent(distroId, event)
                updateFromEvent(event)
            }
            emit(event)
        } finally {
            withContext(NonCancellable) {
                installMutex.withLock {
                    if (installJobs[toolId] === currentJob) installJobs.remove(toolId)
                }
                if (!cancelled && _installProgress.value[toolId]?.terminal != true) {
                    // An adapter that ended without Completed/Failed is not a successful install.
                    transaction?.let { tx ->
                        try {
                            installTransactionManager.rollback(tx)
                        } finally {
                            transaction = null
                        }
                    }
                    toolRepository.updateStateAndInstalledVersion(
                        distroId = distroId,
                        id = toolId,
                        state = failureState(preservePreviousInstall, previousTool),
                        installedVersion = if (preservePreviousInstall) previousTool.installedVersion else null,
                    )
                    if (!preservePreviousInstall) releaseRuntimeReferences(toolId, distroId)
                    val event = InstallEvent.Failed(toolId, "安装流程未完成")
                    updateTask(distroId, toolId, TASK_FAILED, event.message)
                    recordEvent(distroId, event)
                    updateFromEvent(event)
                }
            }
        }
    }

    fun update(toolId: String): Flow<InstallEvent> = installInternal(toolId, OPERATION_UPDATE)

    fun cancelInstall(toolId: String) {
        installJobs[toolId]?.cancel(CancellationException("用户取消安装"))
    }

    suspend fun uninstall(toolId: String, deleteData: Boolean = false) {
        localAccess?.requireLocal()
        val distro = currentDistroId()
        packageOperations.uninstall(distro, toolId) { fixed ->
            require(isToolSupported(toolId)) { "暂不支持卸载工具：$toolId" }
            installMutex.withLock {
                check(toolId !in installJobs) { "工具正在安装：$toolId" }
            }
            linuxRuntime.listBackground()
                .filter { it.toolId == toolId }
                .forEach { linuxRuntime.stopBackground(it.id) }
            uninstallLocked(toolId, deleteData, distro, fixed)
        }
    }

    private suspend fun uninstallLocked(toolId: String, deleteData: Boolean, distroId: String, fixed: ToolManifest) {
        installTaskRepository.upsert(
            InstallTaskEntity(
                distroId = distroId,
                toolId = toolId,
                operation = OPERATION_UNINSTALL,
                state = TASK_RUNNING,
                message = "卸载任务开始",
            ),
        )
        try {
            val outcome = checkNotNull(getAdapter(toolId, fixed, distroId)).uninstall(deleteData).toUninstallOutcome()
            val event = if (outcome.success) "UNINSTALLED" else "UNINSTALL_FAILED"
            val safeMessage = secretRedactor.redact(outcome.message.ifBlank { "卸载完成" })
            toolRepository.updateStateAndInstalledVersion(
                distroId = distroId,
                id = toolId,
                state = if (outcome.success) ToolState.AVAILABLE.name else ToolState.FAILED.name,
                installedVersion = if (outcome.success) null else toolRepository.findById(distroId, toolId)?.installedVersion,
            )
            if (outcome.success) {
                releaseRuntimeReferences(toolId, distroId)
                toolSettingsRepository.delete(distroId, toolId)
                settingsDataStore.setToolAccessToken(distroId, toolId, null)
            }
            updateTask(distroId, toolId, if (outcome.success) TASK_COMPLETED else TASK_FAILED, safeMessage)
            installLogRepository.insert(InstallLogEntity(distroId = distroId, toolId = toolId, event = event, message = safeMessage))
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) { updateTask(distroId, toolId, TASK_CANCELLED, "用户取消卸载") }
            throw cancellation
        } catch (throwable: Throwable) {
            val message = secretRedactor.redact(throwable.message ?: "卸载流程异常终止")
            updateTask(distroId, toolId, TASK_FAILED, message)
            throw throwable
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeInstallLogs(toolId: String): Flow<List<InstallLogEntity>> =
        linuxRuntime.activeDistroId.flatMapLatest { distroId ->
            installLogRepository.observeForTool(distroId, toolId)
        }

    suspend fun clearLogs(toolId: String) =
        installLogRepository.deleteForTool(currentDistroId(), toolId)

    /** Drop persisted tool/install state after the corresponding distro is reset. */
    suspend fun resetDistroState(distroId: String) {
        toolRepository.getForDistro(distroId).forEach { tool ->
            settingsDataStore.setToolAccessToken(distroId, tool.id, null)
        }
        toolRepository.deleteByDistro(distroId)
        installTaskRepository.deleteByDistro(distroId)
        installLogRepository.deleteByDistro(distroId)
        toolSettingsRepository.deleteByDistro(distroId)
        _installProgress.value = emptyMap()
        _verifications.value = emptyMap()
    }

    suspend fun launch(toolId: String): CommandResult {
        requireInstalledTool(toolId)
        return requireAdapter(toolId).launch()
    }

    suspend fun startSession(toolId: String?, workingDirectory: String = "/root"): LinuxSession {
        val config = if (toolId.isNullOrBlank()) {
            null
        } else {
            requireInstalledTool(toolId)
            requireAdapter(toolId).interactiveSessionConfig()
        }
        return if (config == null) {
            linuxRuntime.startSession(SessionConfig(workingDirectory = workingDirectory))
        } else {
            linuxRuntime.startSession(config.copy(workingDirectory = workingDirectory))
        }
    }

    suspend fun verify(toolId: String): ToolVerification {
        require(isToolSupported(toolId)) { "暂不支持验证工具：$toolId" }
        requireManifestEnabled(toolId)
        val result = requireAdapter(toolId).verify()
        val safeStdout = secretRedactor.redact(result.stdout)
        val safeStderr = secretRedactor.redact(result.stderr)
        val verification = ToolVerification(
            toolId = toolId,
            healthy = result.isSuccess,
            version = safeStdout.trim().lineSequence().firstOrNull()?.takeIf { it.isNotBlank() },
            detail = if (result.isSuccess) {
                safeStdout.trim().ifBlank { "命令执行成功" }
            } else {
                safeStderr.ifBlank { safeStdout }.trim().ifBlank { "命令退出码 ${result.exitCode}" }
            },
        )
        _verifications.value = _verifications.value + (toolId to verification)
        installLogRepository.insert(
            InstallLogEntity(
                distroId = currentDistroId(),
                toolId = toolId,
                event = if (verification.healthy) "VERIFIED" else "VERIFY_FAILED",
                message = verification.detail,
            ),
        )
        if (verification.healthy) {
            val distroId = currentDistroId()
            val current = toolRepository.findById(distroId, toolId)
            toolRepository.updateStateAndInstalledVersion(
                distroId = distroId,
                id = toolId,
                state = ToolState.INSTALLED.name,
                installedVersion = current?.installedVersion
                    ?: current?.manifestVersion
                    ?: verification.version,
            )
        } else {
            toolRepository.updateState(currentDistroId(), toolId, ToolState.FAILED.name)
        }
        return verification
    }

    suspend fun startGateway(toolId: String): ManagedProcess {
        localAccess?.requireLocal()
        requireInstalledTool(toolId)
        return serviceController.start(toolId, requireAdapter(toolId), serviceSpec(toolId))
    }

    private suspend fun requireInstalledTool(toolId: String): ToolEntity {
        val tool = toolRepository.findById(currentDistroId(), toolId)
            ?: error("工具不在当前清单中：$toolId")
        check(tool.state == ToolState.INSTALLED.name || tool.state == ToolState.UPDATE_AVAILABLE.name) {
            "工具尚未安装：${tool.name}"
        }
        return tool
    }

    private suspend fun requireAdapter(
        toolId: String,
        requireEnabled: Boolean = true,
    ): ToolRuntimeAdapter {
        val distro = currentDistroId()
        if (requireEnabled) requireManifestEnabled(toolId)
        return checkNotNull(getAdapter(toolId, packageOperations.runtimeManifest(distro, toolId), distro)) { "暂不支持工具：$toolId" }
    }

    private fun requireManifestEnabled(toolId: String) {
        val manifest = toolRepository.manifest(toolId)
            ?: error("工具不在当前清单中：$toolId")
        check(manifest.enabled) { "工具已被 Registry 暂停：${manifest.name}" }
    }

    private suspend fun recordEvent(distroId: String, event: InstallEvent) {
        installLogRepository.insert(event.toLog(distroId))
    }

    private suspend fun updateTask(distroId: String, toolId: String, state: String, message: String) {
        val current = installTaskRepository.findByTool(distroId, toolId) ?: return
        installTaskRepository.upsert(
            current.copy(
                state = state,
                message = secretRedactor.redact(message),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun updateFromEvent(event: InstallEvent) {
        val state = when (event) {
            is InstallEvent.Started -> ToolInstallProgress(event.toolId, "开始安装", 0f)
            is InstallEvent.Progress -> ToolInstallProgress(event.toolId, event.message, event.progress)
            is InstallEvent.Output -> ToolInstallProgress(event.toolId, event.line)
            is InstallEvent.Completed -> ToolInstallProgress(event.toolId, "安装完成", 1f, terminal = true)
            is InstallEvent.Failed -> ToolInstallProgress(event.toolId, event.message, terminal = true)
            is InstallEvent.RolledBack -> ToolInstallProgress(event.toolId, "安装失败，正在回滚")
            is InstallEvent.Cancelled -> ToolInstallProgress(event.toolId, "已取消安装", terminal = true)
        }
        updateProgress(state)
    }

    private fun updateProgress(progress: ToolInstallProgress) {
        _installProgress.value = _installProgress.value + (progress.toolId to progress)
    }

    private fun failureState(
        preservePreviousInstall: Boolean,
        previousTool: ToolEntity?,
        cancelled: Boolean = false,
    ): String = when {
        preservePreviousInstall -> previousTool?.state ?: ToolState.INSTALLED.name
        cancelled -> ToolState.AVAILABLE.name
        else -> ToolState.FAILED.name
    }

    private suspend fun releaseRuntimeReferences(toolId: String, distroId: String) {
        val dependencies = toolRepository.findById(distroId, toolId)?.dependencies.orEmpty()
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
        dependencies.forEach { dependency ->
            val runtimeName = when (ManifestDependencyParser.parse(dependency)?.name) {
                "node" -> "node"
                "python" -> "python"
                "git" -> "git"
                "ca-certificates" -> "ca_certificates"
                "curl" -> "curl"
                else -> null
            }
            runtimeName?.let { dependencyManager.release(it, toolId) }
        }
    }

    private companion object {
        const val OPERATION_INSTALL = "INSTALL"
        const val OPERATION_UPDATE = "UPDATE"
        const val OPERATION_UNINSTALL = "UNINSTALL"
        const val TASK_RUNNING = "RUNNING"
        const val TASK_COMPLETED = "COMPLETED"
        const val TASK_FAILED = "FAILED"
        const val TASK_CANCELLED = "CANCELLED"
        const val TASK_INTERRUPTED = "INTERRUPTED"
    }

}
