package top.tianyan.app.runtime.environment

import top.tianyan.app.core.common.result.AppResult
import top.tianyan.app.runtime.shell.*

/** One immutable execution world. Paths are guest paths, never host java.io.File paths. */
data class ExecutionEnvironmentId(val backend: String, val distribution: String, val workspace: String, val owner: String)

data class WorkspaceEditOutcome(val strategy: String, val replacements: Int, val diff: String?)
data class ExecutionFileEntry(val name: String, val directory: Boolean, val size: Long, val modifiedAt: Long)

interface ExecutionFiles {
    /** Bounded literal search. Apply trusted redactLine before clipping; unsupported backends must not fall back. */
    suspend fun search(path: String, query: String, maxResults: Int = 50, redactLine: (String) -> String = { it }): AppResult<String> = AppResult.Failure(
        top.tianyan.app.core.common.result.AppError(top.tianyan.app.core.common.result.ErrorCode.IO, "当前执行环境不支持原生文本检索；请使用当前环境的 base/grep"))
    /** Optional, strict artifact inventory: absent directories are empty; incomplete/failed listing is a failure. */
    suspend fun listArtifactEntries(path: String): AppResult<List<ExecutionFileEntry>> = listEntries(path)
    /** Hash binary data in this world without copying it into chat. Missing capabilities fail, never fall back. */
    suspend fun fingerprint(path: String, maxBytes: Long = MAX_FILE_FINGERPRINT_BYTES): AppResult<FileFingerprint> = AppResult.Failure(
        top.tianyan.app.core.common.result.AppError(top.tianyan.app.core.common.result.ErrorCode.IO, "当前环境不支持文件指纹"))
    suspend fun listEntries(path: String): AppResult<List<ExecutionFileEntry>> = AppResult.Failure(
        top.tianyan.app.core.common.result.AppError(top.tianyan.app.core.common.result.ErrorCode.IO, "当前环境不支持目录浏览"))
    suspend fun read(path: String, offset: Int? = null, limit: Int? = null): AppResult<String>
    suspend fun readRawBytes(path: String): AppResult<ByteArray>
    suspend fun write(path: String, content: String): AppResult<Unit>
    suspend fun editDetailed(path: String, oldText: String, newText: String): AppResult<WorkspaceEditOutcome>
    suspend fun previewOrNull(path: String): String?
    suspend fun fileSizeOrNull(path: String): Long?
    /** Delete a file within this world; absent files succeed, directories and escapes fail. */
    suspend fun delete(path: String): Boolean
}

interface ExecutionCommands {
    suspend fun execute(command: ShellCommand): CommandResult
    suspend fun startBackground(id: String, command: ShellCommand): ManagedProcess
    suspend fun stopBackground(id: String): Boolean
    fun listBackground(): List<ManagedProcess>
    fun getBackgroundLogs(id: String): List<String>
}

interface ExecutionEnvironment : ExecutionCommands {
    val id: ExecutionEnvironmentId
    /** Logical file-tool alias mapped onto id.workspace (may equal the physical path). */
    val logicalWorkspace: String get() = id.workspace
    /** 人类可读的环境标识（远程=主机，本地=发行版），仅用于诊断与错误提示。 */
    val label: String get() = id.distribution
    val files: ExecutionFiles
    val artifacts: ExecutionArtifacts? get() = null
    val downloads: ExecutionDownloads? get() = null
    val buildBindings: ExecutionBuildBindings? get() = null
    /** Known environment values used only for output redaction, never exposed to model results. */
    suspend fun outputSecrets(): Collection<String> = emptyList()
    /** Compatibility gate for services still bound to the shared local runtime (build/subagent). */
    val supportsSharedLocalServices: Boolean get() = false
    fun workingDirectory(explicit: String? = null): String
    /** Protocol subprocess: cancellation/close belongs to the returned session. */
    suspend fun openSession(config: SessionConfig): LinuxSession
    /** Interactive PTY: remote implementations must implement resize, input, interrupt and close. */
    suspend fun openTerminal(config: SessionConfig): LinuxSession
    /** Stop owned subprocesses/PTYs; never shut down a shared runtime. Idempotent. */
    suspend fun close()
}

fun interface ExecutionEnvironmentFactory {
    /** Serializable identity captured before a new session awaits persistence/model lookup. */
    suspend fun capture(workspace: String): String? = null
    suspend fun capture(workspace: String, distribution: String?): String? = capture(workspace)
    /** Restore an explicitly saved standalone identity before allocating resources. */
    suspend fun restore(owner: String, workspace: String, targetJson: String) = Unit
    /** Capture the selected identity without starting a connection or executing commands. */
    suspend fun bind(owner: String, workspace: String) = Unit
    /** Failed/cancelled setup must roll back allocations before throwing; success transfers ownership. */
    suspend fun open(owner: String, workspace: String, distribution: String?): ExecutionEnvironment
}

/** Optional Android Termux renderer adapter. Remote worlds must not fabricate host argv. */
interface LocalTerminalLaunch {
    suspend fun terminalLaunch(config: SessionConfig): InteractiveLaunchSpec
}
