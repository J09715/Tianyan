package top.tianyan.app.harness.workflow

import java.io.File
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import top.tianyan.app.core.common.result.AppResult
import top.tianyan.app.core.database.WorkflowRepository
import top.tianyan.app.core.database.WorkflowScheduleEntity
import top.tianyan.app.core.database.WorkflowScheduleStore
import top.tianyan.app.core.model.InstalledDistro
import top.tianyan.app.core.model.RuntimeState
import top.tianyan.app.core.model.workflow.WorkflowDefinition
import top.tianyan.app.core.model.workflow.WorkflowEdge
import top.tianyan.app.core.model.workflow.WorkflowNode
import top.tianyan.app.core.model.workflow.WorkflowNodeType
import top.tianyan.app.core.model.workflow.WorkflowRunStatus
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
import android.content.ContextWrapper
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import top.tianyan.app.core.model.workflow.NodeExecutionOutput
import top.tianyan.app.core.model.workflow.NodeRunStatus
import top.tianyan.app.core.model.workflow.WorkflowRuntimeContext
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.database.WorkflowExecutionLogEntity

/** WorkflowRunManager：运行时就绪门、启动对账、注册表生命周期。 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowRunManagerTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun definition() = WorkflowDefinition(
        id = "wf_test",
        name = "测试流程",
        description = "",
        category = "测试",
        nodes = listOf(
            WorkflowNode("start", WorkflowNodeType.TRIGGER, "触发"),
            WorkflowNode("done", WorkflowNodeType.TERMINAL_OUTPUT, "完成"),
        ),
        edges = listOf(WorkflowEdge("e1", "start", "output", "done")),
    )

    private fun state(executionId: String, status: WorkflowRunStatus, definition: WorkflowDefinition) =
        WorkflowRuntimeState.initial(executionId, definition).copy(status = status, startedAt = 1L)

    @Test
    fun `completed node bypasses checkpoint throttle and survives cancellation`() = runTest {
        val release = CompletableDeferred<Unit>()
        val output = NodeExecutionOutput(NodeRunStatus.SUCCESS, artifacts = listOf("/workspace/report.md"), durationMs = 1L)
        val executor = object : NodeExecutor {
            override val supportedTypes = setOf(WorkflowNodeType.TRIGGER)
            override suspend fun execute(node: WorkflowNode, context: WorkflowRuntimeContext, onProgress: suspend (NodeRunStatus, String) -> Unit): NodeExecutionOutput {
                if (node.id == "slow") awaitCancellation()
                release.await()
                return output
            }
        }
        val repository = FakeWorkflowRepository()
        val manager = WorkflowRunManager(WorkflowScheduler(setOf(executor), WorkflowApprovalBroker()), repository,
            FakeLinuxRuntime(RuntimeState.Ready), testJson(), AppLogger(ContextWrapper(null)) { it }, backgroundScope)
        val workflow = definition().copy(nodes = listOf(
            WorkflowNode("fast", WorkflowNodeType.TRIGGER, "快"), WorkflowNode("slow", WorkflowNodeType.TRIGGER, "慢"),
        ), edges = emptyList())
        val started = manager.start(workflow, workspacePath = "/workspace")
        runCurrent()
        val initialCount = repository.saved.size
        release.complete(Unit)
        runCurrent()
        assertTrue(repository.saved.size > initialCount)
        assertEquals(output, repository.saved.last().state.context!!.nodeOutputs["fast"])
        assertEquals(WorkflowRunStatus.RUNNING, repository.saved.last().state.status)
        manager.cancel(started.executionId)
        runCurrent()
        assertEquals(WorkflowRunStatus.CANCELLED, repository.saved.last().state.status)
        assertEquals(output, repository.saved.last().state.context!!.nodeOutputs["fast"])
    }

    @Test
    fun `start fails fast with FAILED history when runtime is not installed`() = runTest {
        val runtime = FakeLinuxRuntime(initialState = RuntimeState.NotInitialized, restoreResult = false)
        val repository = FakeWorkflowRepository()
        val manager = manager(runtime, repository)

        val result = manager.start(definition(), emptyMap(), "/workspace")

        assertFalse(result.accepted)
        assertNotNull(result.failureReason)
        assertTrue(runtime.restoreCalled)
        val saved = repository.saved.last()
        assertEquals(WorkflowRunStatus.FAILED, saved.state.status)
        assertEquals("MANUAL", saved.triggerSource)
        assertNull(saved.scheduleId)
    }

    @Test
    fun `reconcile marks unfinished runs cancelled and preserves trigger provenance`() = runTest {
        val json = testJson()
        val repository = FakeWorkflowRepository()
        repository.unfinished = listOf(
            logRow(json, state("exec_sch", WorkflowRunStatus.RUNNING, definition()), "SCHEDULE", "sched_1"),
            logRow(json, state("exec_man", WorkflowRunStatus.WAITING_APPROVAL, definition()), "MANUAL", null),
        )
        val manager = manager(FakeLinuxRuntime(), repository)

        manager.reconcileInterruptedRuns()

        assertEquals(2, repository.saved.size)
        repository.saved.forEach { saved ->
            assertEquals(WorkflowRunStatus.CANCELLED, saved.state.status)
            assertNotNull(saved.state.error)
        }
        assertEquals("SCHEDULE", repository.saved[0].triggerSource)
        assertEquals("sched_1", repository.saved[0].scheduleId)
        assertEquals("MANUAL", repository.saved[1].triggerSource)
    }

    @Test
    fun `terminal run releases the running flag`() = runBlocking {
        val repository = FakeWorkflowRepository()
        val manager = manager(FakeLinuxRuntime(initialState = RuntimeState.Ready), repository)

        val result = manager.start(definition(), emptyMap(), "/workspace")
        assertTrue(result.accepted)

        // 空 executor set：TRIGGER 节点立即 FAILED → 运行到终态，观察者随后释放 running 标志
        withTimeout(5_000) {
            manager.activeRuns.first { runs -> runs[result.executionId]?.status?.let { it in WorkflowRunManager.TERMINAL } == true }
            manager.running.first { !it }
        }
        assertFalse(manager.running.value)
        assertEquals(WorkflowRunStatus.FAILED, manager.activeRuns.value[result.executionId]?.status)
    }

    @Test
    fun `schedule repository computes next daily run across midnight`() {
        val repository = WorkflowScheduleRepository(
            store = FakeScheduleStore(),
            workflowRepository = FakeWorkflowRepository(),
            dispatcher = RecordingDispatcher(),
        )
        val entity = scheduleEntity(repeatType = "DAILY", hour = 9, minute = 30)
        val from = localTime(2026, 9, 18, 10, 0)
        val next = repository.computeNextRunAt(entity, from)
        val expected = localTime(2026, 9, 19, 9, 30)
        assertEquals(expected, next)

        val before = localTime(2026, 9, 18, 8, 0)
        assertEquals(localTime(2026, 9, 18, 9, 30), repository.computeNextRunAt(entity, before))
    }

    @Test
    fun `schedule repository once trigger ignores past timestamps and clamps interval`() {
        val repository = WorkflowScheduleRepository(
            store = FakeScheduleStore(),
            workflowRepository = FakeWorkflowRepository(),
            dispatcher = RecordingDispatcher(),
        )
        val from = localTime(2026, 9, 18, 10, 0)
        assertNull(repository.computeNextRunAt(scheduleEntity(repeatType = "ONCE", onceAt = from - 1_000), from))
        assertEquals(from + 5_000, repository.computeNextRunAt(scheduleEntity(repeatType = "ONCE", onceAt = from + 5_000), from))
        assertEquals(
            from + 15 * 60_000L,
            repository.computeNextRunAt(scheduleEntity(repeatType = "INTERVAL", intervalMinutes = 3), from),
        )
    }

    @Test
    fun `enabling schedule persists the same next run sent to dispatcher`() = runBlocking {
        val store = FakeScheduleStore().apply {
            saved = scheduleEntity(repeatType = "INTERVAL", intervalMinutes = 15).copy(enabled = false, nextRunAt = 1L)
        }
        val dispatcher = RecordingDispatcher()
        val repository = WorkflowScheduleRepository(store, FakeWorkflowRepository(), dispatcher)

        repository.setEnabled("sched_test", true)

        assertTrue(store.saved!!.enabled)
        assertTrue(store.saved!!.nextRunAt!! > System.currentTimeMillis())
        assertEquals(store.saved, dispatcher.lastDispatched)
        repository.setEnabled("sched_test", false)
        assertNull(store.saved!!.nextRunAt)
        assertEquals(1, dispatcher.cancelled)
    }
}

private fun manager(
    runtime: FakeLinuxRuntime,
    repository: FakeWorkflowRepository,
): WorkflowRunManager = WorkflowRunManager(
    scheduler = WorkflowScheduler(emptySet(), WorkflowApprovalBroker()),
    repository = repository,
    linuxRuntime = runtime,
    json = testJson(),
    logger = AppLogger(ContextWrapper(null)) { it },
)

private fun testJson(): Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private fun logRow(json: Json, state: WorkflowRuntimeState, triggerSource: String, scheduleId: String?) =
    WorkflowExecutionLogEntity(
        executionId = state.executionId,
        workflowId = state.definition.id,
        startTime = 1L,
        endTime = null,
        status = state.status.name,
        finalContextJson = json.encodeToString(WorkflowRuntimeState.serializer(), state),
        triggerSource = triggerSource,
        workflowName = state.definition.name,
        scheduleId = scheduleId,
    )

private fun scheduleEntity(
    repeatType: String,
    hour: Int? = null,
    minute: Int? = null,
    intervalMinutes: Int? = null,
    onceAt: Long? = null,
): WorkflowScheduleEntity = WorkflowScheduleEntity(
    id = "sched_test",
    workflowId = "wf_test",
    name = "测试计划",
    enabled = true,
    repeatType = repeatType,
    hour = hour,
    minute = minute,
    intervalMinutes = intervalMinutes,
    onceAtEpochMillis = onceAt,
    variablesJson = "{}",
    workspacePath = "/workspace",
    modelId = null,
    modelVariant = null,
    lastExecutionId = null,
    lastRunAt = null,
    nextRunAt = null,
    createdAt = 0L,
)

private fun localTime(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
    Calendar.getInstance().apply {
        clear()
        set(year, month - 1, day, hour, minute, 0)
    }.timeInMillis

private class FakeScheduleStore : WorkflowScheduleStore {
    var saved: WorkflowScheduleEntity? = null
    override fun observeSchedules(): Flow<List<WorkflowScheduleEntity>> = MutableStateFlow(emptyList())
    override suspend fun findSchedule(id: String): WorkflowScheduleEntity? = saved?.takeIf { it.id == id }
    override suspend fun upsert(entity: WorkflowScheduleEntity) { saved = entity }
    override suspend fun delete(id: String) = Unit
    override suspend fun setEnabled(id: String, enabled: Boolean) = Unit
    override suspend fun updateRunInfo(id: String, executionId: String?, runAt: Long, nextRunAt: Long?) = Unit
}

private class RecordingDispatcher : WorkflowScheduleDispatcher {
    var dispatched = 0
    var cancelled = 0
    var lastDispatched: WorkflowScheduleEntity? = null
    override fun dispatch(entity: WorkflowScheduleEntity) {
        dispatched++
        lastDispatched = entity
    }

    override fun cancel(scheduleId: String) {
        cancelled++
    }
}
