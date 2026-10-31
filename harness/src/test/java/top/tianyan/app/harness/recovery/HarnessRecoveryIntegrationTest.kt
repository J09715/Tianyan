package top.tianyan.app.harness.recovery

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.database.AppDatabase
import top.tianyan.app.core.database.HarnessRuntimeDao
import top.tianyan.app.core.database.HarnessRuntimeRepository
import top.tianyan.app.core.database.HarnessEntryEntity
import top.tianyan.app.core.database.HarnessLaneEntity
import top.tianyan.app.core.database.HarnessOperationEntity
import top.tianyan.app.core.database.HarnessUsageEntity
import top.tianyan.app.core.database.HarnessLaneResultEntity
import top.tianyan.app.core.database.RoomHarnessRuntimeRepository
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.harness.UserMessage
import top.tianyan.app.harness.events.HarnessEventBus
import top.tianyan.app.harness.operation.OperationCoordinator
import top.tianyan.app.harness.operation.OperationPhase
import top.tianyan.app.harness.operation.OperationStatus
import top.tianyan.app.harness.operation.ReplayPolicy
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.HarnessTool
import top.tianyan.app.harness.events.HarnessEvent
import top.tianyan.app.harness.core.DurableToolRunner
import top.tianyan.app.harness.effects.ToolRecoveryNotice

