package top.tianyan.app.harness.workflow

import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import top.tianyan.app.core.common.result.AppResult
import top.tianyan.app.core.database.WorkflowRepository
import top.tianyan.app.core.database.WorkflowExecutionLogEntity
import top.tianyan.app.core.model.InstalledDistro
import top.tianyan.app.core.model.RuntimeState
import top.tianyan.app.core.model.workflow.WorkflowDefinition
import top.tianyan.app.core.model.workflow.WorkflowRuntimeState
import top.tianyan.app.runtime.DownloadProgress
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.RuntimeHealth
import top.tianyan.app.runtime.RuntimeInstallRequest
import top.tianyan.app.runtime.RootfsUpdateInfo
import top.tianyan.app.runtime.shell.CommandResult
import top.tianyan.app.runtime.shell.LinuxSession
import top.tianyan.app.runtime.shell.ManagedProcess
import top.tianyan.app.runtime.shell.ProcessType
import top.tianyan.app.runtime.shell.SessionConfig
import top.tianyan.app.runtime.shell.ShellCommand

internal data class SavedRun(
    val state: WorkflowRuntimeState,
    val triggerSource: String,
    val scheduleId: String?,
)

internal class FakeWorkflowRepository : WorkflowRepository {
    val saved = mutableListOf<SavedRun>()
    val stored = mutableMapOf<String, WorkflowExecutionLogEntity>()
    var unfinished: List<WorkflowExecutionLogEntity> = emptyList()

    override fun observeDefinitions(): Flow<List<WorkflowDefinition>> = MutableStateFlow(emptyList())
    override fun observeRecentExecutions(limit: Int): Flow<List<WorkflowExecutionLogEntity>> =
        MutableStateFlow(emptyList())

    override suspend fun findById(id: String): WorkflowDefinition? = null
    override suspend fun findBySlashCommand(command: String): WorkflowDefinition? = null
    override suspend fun upsert(definition: WorkflowDefinition) = Unit
    override suspend fun deleteCustom(id: String): Boolean = false
    override suspend fun ensureBuiltins() = Unit
    override suspend fun saveExecution(state: WorkflowRuntimeState, triggerSource: String, scheduleId: String?) {
        saved.add(SavedRun(state, triggerSource, scheduleId))
        stored[state.executionId] = WorkflowExecutionLogEntity(
            executionId = state.executionId, workflowId = state.definition.id,
            startTime = state.startedAt ?: 0L, endTime = state.finishedAt, status = state.status.name,
            finalContextJson = Json.encodeToString(WorkflowRuntimeState.serializer(), state),
            triggerSource = triggerSource, scheduleId = scheduleId, workflowName = state.definition.name,
        )
    }

    override suspend fun findUnfinishedExecutions(): List<WorkflowExecutionLogEntity> =
        unfinished

    override suspend fun findExecutionById(id: String): WorkflowExecutionLogEntity? = stored[id]
}

internal class FakeLinuxRuntime(
    initialState: RuntimeState = RuntimeState.NotInitialized,
    private val restoreResult: Boolean = false,
) : LinuxRuntime {
    val stateFlow = MutableStateFlow<RuntimeState>(initialState)
    var restoreCalled = false

    override val state: StateFlow<RuntimeState> = stateFlow
    override val activeDistroId: StateFlow<String> = MutableStateFlow("")
    override val installedDistros: StateFlow<List<InstalledDistro>> = MutableStateFlow(emptyList())

    override suspend fun initialize(request: RuntimeInstallRequest): AppResult<Unit> = error("unused")
    override suspend fun restoreInstalledState(): Boolean {
        restoreCalled = true
        if (restoreResult) stateFlow.value = RuntimeState.Ready
        return restoreResult
    }

    override suspend fun updateRootfs(distroId: String?): AppResult<Unit> = error("unused")
    override suspend fun checkRootfsUpdate(distroId: String?): AppResult<RootfsUpdateInfo> = error("unused")
    override suspend fun healthCheck(distroId: String?): RuntimeHealth = error("unused")
    override suspend fun switchActiveDistro(distroId: String): AppResult<Unit> = error("unused")
    override suspend fun installDistro(
        request: RuntimeInstallRequest,
        onProgress: suspend (DownloadProgress) -> Unit,
    ): AppResult<Unit> = error("unused")

    override suspend fun uninstallDistro(distroId: String): AppResult<Unit> = error("unused")
    override suspend fun resetSandbox(distroId: String?): AppResult<Unit> = error("unused")
    override fun refreshInstalledDistros() = Unit
    override suspend fun execute(command: ShellCommand, distroId: String?): CommandResult = error("unused")
    override suspend fun startSession(config: SessionConfig, distroId: String?): LinuxSession = error("unused")
    override suspend fun startBackground(
        id: String,
        command: ShellCommand,
        toolId: String?,
        type: ProcessType,
        distroId: String?,
    ): ManagedProcess = error("unused")

    override suspend fun stopBackground(id: String): Boolean = true
    override fun listBackground(): List<ManagedProcess> = emptyList()
    override suspend fun cleanupDeadBackground(): Int = 0
    override suspend fun shutdown() = Unit
    override fun rootfsPath(distroId: String?): File = File("/tmp/rootfs")
    override fun workspacePath(): File = File("/tmp/workspace")
}
