package top.tianyan.app.harness.workflow

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.database.WorkflowRepository
import top.tianyan.app.core.model.RuntimeState
import top.tianyan.app.core.model.workflow.WorkflowApprovalRequest
import top.tianyan.app.core.model.workflow.NodeExecutionOutput
import top.tianyan.app.core.model.workflow.WorkflowRunStatus
import top.tianyan.app.core.model.workflow.WorkflowRuntimeState
import top.tianyan.app.core.model.workflow.WorkflowDefinition
import top.tianyan.app.core.model.workflow.WorkflowRunTrigger
import top.tianyan.app.core.model.workflow.triggerSource
import top.tianyan.app.core.model.workflow.WorkflowRecovery
import top.tianyan.app.core.model.workflow.WorkflowJournalKind
import top.tianyan.app.core.model.workflow.WorkflowJournalEntry
import top.tianyan.app.runtime.LinuxRuntime

/**
 * 工作流运行的进程级所有者：把执行从 ViewModel 生命周期解耦。
 *
 * - 所有运行跑在自建的 [runScope]（SupervisorJob），退出页面/销毁 ViewModel 不再取消；
 * - 注册表持有 [WorkflowRunHandle]，UI 或通知栏可随时 cancel/decide；
 * - 运行随推进持续 upsert RUNNING 面包屑（进程被杀后历史可对账），终态写最终快照；
 * - [reconcileInterruptedRuns] 在应用启动时把进程死亡遗留的非终态行标为 CANCELLED。
 */