/**
 * 崩溃恢复集成测试：真实 Room 持久化 + OperationCoordinator + RecoveryManager 全链路。
 * 模拟"进程死亡"的方式是丢弃内存中的对象、仅从数据库重建 coordinator——
 * 与真机进程重启后 loadSession → recoverSession 的路径一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HarnessRecoveryIntegrationTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: HarnessRuntimeRepository
    private lateinit var eventBus: HarnessEventBus
    private lateinit var coordinator: OperationCoordinator
    private lateinit var recoveryManager: RecoveryManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomHarnessRuntimeRepository(database.harnessRuntimeDao())
        eventBus = HarnessEventBus()
        rebuildRuntime()
    }

    @After
    fun tearDown() {
        database.close()
    }

    /** 模拟进程重启：一切内存对象丢弃，仅数据库保留。 */
    private fun rebuildRuntime() {
        coordinator = OperationCoordinator(repository, Json, eventBus)
        recoveryManager = RecoveryManager(repository, coordinator, approvalRepository = null, json = Json, eventBus = eventBus)
    }

    private fun user(text: String) = UserMessage(id = "u-$text", createdAt = 1L, text = text)

    @Test
    fun `clean session has nothing to recover`() = runBlocking {
        val outcome = recoveryManager.recoverSession("clean-session")
        assertTrue(outcome is RecoveryOutcome.Clean)
    }

    @Test
    fun `provider intent crash suspends operation and is reclaimed by next run`() = runBlocking {
        val sessionId = "s-provider"
        val operationId = coordinator.acceptRun(sessionId, user("hi"))
        // 崩溃：模型请求已发出（intent 落盘）但结果未写入
        coordinator.providerIntent(operationId, "assistant-1", round = 0, attempt = 1, maxAttempts = 1)

        rebuildRuntime()

        val outcome = recoveryManager.recoverSession(sessionId)
        assertTrue("模型请求中断应进入 Suspended", outcome is RecoveryOutcome.Suspended)

        // 恢复后：状态挂起，但 lane 仍被占用
        val suspended = repository.findOperation(operationId)!!
        assertEquals(OperationStatus.SUSPENDED.id, suspended.status)

        // 用户直接发新消息：接管旧操作，正常运行
        rebuildRuntime()
        val freshOperationId = coordinator.acceptRun(sessionId, user("again"))
        assertTrue(freshOperationId != operationId)
        assertEquals(freshOperationId, repository.findLane(sessionId, "main")!!.currentOperationId)
        // 旧操作被 finish，历史 entry 全部保留
        assertEquals("aborted", database.harnessRuntimeDao().let { dao ->
            dao.listActiveOperations(sessionId).none { it.id == operationId }.let { if (it) "aborted" else "still-active" }
        })
        assertTrue(repository.listEntries(sessionId).size >= 2)
    }

    @Test
    fun `never replay tool gets interrupted result instead of re-execution`() = runBlocking {
        val sessionId = "s-tool"
        val operationId = coordinator.acceptRun(sessionId, user("rm something"))
        val toolCall = ToolCall(
            id = "call-1",
            createdAt = 2L,
            tool = HarnessTool.BASE,
            args = buildJsonObject {
                put("command", JsonPrimitive("rm -rf /tmp/x"))
            },
            rawToolName = "base",
        )
        // 崩溃：工具 intent 已落盘（NEVER 策略），执行结果未写入
        coordinator.toolIntent(operationId, toolCall, """{"command":"rm -rf /tmp/x"}""", ReplayPolicy.NEVER, round = 0)

        rebuildRuntime()

        val outcome = recoveryManager.recoverSession(sessionId)
        assertTrue("不可重放工具应返回 ToolInterrupted", outcome is RecoveryOutcome.ToolInterrupted)
        assertEquals("call-1", (outcome as RecoveryOutcome.ToolInterrupted).toolCallId)

        // 中断结果作为 ToolResult entry 写入树中（不重放执行）
        val entries = repository.listEntries(sessionId)
        val toolResult = entries.mapNotNull { entry ->
            runCatching { Json.decodeFromString(HarnessMessage.serializer(), entry.payloadJson) }
                .getOrNull() as? ToolResult
        }.singleOrNull()
        assertTrue("应写入一条中断 ToolResult", toolResult != null)
        assertEquals("call-1", toolResult!!.toolCallId)
        assertTrue(!toolResult.success)
        assertTrue(toolResult.output.contains("未再次执行"))
        assertEquals(ToolRecoveryNotice.OUTCOME_UNKNOWN, toolResult.errorCode)
        assertTrue(toolResult.output.contains("禁止直接重新发起"))
    }

    @Test
    fun `side effect survives result commit failure and repeated recovery never executes again`() = runBlocking {
        val sessionId = "s-commit-failure"
        val operationId = coordinator.acceptRun(sessionId, user("write"))
        val call = ToolCall("write-call", 2L, HarnessTool.WRITE, buildJsonObject { })
        val failure = IllegalStateException("result storage unavailable")
        val durableRepository = repository
        val failingRepository = object : HarnessRuntimeRepository by durableRepository {
            override suspend fun settleEffect(entry: HarnessEntryEntity?, usage: HarnessUsageEntity?,
                operation: HarnessOperationEntity, lane: HarnessLaneEntity) {
                if (operation.phase == OperationPhase.TOOL_SETTLED.id) throw failure
                durableRepository.settleEffect(entry, usage, operation, lane)
            }
        }
        val failingCoordinator = OperationCoordinator(failingRepository, Json, eventBus)
        val externalFile = StringBuilder()
        val actual = runCatching {
            DurableToolRunner.run(
                commitIntent = { failingCoordinator.toolIntent(operationId, call, "{}", ReplayPolicy.NEVER, 0) },
                execute = {
                    externalFile.append("written once")
                    ToolResult("original-result", 3L, call.id, true, "written")
                },
                commitResult = { failingCoordinator.toolSettled(operationId, it, 0) },
                executionFailure = { error("must not convert storage failure") },
            )
        }.exceptionOrNull()
        assertSame(failure, actual)
        assertEquals(OperationPhase.TOOL_INTENT.id, repository.findOperation(operationId)?.phase)
        assertTrue(results(sessionId).isEmpty())

        rebuildRuntime()
        assertTrue(recoveryManager.recoverSession(sessionId) is RecoveryOutcome.ToolInterrupted)
        rebuildRuntime()
        recoveryManager.recoverSession(sessionId)
        val result = results(sessionId).single()
        assertEquals(ToolRecoveryNotice.OUTCOME_UNKNOWN, result.errorCode)
        assertEquals(call.id, result.toolCallId)
        assertTrue(result.output.contains("结果未知"))
        assertEquals("written once", externalFile.toString())
    }

    @Test
    fun `committed success is preserved by recovery`() = runBlocking {
        val sessionId = "s-settled"
        val operationId = coordinator.acceptRun(sessionId, user("write"))
        val call = ToolCall("settled-call", 2L, HarnessTool.WRITE, buildJsonObject { })
        coordinator.toolIntent(operationId, call, "{}", ReplayPolicy.NEVER, 0)
        val result = ToolResult("settled-result", 3L, call.id, true, "written")
        coordinator.toolSettled(operationId, result, 0)
        rebuildRuntime()
        recoveryManager.recoverSession(sessionId)
        assertEquals(result, results(sessionId).single())
    }

    @Test
    fun `missing replay declaration is treated as unknown instead of safe`() = runBlocking {
        val sessionId = "s-missing-replay"
        val operationId = coordinator.acceptRun(sessionId, user("write"))
        val call = ToolCall("unknown-policy", 2L, HarnessTool.WRITE, buildJsonObject { })
        coordinator.toolIntent(operationId, call, "{}", ReplayPolicy.NEVER, 0)
        repository.saveOperation(repository.findOperation(operationId)!!.copy(replayPolicy = null))
        rebuildRuntime()
        assertTrue(recoveryManager.recoverSession(sessionId) is RecoveryOutcome.ToolInterrupted)
        assertEquals(ToolRecoveryNotice.OUTCOME_UNKNOWN, results(sessionId).single().errorCode)
    }

    private suspend fun results(sessionId: String): List<ToolResult> = repository.listEntries(sessionId)
        .filter { it.entryType == "message" }
        .map { Json.decodeFromString(HarnessMessage.serializer(), it.payloadJson) }
        .filterIsInstance<ToolResult>()

    @Test
    fun `safe replay tool interrupts with distinct reason`() = runBlocking {
        val sessionId = "s-safe"
        val operationId = coordinator.acceptRun(sessionId, user("read file"))
        val toolCall = ToolCall(
            id = "call-2",
            createdAt = 2L,
            tool = HarnessTool.READ,
            args = buildJsonObject {
                put("path", JsonPrimitive("a.txt"))
            },
        )
        coordinator.toolIntent(operationId, toolCall, """{"path":"a.txt"}""", ReplayPolicy.SAFE, round = 0)

        rebuildRuntime()

        val outcome = recoveryManager.recoverSession(sessionId)
        assertTrue(outcome is RecoveryOutcome.Suspended)
        assertTrue((outcome as RecoveryOutcome.Suspended).reason.contains("可安全重放"))
    }

    @Test
    fun `corrupted operation state suspends without crashing recovery`() = runBlocking {
        val sessionId = "s-corrupt"
        val operationId = coordinator.acceptRun(sessionId, user("hi"))
        // 直接破坏持久化状态（模拟半截写入）
        val dao = database.harnessRuntimeDao()
        val corrupted = repository.findOperation(operationId)!!.copy(stateJson = "{not-valid-json")
        dao.upsertOperation(corrupted)

        rebuildRuntime()

        val outcome = recoveryManager.recoverSession(sessionId)
        assertTrue("损坏状态应进入 Suspended 而非抛异常", outcome is RecoveryOutcome.Suspended)
        assertTrue((outcome as RecoveryOutcome.Suspended).reason.contains("运行状态损坏"))
    }

    @Test
    fun `recovery suspends main and subagent lanes`() = runBlocking {
        val sessionId = "s-multi-lane"
        val mainId = coordinator.acceptRun(sessionId, user("main"))
        val subId = coordinator.beginRun(sessionId, "subagent-one")
        coordinator.providerIntent(mainId, "main-answer", 0, 1, 1)
        coordinator.providerIntent(subId, "sub-answer", 0, 1, 1)
        rebuildRuntime()

        assertTrue(recoveryManager.recoverSession(sessionId) is RecoveryOutcome.Suspended)
        assertEquals(OperationStatus.SUSPENDED.id, repository.findOperation(mainId)?.status)
        assertEquals(OperationStatus.SUSPENDED.id, repository.findOperation(subId)?.status)
    }

    @Test
    fun `finishing an old operation preserves a newer lane owner`() = runBlocking {
        val sessionId = "s-lane-cas"
        val oldId = coordinator.acceptRun(sessionId, user("old"))
        val dao = database.harnessRuntimeDao()
        val oldLane = repository.findLane(sessionId, "main")!!
        dao.upsertLane(oldLane.copy(currentOperationId = "new-owner"))

        dao.finishOperation(
            HarnessLaneResultEntity(sessionId, "main", oldId, "aborted", null, null, 9L),
            oldLane.copy(currentOperationId = null, updatedAt = 9L),
        )

        assertEquals("new-owner", repository.findLane(sessionId, "main")?.currentOperationId)
    }

    @Test
    fun `recovery events are observable on the bus`() = runBlocking {
        val sessionId = "s-events"
        val operationId = coordinator.acceptRun(sessionId, user("hi"))
        coordinator.providerIntent(operationId, "a-1", 0, 1, 1)

        val events = mutableListOf<HarnessEvent>()
        val job = GlobalScope.launch(Dispatchers.Unconfined) {
            eventBus.events.collect { events += it }
        }
        try {
            rebuildRuntime()
            recoveryManager.recoverSession(sessionId)
            assertTrue(events.any { it is HarnessEvent.RecoveryApplied })
        } finally {
            job.cancel()
        }
    }
}
