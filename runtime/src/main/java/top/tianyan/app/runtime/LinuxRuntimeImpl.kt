package top.tianyan.app.runtime

import android.os.Build
import android.os.StatFs
import top.tianyan.app.runtime.environment.boundLocalDistribution
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.common.result.AppError
import top.tianyan.app.core.common.result.AppResult
import top.tianyan.app.core.common.result.ErrorCode
import top.tianyan.app.core.database.StorageMountBindingRepository
import top.tianyan.app.core.datastore.RuntimePreferences
import top.tianyan.app.core.model.CpuArch
import top.tianyan.app.core.model.InstalledDistro
import top.tianyan.app.core.model.RuntimeState
import top.tianyan.app.core.model.StorageMountBinding
import top.tianyan.app.runtime.backend.BackendSelector
import top.tianyan.app.runtime.proot.QemuCompatibilityLayout
import top.tianyan.app.runtime.proot.hostSupplementaryGroupIds
import top.tianyan.app.runtime.proot.syncGuestGroups
import top.tianyan.app.runtime.bridge.HostBridge
import top.tianyan.app.runtime.bridge.SandboxCredentialStore
import top.tianyan.app.runtime.pty.PtyManager
import top.tianyan.app.runtime.proot.ProotInstaller
import top.tianyan.app.runtime.rootfs.RootfsInstaller
import top.tianyan.app.runtime.terminal.terminalBanner
import top.tianyan.app.runtime.shell.CommandResult
import top.tianyan.app.runtime.shell.InteractiveLaunchSpec
import top.tianyan.app.runtime.shell.LinuxSession
import top.tianyan.app.runtime.shell.ManagedProcess
import top.tianyan.app.runtime.shell.ProcessType
import top.tianyan.app.runtime.shell.ProcessRegistry
import top.tianyan.app.runtime.shell.SessionConfig
import top.tianyan.app.runtime.shell.ShellCommand
import top.tianyan.app.runtime.shell.ShellExecutor
import top.tianyan.app.runtime.shell.ForegroundCommandAdmission
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LinuxRuntimeImpl(
    private val pathManager: RuntimePathManager,
    private val prootInstaller: ProotInstaller,
    private val rootfsInstaller: RootfsInstaller,
    private val backendSelector: BackendSelector,
    private val ptyManager: PtyManager,
    private val shellExecutor: ShellExecutor,
    private val healthChecker: RuntimeHealthChecker,
    private val processRegistry: ProcessRegistry,
    private val settingsDataStore: RuntimePreferences,
    private val storageMountBindingRepository: StorageMountBindingRepository,
    private val hostBridge: HostBridge,
    private val sandboxCredentials: SandboxCredentialStore,
    private val distroConfigurator: DistroConfigurator,
    private val logger: AppLogger,
) : LinuxRuntime {

    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.NotInitialized)
    override val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private val _activeDistroId = MutableStateFlow("ubuntu")
    override val activeDistroId: StateFlow<String> = _activeDistroId.asStateFlow()

    private val _installedDistros = MutableStateFlow<List<InstalledDistro>>(emptyList())
    override val installedDistros: StateFlow<List<InstalledDistro>> = _installedDistros.asStateFlow()

    private val initializeMutex = Mutex()
    private val storageActivities = StorageActivityGate()
    private val interactiveSessions = InteractiveSessionRegistry()
    private val commandAdmission = ForegroundCommandAdmission()
    override fun refreshInstalledDistros() {
        pathManager.invalidateInstalledDistrosCache()
        val ids = pathManager.listInstalledDistroIds()
        val active = _activeDistroId.value
        val list = ids.map { id ->
            val spec = DistributionCatalog.require(id)
            val size = pathManager.distroSizeBytes(id)
            val marker = pathManager.rootfsInstalledMarker(id)
            val installedTime = if (marker.exists()) marker.lastModified() else System.currentTimeMillis()
            InstalledDistro(
                id = id,
                displayName = spec.displayName,
                sizeBytes = size,
                installedAt = installedTime,
                isActive = id.equals(active, ignoreCase = true),
                packageManager = when (id.lowercase()) {
                    "alpine" -> "apk"
                    "arch" -> "pacman"
                    "fedora", "almalinux" -> "dnf"
                    "opensuse" -> "zypper"
                    else -> "apt"
                },
                statusText = if (id.equals(active, ignoreCase = true)) "当前主系统" else "已就绪",
            )
        }
        _installedDistros.value = list
    }

    override suspend fun switchActiveDistro(distroId: String): AppResult<Unit> = initializeMutex.withLock {
        val safeId = distroId.lowercase().trim()
        if (!pathManager.isDistroInstalled(safeId)) {
            return@withLock AppResult.Failure(
                AppError(ErrorCode.RUNTIME_NOT_INITIALIZED, "系统未安装或文件不完整：$safeId"),
            )
        }
        pathManager.ensureDistroDirectories(safeId)
        _activeDistroId.value = safeId
        settingsDataStore.setSelectedDistribution(safeId)
        refreshInstalledDistros()
        logger.i("Switched active Linux distro to $safeId")
        AppResult.Success(Unit)
    }

    override suspend fun installDistro(
        request: RuntimeInstallRequest,
        onProgress: suspend (DownloadProgress) -> Unit,
    ): AppResult<Unit> = initializeMutex.withLock {
        withContext(Dispatchers.IO) {
            val distroId = request.distributionId.lowercase().trim()
            val distribution = DistributionCatalog.require(distroId)
            logger.i("Installing new distro: ${distribution.displayName} ($distroId)")
            val result = rootfsInstaller.installOci(distribution, request.registryRoute, onProgress)
            val error = result.errorOrNull()
            if (error != null) {
                return@withContext AppResult.Failure(error)
            }
            distroConfigurator.configureRootfs(distroId)
            distroConfigurator.configureDns(distroId)
            distroConfigurator.configureEnvironment(distroId)
            refreshInstalledDistros()
            logger.i("Distro $distroId installed successfully")
            AppResult.Success(Unit)
        }
    }

    override suspend fun importDistro(
        request: RuntimeInstallRequest,
        archive: File,
    ): AppResult<Unit> = initializeMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                updateInitializing("校验设备环境", 0.05f)
                val architecture = detectArchitecture()
                if (architecture != CpuArch.ARM64) {
                    val error = AppError(ErrorCode.UNSUPPORTED_ARCHITECTURE, "手动导入仅支持 ARM64 RootFS")
                    failInitialization(error)
                    return@withContext AppResult.Failure(error)
                }
                checkStorage()
                updateInitializing("导入 Linux RootFS", 0.2f, archive.name)
                pathManager.ensureDirectories()
                val prootResult = prootInstaller.install()
                prootResult.errorOrNull()?.let { error ->
                    failInitialization(error)
                    return@withContext AppResult.Failure(error)
                }
                val distroId = request.distributionId.lowercase().trim()
                val result = rootfsInstaller.importArchive(distroId, archive)
                result.errorOrNull()?.let { error ->
                    failInitialization(error)
                    return@withContext AppResult.Failure(error)
                }
                updateInitializing("配置 Linux 系统", 0.65f)
                distroConfigurator.configureRootfs(distroId)
                distroConfigurator.configureDns(distroId)
                distroConfigurator.configureEnvironment(distroId)
                createWorkspace()
                val health = healthChecker.check()
                if (!health.isHealthy) {
                    val error = AppError(ErrorCode.INSTALLATION_FAILED, "导入后的 Linux 环境健康检查失败：${health.detail.orEmpty()}")
                    failInitialization(error)
                    return@withContext AppResult.Failure(error)
                }
                _activeDistroId.value = distroId
                settingsDataStore.setSelectedDistribution(distroId)
                refreshInstalledDistros()
                hostBridge.start()
                _state.value = RuntimeState.Ready
                AppResult.Success(Unit)
            } catch (cancellation: CancellationException) {
                _state.value = RuntimeState.NotInitialized
                throw cancellation
            } catch (throwable: Throwable) {
                failInitialization(AppError(ErrorCode.INSTALLATION_FAILED, throwable.message ?: "导入失败", throwable))
                AppResult.Failure(AppError(ErrorCode.INSTALLATION_FAILED, throwable.message ?: "导入失败", throwable))
            }
        }
    }

    override suspend fun uninstallDistro(distroId: String): AppResult<Unit> = initializeMutex.withLock {
        withRuntimeMaintenance {
            val safeId = distroId.lowercase().trim()
            val installed = pathManager.listInstalledDistroIds()
            if (installed.size <= 1) {
                return@withRuntimeMaintenance AppResult.Failure(
                    AppError(ErrorCode.INSTALLATION_FAILED, "不能卸载唯一的 Linux 系统，请先安装其他系统后再卸载"),
                )
            }
            closeRuntimeProcesses(safeId)
            sandboxCredentials.revoke(safeId)
            val wasActive = _activeDistroId.value == safeId
            if (wasActive) {
                hostBridge.stop()
            }
            val res = rootfsInstaller.uninstallDistro(safeId)
            if (res is AppResult.Success) {
                if (wasActive) {
                    val nextDistro = installed.firstOrNull { it != safeId } ?: "ubuntu"
                    _activeDistroId.value = nextDistro
                    settingsDataStore.setSelectedDistribution(nextDistro)
                }
                refreshInstalledDistros()
            }
            if (wasActive && _state.value is RuntimeState.Ready && !hostBridge.isRunning.value) {
                hostBridge.start()
            }
            res
        }
    }

    override suspend fun resetSandbox(distroId: String?): AppResult<Unit> = initializeMutex.withLock {
        withRuntimeMaintenance {
            val safeId = (distroId ?: _activeDistroId.value).lowercase().trim()
            val wasActive = _activeDistroId.value == safeId
            try {
                closeRuntimeProcesses(safeId)
                sandboxCredentials.revoke(safeId)
                if (wasActive) {
                    hostBridge.stop()
                }
                val distribution = DistributionCatalog.require(safeId)
                val result = rootfsInstaller.resetDistro(distribution)
                if (result is AppResult.Success) {
                    distroConfigurator.configureRootfs(safeId)
                    distroConfigurator.configureDns(safeId)
                    distroConfigurator.configureEnvironment(safeId)
                    refreshInstalledDistros()
                    if (wasActive) {
                        if (!hostBridge.isRunning.value) {
                            hostBridge.start()
                        }
                        _state.value = RuntimeState.Ready
                    }
                    logger.i("Reset sandbox distro $safeId successfully")
                    AppResult.Success(Unit)
                } else {
                    val error = result.errorOrNull()
                    if (wasActive) {
                        _state.value = RuntimeState.Error(
                            error?.cause ?: IllegalStateException(error?.message ?: "Linux 环境重置失败"),
                        )
                    }
                    AppResult.Failure(error ?: AppError(ErrorCode.INSTALLATION_FAILED, "重置 Linux 环境失败"))
                }
            } catch (throwable: Throwable) {
                logger.e("Failed to reset sandbox distro $safeId", throwable)
                AppResult.Failure(AppError(ErrorCode.IO, "重置 Linux 环境失败：${throwable.message}", throwable))
            }
        }
    }

    override suspend fun initialize(request: RuntimeInstallRequest): AppResult<Unit> = initializeMutex.withLock {
        withContext(Dispatchers.IO) {
            if (_state.value is RuntimeState.Ready) {
                return@withContext AppResult.Success(Unit)
            }

            try {
                updateInitializing("detectArchitecture", 0f)
                val architecture = detectArchitecture()
                if (architecture != CpuArch.ARM64) {
                    val message = "Unsupported CPU architecture: $architecture. Phase 1 supports ARM64 only."
                    logger.e(message)
                    val error = AppError(ErrorCode.UNSUPPORTED_ARCHITECTURE, message)
                    _state.value = RuntimeState.Error(RuntimeArchitectureException(message))
                    return@withContext AppResult.Failure(error)
                }

                updateInitializing("checkStorage", 0.05f)
                checkStorage()

                updateInitializing("createDirectories", 0.1f)
                pathManager.ensureDirectories()
                pathManager.cleanupStalePtyMarkers(request.distributionId)

                updateInitializing("校验运行引擎", 0.15f, "校验 PRoot 主程序、外置 loader 与 ARM64 架构")
                val prootResult = prootInstaller.install()
                val prootError = prootResult.errorOrNull()
                if (prootError != null) {
                    failInitialization(prootError)
                    return@withContext AppResult.Failure(prootError)
                }

                val distroId = request.distributionId.lowercase().trim()
                val distribution = DistributionCatalog.require(distroId)
                updateInitializing("下载 ${distribution.displayName}", 0.2f, "通过 proot-distro 5.10.0 OCI 机制下载 linux/arm64 镜像")
                val rootfsResult = rootfsInstaller.installOci(
                    distribution,
                    request.registryRoute,
                ) { progress -> updateDownloadProgress("下载 ${distribution.displayName}", 0.2f, 0.55f, progress) }
                val rootfsError = rootfsResult.errorOrNull()
                if (rootfsError != null) {
                    failInitialization(rootfsError)
                    return@withContext AppResult.Failure(rootfsError)
                }

                updateInitializing("configureRootfs", 0.75f)
                distroConfigurator.configureRootfs(distroId)

                updateInitializing("configureDns", 0.8f)
                distroConfigurator.configureDns(distroId)

                updateInitializing("configureEnvironment", 0.85f)
                distroConfigurator.configureEnvironment(distroId)

                updateInitializing("createWorkspace", 0.9f)
                createWorkspace()

                updateInitializing("runHealthCheck", 0.95f)
                val health = healthChecker.check()
                if (!health.isHealthy) {
                    val message = "Runtime health check failed after initialization: ${health.detail.orEmpty()}"
                    logger.e(message)
                    val error = AppError(ErrorCode.INSTALLATION_FAILED, message)
                    failInitialization(error)
                    return@withContext AppResult.Failure(error)
                }

                _activeDistroId.value = distroId
                settingsDataStore.setSelectedDistribution(distroId)
                refreshInstalledDistros()

                hostBridge.start()
                _state.value = RuntimeState.Ready
                logger.i("Linux runtime initialized and ready with distro: $distroId")
                AppResult.Success(Unit)
            } catch (cancellation: CancellationException) {
                _state.value = RuntimeState.NotInitialized
                throw cancellation
            } catch (throwable: Throwable) {
                logger.e("Linux runtime initialization failed", throwable)
                _state.value = RuntimeState.Error(throwable)
                AppResult.Failure(
                    AppError(
                        code = when (throwable) {
                            is InsufficientStorageException -> ErrorCode.INSUFFICIENT_STORAGE
                            else -> ErrorCode.INSTALLATION_FAILED
                        },
                        message = throwable.message ?: "Linux runtime initialization failed",
                        cause = throwable,
                    ),
                )
            }
        }
    }

    override suspend fun restoreInstalledState(): Boolean = initializeMutex.withLock {
        withContext(Dispatchers.IO) {
        if (_state.value is RuntimeState.Ready && hostBridge.isRunning.value) {
            return@withContext true
        }
        val selectedDistroId = runCatching { settingsDataStore.selectedDistribution.first() }.getOrDefault("ubuntu")

        if (!pathManager.isRootfsInstalled()) {
            logger.i("Restore skipped: rootfs marker or validator check failed (fresh install?)")
            return@withContext false
        }
        if (!pathManager.isProotInstalled()) {
            val message = "已安装的 Linux 环境无法恢复：PRoot 运行组件不完整（APK 内 native 库缺失或不可读）"
            logger.e("Restore failed: $message")
            _state.value = RuntimeState.Error(IllegalStateException(message))
            return@withContext false
        }

        val installedList = pathManager.listInstalledDistroIds()
        val effectiveDistro = if (selectedDistroId in installedList) selectedDistroId else installedList.firstOrNull() ?: selectedDistroId
        pathManager.ensureDistroDirectories(effectiveDistro)
        _activeDistroId.value = effectiveDistro
        refreshInstalledDistros()

        val health = try {
            healthChecker.check()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            logger.e("Restore failed: health check threw", throwable)
            _state.value = RuntimeState.Error(throwable)
            return@withContext false
        }
        if (!health.isHealthy) {
            val message = "已安装的 Linux 环境健康检查未通过：${health.detail.orEmpty()}"
            logger.e("Restore failed: $message")
            _state.value = RuntimeState.Error(IllegalStateException(message))
            return@withContext false
        }
        runCatching { distroConfigurator.configureChinaMirrors(effectiveDistro) }
        runCatching { distroConfigurator.configureEnvironment(effectiveDistro) }
        // APK 升级可能新增或替换内置 MCP/构建脚本。已有 rootfs 不会再走 configureRootfs，
        // 因此恢复时必须幂等同步一次，避免数据库预设已切换到新脚本而沙箱内文件仍是旧版本。
        runCatching { distroConfigurator.syncAssets(effectiveDistro) }
            .onFailure { logger.w("Restore asset synchronization failed for $effectiveDistro: ${it.message}", it) }
        hostBridge.start()
        _state.value = RuntimeState.Ready
        logger.i("Linux runtime restored from disk and ready with distro: $effectiveDistro")
        true
        }
    }

    override suspend fun updateRootfs(distroId: String?): AppResult<Unit> = initializeMutex.withLock {
        withRuntimeMaintenance {
            if (_state.value !is RuntimeState.Ready) {
                return@withRuntimeMaintenance AppResult.Failure(
                    AppError(ErrorCode.INSTALLATION_FAILED, "只有已就绪的 Linux Runtime 才能更新"),
                )
            }
            try {
                val targetDistro = distroId?.lowercase()?.trim() ?: _activeDistroId.value
                closeRuntimeProcesses(targetDistro)
                updateInitializing("更新 RootFS", 0.05f, "保留 /root 和 /opt/tianyan 用户数据")
                val distribution = DistributionCatalog.require(targetDistro)
                val result = rootfsInstaller.updateOci(
                    distribution,
                    RegistryRoute.AUTO,
                ) { progress -> updateDownloadProgress("更新 ${distribution.displayName}", 0.1f, 0.65f, progress) }
                result.errorOrNull()?.let { error ->
                    _state.value = RuntimeState.Error(error.cause ?: IllegalStateException(error.message))
                    return@withRuntimeMaintenance AppResult.Failure(error)
                }

                updateInitializing("configureRootfs", 0.75f)
                distroConfigurator.configureRootfs(targetDistro)
                distroConfigurator.configureDns(targetDistro)
                distroConfigurator.configureEnvironment(targetDistro)
                updateInitializing("runHealthCheck", 0.9f)
                val health = healthChecker.check()
                if (!health.isHealthy) {
                    rootfsInstaller.rollbackPendingUpdate(targetDistro)
                    _state.value = RuntimeState.Error(
                        IllegalStateException("更新后健康检查失败：${health.detail.orEmpty()}"),
                    )
                    return@withRuntimeMaintenance AppResult.Failure(
                        AppError(
                            ErrorCode.INSTALLATION_FAILED,
                            "RootFS 更新后的健康检查失败，已恢复旧版本",
                        ),
                    )
                }
                rootfsInstaller.finalizePendingUpdate(targetDistro)
                refreshInstalledDistros()
                _state.value = RuntimeState.Ready
                AppResult.Success(Unit)
            } catch (cancellation: CancellationException) {
                val targetDistro = distroId?.lowercase()?.trim() ?: _activeDistroId.value
                rootfsInstaller.rollbackPendingUpdate(targetDistro)
                _state.value = RuntimeState.Error(IllegalStateException("RootFS 更新已取消，旧版本已恢复"))
                throw cancellation
            } catch (throwable: Throwable) {
                val targetDistro = distroId?.lowercase()?.trim() ?: _activeDistroId.value
                rootfsInstaller.rollbackPendingUpdate(targetDistro)
                logger.e("Linux runtime update failed", throwable)
                _state.value = RuntimeState.Error(throwable)
                AppResult.Failure(
                    AppError(ErrorCode.INSTALLATION_FAILED, throwable.message ?: "RootFS 更新失败", throwable),
                )
            }
        }
    }

    override suspend fun checkRootfsUpdate(distroId: String?): AppResult<RootfsUpdateInfo> =
        withContext(Dispatchers.IO) {
            if (_state.value !is RuntimeState.Ready) {
                return@withContext AppResult.Failure(
                    AppError(ErrorCode.INSTALLATION_FAILED, "只有已就绪的 Linux Runtime 才能检查更新"),
                )
            }
            val targetDistro = distroId?.lowercase()?.trim() ?: _activeDistroId.value
            val distribution = DistributionCatalog.require(targetDistro)
            runCatching { rootfsInstaller.checkForUpdate(distribution, RegistryRoute.AUTO) }.fold(
                onSuccess = { AppResult.Success(it) },
                onFailure = {
                    logger.e("Linux runtime RootFS update check failed", it)
                    AppResult.Failure(AppError(ErrorCode.NETWORK, "RootFS 更新检查失败：${it.message}", it))
                },
            )
        }

    override suspend fun healthCheck(distroId: String?): RuntimeHealth = withContext(Dispatchers.IO) {
        healthChecker.check()
    }

    override suspend fun execute(command: ShellCommand, distroId: String?): CommandResult = storageActivities.activity {
        ensureReady()
        val safeDistro = boundLocalDistribution(distroId, _activeDistroId.value)
        val mounts = storageMounts()
        val execution = resolveExecutionLayout(safeDistro, command.useQemuCompatibility)
        commandAdmission.execute(command, shellExecutor) { shellExecutor.execute(
            command = backendSelector.active().build(
                prootBinary = pathManager.activeProotFile(),
                rootfsDir = execution.rootfsDir,
                workspaceDir = pathManager.workspaceDir,
                homeDir = pathManager.homeDir(safeDistro),
                optDir = execution.optDir,
                tmpDir = pathManager.tmpDir,
                attachmentsDir = pathManager.attachmentsDir,
                command = command,
                mounts = mounts,
                emulatorBinary = execution.emulatorBinary,
                hostCredential = sandboxCredentials.environment(safeDistro, command.pluginIdentity?.pluginId ?: "sandbox", command.pluginIdentity?.version ?: "sandbox"),
            ),
            environment = pathManager.hostProcessEnvironment(safeDistro, execution.rootfsDir),
            timeoutMs = command.timeoutMs,
            onOutput = command.onOutput,
        ) }
    }
    private suspend fun resolveExecutionLayout(
        distroId: String,
        useQemuCompatibility: Boolean,
    ): ExecutionLayout {
        if (!useQemuCompatibility) {
            return ExecutionLayout(pathManager.rootfsDir(distroId), null, pathManager.tianyanRootDir(distroId))
        }
        check(settingsDataStore.qemuCompatibilityEnabled.first()) {
            "QEMU 兼容模式未开启，请先在设置中打开兼容开关"
        }
        val tianyanRoot = pathManager.tianyanRootDir(distroId)
        check(QemuCompatibilityLayout.isReady(tianyanRoot)) {
            "QEMU 兼容环境未就绪，请先安装并验证 qemu-x86-64-compat 插件"
        }
        return ExecutionLayout(
            rootfsDir = QemuCompatibilityLayout.guestRootfs(tianyanRoot),
            emulatorBinary = QemuCompatibilityLayout.qemuBinary(tianyanRoot),
            optDir = tianyanRoot,
        )
    }

    private data class ExecutionLayout(
        val rootfsDir: File,
        val emulatorBinary: File?,
        val optDir: File,
    )

    override suspend fun startSession(config: SessionConfig, distroId: String?): LinuxSession = storageActivities.activity {
        val prepared = prepareInteractiveSession(config, distroId)
        try {
            val session = top.tianyan.app.runtime.shell.launchRuntimeSession(
                ptyManager, prepared.effectiveConfig, prepared.command, prepared.fallbackCommand, prepared.hostEnvironment,
                resize = { columns, rows -> resizePty(prepared.markerPath, columns, rows, prepared.distroId) },
                cleanup = { check(!prepared.markerFile.exists() || prepared.markerFile.delete()) { "Session marker cleanup failed" } },
            )
            interactiveSessions.track(session, prepared.distroId)
        } catch (throwable: Throwable) {
            prepared.markerFile.delete()
            throw throwable
        }
    }

    override suspend fun buildInteractiveLaunch(
        config: SessionConfig,
        distroId: String?,
    ): InteractiveLaunchSpec = storageActivities.activity {
        val prepared = prepareInteractiveSession(config, distroId)
        // Termux JNI owns the PTY; marker file is unused for this path.
        prepared.markerFile.delete()
        // Termux JNI createSubprocess replaces the process environment entirely.
        // Merge Android env + our PRoot host vars (same shape as Android-PRoot-Engine).
        val mergedEnv = LinkedHashMap<String, String>().apply {
            System.getenv().forEach { (k, v) -> put(k, v) }
            putAll(prepared.hostEnvironment)
            putIfAbsent("HOME", pathManager.baseDir.parentFile?.absolutePath ?: "/")
            putIfAbsent("PATH", System.getenv("PATH") ?: "/system/bin:/system/xbin")
        }
        InteractiveLaunchSpec(
            executable = prepared.command.first(),
            arguments = prepared.command.toTypedArray(),
            workingDirectory = pathManager.baseDir.parentFile?.absolutePath
                ?: pathManager.baseDir.absolutePath,
            environment = mergedEnv.map { "${it.key}=${it.value}" }.toTypedArray(),
        )
    }

    private data class PreparedInteractive(
        val distroId: String,
        val effectiveConfig: SessionConfig,
        val command: List<String>,
        val fallbackCommand: List<String>,
        val hostEnvironment: Map<String, String>,
        val markerFile: File,
        val markerPath: String,
    )

    private suspend fun prepareInteractiveSession(
        config: SessionConfig,
        distroId: String?,
    ): PreparedInteractive {
        ensureReady()
        val safeDistro = boundLocalDistribution(distroId, _activeDistroId.value)
        val effectiveConfig = if (config.commandLine == "/bin/bash -i") {
            val rootfs = pathManager.rootfsDir(safeDistro)
            val hasBash = File(rootfs, "bin/bash").exists() || File(rootfs, "usr/bin/bash").exists()
            if (hasBash) config else config.copy(commandLine = "/bin/sh -i", allowSttyResize = true)
        } else {
            config
        }
        val markerId = UUID.randomUUID().toString()
        val markerFile = File(pathManager.tianyanRootDir(safeDistro), ".pty-$markerId")
        val markerPath = "/opt/tianyan/.pty-$markerId"
        val mounts = storageMounts()
        runCatching {
            syncGuestGroups(
                rootfsDir = pathManager.rootfsDir(safeDistro),
                hostGroupIds = hostSupplementaryGroupIds(),
            )
        }.onFailure {
            logger.w("同步宿主补充组到 /etc/group 失败，登录时可能出现 groups 警告", it)
        }
        if (effectiveConfig.showBanner) {
            runCatching {
                File(pathManager.tianyanRootDir(safeDistro), "motd").writeText(terminalBanner())
            }.onFailure {
                logger.w("写入终端横幅失败，本次会话将无横幅", it)
            }
        }
        // 会话在其整个生命周期内固定使用同一个后端：只解析一次，主/回退命令共用。
        val builder = backendSelector.active()
        val command = builder.buildInteractive(
            prootBinary = pathManager.activeProotFile(),
            rootfsDir = pathManager.rootfsDir(safeDistro),
            workspaceDir = pathManager.workspaceDir,
            homeDir = pathManager.homeDir(safeDistro),
            optDir = pathManager.tianyanRootDir(safeDistro),
            tmpDir = pathManager.tmpDir,
            attachmentsDir = pathManager.attachmentsDir,
            config = effectiveConfig,
            nativePty = true,
            mounts = mounts,
            hostCredential = sandboxCredentials.environment(safeDistro, effectiveConfig.pluginIdentity?.pluginId ?: "sandbox", effectiveConfig.pluginIdentity?.version ?: "sandbox"),
        )
        val fallbackCommand = builder.buildInteractive(
            prootBinary = pathManager.activeProotFile(),
            rootfsDir = pathManager.rootfsDir(safeDistro),
            workspaceDir = pathManager.workspaceDir,
            homeDir = pathManager.homeDir(safeDistro),
            optDir = pathManager.tianyanRootDir(safeDistro),
            tmpDir = pathManager.tmpDir,
            attachmentsDir = pathManager.attachmentsDir,
            config = effectiveConfig,
            ptyMarker = markerPath,
            mounts = mounts,
            hostCredential = sandboxCredentials.environment(safeDistro, effectiveConfig.pluginIdentity?.pluginId ?: "sandbox", effectiveConfig.pluginIdentity?.version ?: "sandbox"),
        )
        return PreparedInteractive(
            distroId = safeDistro,
            effectiveConfig = effectiveConfig,
            command = command,
            fallbackCommand = fallbackCommand,
            hostEnvironment = pathManager.hostProcessEnvironment(safeDistro),
            markerFile = markerFile,
            markerPath = markerPath,
        )
    }

    private suspend fun storageMounts(): List<StorageMountBinding> =
        resolveStorageMounts(settingsDataStore, storageMountBindingRepository, logger)

    private suspend fun resizePty(markerPath: String, columns: Int, rows: Int, distroId: String = "ubuntu") {
        val safeColumns = columns.coerceIn(20, 400)
        val safeRows = rows.coerceIn(5, 200)
        runCatching {
            shellExecutor.execute(
                command = backendSelector.active().build(
                    prootBinary = pathManager.activeProotFile(),
                    rootfsDir = pathManager.rootfsDir(distroId),
                    workspaceDir = pathManager.workspaceDir,
                    homeDir = pathManager.homeDir(distroId),
                    optDir = pathManager.tianyanRootDir(distroId),
                    tmpDir = pathManager.tmpDir,
                    attachmentsDir = pathManager.attachmentsDir,
                    command = ShellCommand(
                        commandLine = "if test -s '$markerPath'; then " +
                            "stty -F \"\$(cat '$markerPath')\" cols $safeColumns rows $safeRows; " +
                            "fi",
                        timeoutMs = 2_000L,
                    ),
                ),
                environment = pathManager.hostProcessEnvironment(distroId),
                timeoutMs = 2_000L,
            )
        }
    }

    override suspend fun startBackground(
        id: String,
        command: ShellCommand,
        toolId: String?,
        type: ProcessType,
        distroId: String?,
    ): ManagedProcess = storageActivities.activity {
        ensureReady()
        val mounts = storageMounts()
        processRegistry.start(
            id = id,
            command = command,
            toolId = toolId,
            type = type,
            distroId = boundLocalDistribution(distroId, _activeDistroId.value),
            mounts = mounts,
        )
    }

    override suspend fun stopBackground(id: String): Boolean = processRegistry.stop(id)
    override val supportsBackgroundInstanceStop = true
    override suspend fun stopBackgroundInstance(process: ManagedProcess): Boolean = processRegistry.stopInstance(process)

    override fun listBackground(): List<ManagedProcess> = processRegistry.list()

    override suspend fun cleanupDeadBackground(): Int = processRegistry.cleanupDeadProcesses()

    override fun observeBackgroundLogs(idOrToolId: String): Flow<List<String>> =
        processRegistry.observeLogs(idOrToolId)

    override fun getBackgroundLogs(idOrToolId: String): List<String> =
        processRegistry.getLogs(idOrToolId)

    override fun clearBackgroundLogs(idOrToolId: String) = processRegistry.clearLogs(idOrToolId)

    override suspend fun shutdown() = initializeMutex.withLock { withRuntimeMaintenance {
        closeRuntimeProcesses()
        hostBridge.stop()
        _state.value = RuntimeState.NotInitialized
        logger.i("Linux runtime shut down")
    } }

    private suspend fun <T> withRuntimeMaintenance(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { storageActivities.cleanup(block) }

    override suspend fun withStorageCleanup(block: suspend () -> Unit) {
        check(initializeMutex.tryLock()) { "正在安装、更新或管理环境，请完成后再清理" }
        try {
            storageActivities.cleanup {
                check(!interactiveSessions.hasSessions() && processRegistry.list().isEmpty() && !shellExecutor.hasPending()) {
                    "请先关闭终端并停止后台服务，再清理存储"
                }
                check(_state.value !is RuntimeState.Initializing) { "环境正在初始化，请稍后再清理" }
                block()
            }
        } finally {
            initializeMutex.unlock()
        }
    }

    private suspend fun closeRuntimeProcesses(distroId: String? = null) = closeRuntimeResources(listOf(
        { interactiveSessions.closeAll(distroId) },
        { processRegistry.stopAll() },
        { shellExecutor.closePending() },
    ))

    override fun rootfsPath(distroId: String?): File =
        pathManager.rootfsDir(distroId?.lowercase()?.trim() ?: _activeDistroId.value)

    override fun rootfsVersion(distroId: String?): String? =
        pathManager.rootfsVersion(distroId?.lowercase()?.trim() ?: _activeDistroId.value)

    override fun workspacePath(): File = pathManager.workspaceDir

    private fun detectArchitecture(): CpuArch {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        return CpuArch.fromBuildAbi(abi)
    }

    private fun checkStorage() {
        val filesPath = pathManager.baseDir.parentFile?.absolutePath
            ?: pathManager.baseDir.absolutePath
        val statFs = StatFs(filesPath)
        val availableBytes = statFs.availableBytes
        if (availableBytes < MIN_FREE_BYTES) {
            throw InsufficientStorageException(
                "Not enough free space. Required at least ${MIN_FREE_BYTES / (1024 * 1024)} MB, " +
                    "available ${availableBytes / (1024 * 1024)} MB",
            )
        }
    }

    private fun createWorkspace() {
        pathManager.workspaceDir.mkdirs()
    }

    private fun ensureReady() {
        if (_state.value !is RuntimeState.Ready) {
            throw IllegalStateException("Linux runtime is not ready. Call initialize() first.")
        }
    }

    private fun updateInitializing(step: String, progress: Float, detail: String? = null) {
        _state.value = RuntimeState.Initializing(
            step = step,
            progress = progress.coerceIn(0f, 1f),
            detail = detail,
        )
    }

    private fun updateDownloadProgress(
        step: String,
        baseProgress: Float,
        progressSpan: Float,
        progress: DownloadProgress,
    ) {
        val fraction = progress.fraction ?: 0f
        val scaled = baseProgress + fraction * progressSpan
        val detail = if (progress.totalMegabytes != null) {
            "${progress.downloadedMegabytes} / ${progress.totalMegabytes} MB"
        } else {
            "已下载 ${progress.downloadedMegabytes} MB"
        }
        updateInitializing(step, scaled, detail)
    }

    private fun failInitialization(error: AppError) {
        _state.value = RuntimeState.Error(
            error.cause ?: IllegalStateException(error.message),
        )
    }

    private class RuntimeArchitectureException(message: String) : IllegalStateException(message)

    private class InsufficientStorageException(message: String) : IllegalStateException(message)

    companion object {
        const val MIN_FREE_BYTES = 600L * 1024L * 1024L
    }
}