class WorkflowRunManager(
    private val scheduler: WorkflowScheduler,
    private val repository: WorkflowRepository,
    private val linuxRuntime: LinuxRuntime,
    private val json: Json,
    private val logger: AppLogger,
) {
    internal constructor(
        scheduler: WorkflowScheduler,
        repository: WorkflowRepository,
        linuxRuntime: LinuxRuntime,
        json: Json,
        logger: AppLogger,
        scopeOverride: CoroutineScope,
    ) : this(scheduler, repository, linuxRuntime, json, logger) {
        runScopeOverride = scopeOverride
    }

    private val defaultRunScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var runScopeOverride: CoroutineScope? = null
    private val runScope: CoroutineScope get() = runScopeOverride ?: defaultRunScope

    private val handles = ConcurrentHashMap<String, WorkflowRunHandle>()
    private val historyWriter = WorkflowHistoryWriter(repository) { message, error -> logger.e(message, error) }
    val historyErrors: StateFlow<Map<String, String>> = historyWriter.errors
    private val runningIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val resumeLock = Mutex()

    private val _activeRuns = MutableStateFlow<Map<String, WorkflowRuntimeState>>(emptyMap())
    val activeRuns: StateFlow<Map<String, WorkflowRuntimeState>> = _activeRuns.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _latestRunId = MutableStateFlow<String?>(null)
    val latestRunId: StateFlow<String?> = _latestRunId.asStateFlow()

    /** 全部待审批请求（executionId → request）：后台审批通知与 UI 弹窗共用。 */
    private val _pendingApprovals = MutableStateFlow<Map<String, WorkflowApprovalRequest>>(emptyMap())
    val pendingApprovals: StateFlow<Map<String, WorkflowApprovalRequest>> = _pendingApprovals.asStateFlow()

    fun handle(executionId: String): WorkflowRunHandle? = handles[executionId]

    /** 指定运行的审批请求流；运行不存在时返回恒为 null 的空流。 */
    fun approvalRequestFor(executionId: String): StateFlow<WorkflowApprovalRequest?> =
        handles[executionId]?.approvalRequest ?: MutableStateFlow(null)

    fun cancel(executionId: String) {
        handles[executionId]?.cancel()
        stopManagedProcesses(executionId)
    }

    fun decide(executionId: String, nodeId: String, approved: Boolean, variables: Map<String, String> = emptyMap(), reviewToken: String? = null): Boolean =
        handles[executionId]?.decide(nodeId, approved, variables, reviewToken) ?: false

    /**
     * 启动一次运行。运行时未就绪（未安装沙箱/等待超时）时不启动调度，
     * 而是落一条 FAILED 历史便于用户与定时计划看到原因。
     */
    suspend fun start(
        definition: WorkflowDefinition,
        variables: Map<String, String> = emptyMap(),
        workspacePath: String,
        trigger: WorkflowRunTrigger = WorkflowRunTrigger.Manual,
    ): WorkflowStartResult {
        if (linuxRuntime.state.value !is RuntimeState.Ready) {
            awaitRuntimeReady()?.let { failure ->
                val executionId = "wf_${UUID.randomUUID()}"
                val failedState = WorkflowRuntimeState.initial(executionId, definition).copy(
                    status = WorkflowRunStatus.FAILED,
                    startedAt = System.currentTimeMillis(),
                    finishedAt = System.currentTimeMillis(),
                    error = failure,
                )
                historyWriter.save(failedState, trigger.triggerSource, (trigger as? WorkflowRunTrigger.Schedule)?.scheduleId)
                return WorkflowStartResult(executionId, failure)
            }
        }
        val handle = scheduler.execute(definition, variables, workspacePath, runScope,
            checkpoint = durableCheckpoint(trigger.triggerSource, (trigger as? WorkflowRunTrigger.Schedule)?.scheduleId),
            environmentId = linuxRuntime.activeDistroId.value)
        return register(handle, trigger)
    }

    /** Explicit continuation of the stored definition; retries must match the reviewed snapshot. */
    suspend fun resume(executionId: String, retryNodeIds: Set<String>, expectedResumeCount: Int, expectedJournalSize: Int): WorkflowStartResult = resumeLock.withLock {
        fun rejected(message: String) = WorkflowStartResult(executionId, message)
        if (executionId in runningIds || handles[executionId]?.settled == false) return@withLock rejected("此运行正在执行或收尾，请勿重复继续")
        val row = repository.findExecutionById(executionId) ?: return@withLock rejected("运行记录不存在")
        val snapshot = runCatching { json.decodeFromString<WorkflowRuntimeState>(row.finalContextJson) }.getOrNull()
            ?: return@withLock rejected("运行记录无法读取")
        if (snapshot.executionId != executionId || snapshot.resumeCount != expectedResumeCount || snapshot.journal.size != expectedJournalSize)
            return@withLock rejected("运行记录已变化，请重新查看恢复计划")
        val plan = WorkflowRecovery.inspect(snapshot)
        if (!plan.canResume) return@withLock rejected(plan.blockedReason!!)
        if (retryNodeIds != plan.retryNodeIds) return@withLock rejected("请逐个确认所有失败或结果不确定的节点；不允许重试已完成节点")
        if (linuxRuntime.state.value !is RuntimeState.Ready) return@withLock rejected("Linux 沙箱尚未就绪，请就绪后继续")
        if (snapshot.runtimeEnvironmentId != linuxRuntime.activeDistroId.value) return@withLock rejected("当前发行版与原运行不同，请先切回原发行版")
        val interrupted = snapshot.journal.groupBy { it.nodeId }.values.map { it.last() }.filter { it.kind == WorkflowJournalKind.STARTED }
        val journal = snapshot.journal + interrupted.mapIndexed { index, entry ->
            WorkflowJournalEntry(snapshot.journal.size + index + 1, entry.nodeId, entry.attempt, WorkflowJournalKind.INTERRUPTED, System.currentTimeMillis())
        }
        val previousContext = requireNotNull(snapshot.context)
        val context = previousContext.copy(nodeOutputs = plan.cachedOutputs,
            globalVariables = previousContext.globalVariables + plan.cachedOutputs.values.flatMap { it.variables.entries }.associate { it.key to it.value })
        val restored = snapshot.copy(status = WorkflowRunStatus.IDLE, context = context, finishedAt = null, error = null,
            journal = journal, resumeCount = snapshot.resumeCount + 1)
        val trigger = row.scheduleId?.takeIf { row.triggerSource == "SCHEDULE" }?.let { WorkflowRunTrigger.Schedule(it) } ?: WorkflowRunTrigger.Manual
        register(scheduler.execute(snapshot.definition, emptyMap(), context.workspacePath, runScope,
            checkpoint = durableCheckpoint(row.triggerSource, row.scheduleId), restored = restored), trigger)
    }

    private fun durableCheckpoint(source: String, scheduleId: String?): suspend (WorkflowRuntimeState) -> Unit = { state ->
        check(historyWriter.save(state, source, scheduleId)) { "执行检查点保存失败，已停止派发后续动作" }
    }

    private fun register(handle: WorkflowRunHandle, trigger: WorkflowRunTrigger): WorkflowStartResult {
        val executionId = handle.state.value.executionId
        handles[executionId] = handle
        runningIds.add(executionId)
        _running.value = true
        _latestRunId.value = executionId
        runScope.launch { observeRun(handle, trigger) }
        return WorkflowStartResult(executionId)
    }

    /** 启动对账：进程死亡遗留的非终态历史补写 CANCELLED，节点状态与日志保留可回看。 */
    suspend fun reconcileInterruptedRuns() {
        val unfinished = repository.findUnfinishedExecutions()
        unfinished.forEach { row ->
            if (row.executionId in runningIds) return@forEach
            val state = runCatching { json.decodeFromString<WorkflowRuntimeState>(row.finalContextJson) }.getOrNull() ?: return@forEach
            if (state.status in TERMINAL) return@forEach
            val reconciled = state.copy(
                status = WorkflowRunStatus.CANCELLED,
                finishedAt = System.currentTimeMillis(),
                error = "进程曾被系统终止，可重新运行",
            )
            historyWriter.save(reconciled, row.triggerSource, row.scheduleId)
        }
    }

    private suspend fun observeRun(handle: WorkflowRunHandle, trigger: WorkflowRunTrigger) {
        val executionId = handle.state.value.executionId
        val scheduleId = (trigger as? WorkflowRunTrigger.Schedule)?.scheduleId
        val source = trigger.triggerSource
        var lastPersistAt = 0L
        var persistedOutputs = emptyMap<String, NodeExecutionOutput>()
        // 审批流订阅必须与本观察协程同生共死：否则每次运行泄漏一个挂在 StateFlow 上的收集协程
        val approvalJob = runScope.launch {
            handle.approvalRequest.collect { request ->
                _pendingApprovals.update { current ->
                    if (request == null) current - executionId else current + (executionId to request)
                }
            }
        }
        try {
            handle.state.first { state ->
                _activeRuns.update { it + (executionId to state) }
                val terminal = state.status in TERMINAL
                val now = System.currentTimeMillis()
                // 已结算结果（含结构化值与产物路径）不受日志刷新节流影响。
                val outputs = state.context?.nodeOutputs.orEmpty()
                if (terminal || outputs != persistedOutputs || lastPersistAt == 0L || now - lastPersistAt >= BREADCRUMB_INTERVAL_MS) {
                    if (historyWriter.save(state, source, scheduleId)) {
                        lastPersistAt = now
                        persistedOutputs = outputs
                    }
                }
                terminal
            }
        } finally {
            approvalJob.cancel()
            finishRun(handle)
        }
    }

    private fun finishRun(handle: WorkflowRunHandle) {
        val executionId = handle.state.value.executionId
        if (runningIds.remove(executionId)) {
            _running.value = runningIds.isNotEmpty()
            _pendingApprovals.update { it - executionId }
            stopManagedProcesses(executionId)
            // 终态句柄保留一段时间供 UI 回看，之后从注册表与 activeRuns 中回收
            runScope.launch {
                delay(RECLAIM_DELAY_MS)
                resumeLock.withLock {
                    if (handles.remove(executionId, handle)) {
                        _activeRuns.update { it - executionId }
                        historyWriter.forget(executionId)
                    }
                }
            }
        }
    }

    /** 回收该运行通过 PROCESS_SERVICE 节点启动的托管后台进程。 */
    private fun stopManagedProcesses(executionId: String) {
        runCatching {
            linuxRuntime.listBackground()
                .filter { it.id.startsWith("workflow:$executionId:") }
                .forEach { process -> launchStop(process.id) }
        }
    }

    private fun launchStop(processId: String) {
        runScope.launch { runCatching { linuxRuntime.stopBackground(processId) } }
    }

    /** 返回 null 表示就绪；否则返回用户可读的失败原因。不自动下载 RootFS。 */
    private suspend fun awaitRuntimeReady(): String? {
        val state = linuxRuntime.state
        if (state.value is RuntimeState.NotInitialized) {
            val restored = runCatching { linuxRuntime.restoreInstalledState() }.getOrDefault(false)
            if (!restored && state.value !is RuntimeState.Ready) {
                return "Linux 沙箱尚未初始化，无法运行工作流"
            }
        }
        val ready = withTimeoutOrNull(RUNTIME_READY_TIMEOUT_MS) {
            state.first { it is RuntimeState.Ready }
        } ?: return "等待 Linux 沙箱就绪超时（${RUNTIME_READY_TIMEOUT_MS / 1000} 秒），工作流未执行"
        return null
    }

    data class WorkflowStartResult(
        val executionId: String,
        val failureReason: String? = null,
    ) {
        val accepted: Boolean get() = failureReason == null
    }

    companion object {
        val TERMINAL = setOf(WorkflowRunStatus.SUCCESS, WorkflowRunStatus.FAILED, WorkflowRunStatus.CANCELLED)
        private const val BREADCRUMB_INTERVAL_MS = 3_000L
        private const val RECLAIM_DELAY_MS = 60 * 60 * 1000L
        private const val RUNTIME_READY_TIMEOUT_MS = 2 * 60 * 1000L
    }
}
