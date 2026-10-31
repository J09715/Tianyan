package top.tianyan.app.runtime

import top.tianyan.app.core.common.result.AppError
import top.tianyan.app.core.common.result.AppResult
import top.tianyan.app.core.common.result.ErrorCode
import top.tianyan.app.core.model.InstalledDistro
import top.tianyan.app.core.model.RuntimeState
import top.tianyan.app.runtime.shell.CommandResult
import top.tianyan.app.runtime.shell.InteractiveLaunchSpec
import top.tianyan.app.runtime.shell.LinuxSession
import top.tianyan.app.runtime.shell.ManagedProcess
import top.tianyan.app.runtime.shell.ProcessType
import top.tianyan.app.runtime.shell.SessionConfig
import top.tianyan.app.runtime.shell.ShellCommand
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

interface LinuxRuntime {
    val state: StateFlow<RuntimeState>
    val activeDistroId: StateFlow<String>
    val installedDistros: StateFlow<List<InstalledDistro>>

    suspend fun initialize(request: RuntimeInstallRequest = RuntimeInstallRequest("ubuntu")): AppResult<Unit>
    suspend fun restoreInstalledState(): Boolean
    suspend fun updateRootfs(distroId: String? = null): AppResult<Unit>
    suspend fun checkRootfsUpdate(distroId: String? = null): AppResult<RootfsUpdateInfo>
    suspend fun healthCheck(distroId: String? = null): RuntimeHealth

    suspend fun switchActiveDistro(distroId: String): AppResult<Unit>
    suspend fun installDistro(request: RuntimeInstallRequest, onProgress: suspend (DownloadProgress) -> Unit = {}): AppResult<Unit>
    suspend fun importDistro(request: RuntimeInstallRequest, archive: File): AppResult<Unit> =
        AppResult.Failure(AppError(
            ErrorCode.INSTALLATION_FAILED,
            "当前运行时不支持导入 RootFS",
        ))
    suspend fun uninstallDistro(distroId: String): AppResult<Unit>
    /** Remove the active distro and its installed tools while preserving /workspace. */
    suspend fun resetSandbox(distroId: String? = null): AppResult<Unit>
    fun refreshInstalledDistros()

    suspend fun execute(command: ShellCommand, distroId: String? = null): CommandResult
    suspend fun startSession(config: SessionConfig = SessionConfig(), distroId: String? = null): LinuxSession

    /**
     * Build host argv/env for an interactive console owned by Termux TerminalSession JNI,
     * without opening our NativePty. Used by the matrix terminal UI.
     */
    suspend fun buildInteractiveLaunch(
        config: SessionConfig = SessionConfig(),
        distroId: String? = null,
    ): InteractiveLaunchSpec = error("当前运行时不支持交互式终端启动规格")

    suspend fun startBackground(
        id: String,
        command: ShellCommand,
        toolId: String? = null,
        type: ProcessType = ProcessType.SERVICE,
        distroId: String? = null,
    ): ManagedProcess

    suspend fun stopBackground(id: String): Boolean
    val supportsBackgroundInstanceStop: Boolean get() = false
    suspend fun stopBackgroundInstance(process: ManagedProcess): Boolean = false
    fun listBackground(): List<ManagedProcess>
    suspend fun cleanupDeadBackground(): Int
    /**
     * 回收 [port] 上被遗弃的本运行时监听者（上一代进程被强杀后仍在监听回环端口的孤儿）。
     * [protectedSessions] 为调用方可额外豁免的会话根（终端会话、前台命令）；
     * 不支持精确回收的后端返回 0。
     */
    suspend fun reclaimListeningPort(port: Int, protectedSessions: Set<Long> = emptySet()): Int = 0
    fun observeBackgroundLogs(idOrToolId: String): Flow<List<String>> = emptyFlow()
    fun getBackgroundLogs(idOrToolId: String): List<String> = emptyList()
    fun clearBackgroundLogs(idOrToolId: String) = Unit

    suspend fun shutdown()
    /** Execute file cleanup only while runtime launches and installation are excluded. */
    suspend fun withStorageCleanup(block: suspend () -> Unit) {
        error("当前运行时不支持安全存储清理")
    }
    fun rootfsPath(distroId: String? = null): File
    fun rootfsVersion(distroId: String? = null): String? = null
    fun workspacePath(): File
}
