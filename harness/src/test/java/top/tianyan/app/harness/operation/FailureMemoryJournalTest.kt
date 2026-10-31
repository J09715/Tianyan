package top.tianyan.app.harness.operation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.database.*
import top.tianyan.app.harness.*
import top.tianyan.app.harness.events.HarnessEventBus

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FailureMemoryJournalTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: HarnessRuntimeRepository
    private lateinit var coordinator: OperationCoordinator
    private val json = Json { ignoreUnknownKeys = true }
    @Before fun prepare() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = RoomHarnessRuntimeRepository(db.harnessRuntimeDao())
        coordinator = freshCoordinator()
    }
    @After fun close() { db.close() }
    private fun freshCoordinator(repo: HarnessRuntimeRepository = repository) = OperationCoordinator(repo, json, HarnessEventBus())
    private suspend fun start(session: String = "s", lane: String = "main") =
        coordinator.acceptRun(session, UserMessage("input-$session-$lane", 1, "goal"), lane)
    private fun call(id: String = "call", command: String = "SECRET-COMMAND") =
        ToolCall(id, 2, HarnessTool.BASE, buildJsonObject { put("command", command) }, rawToolName = "base")
    private suspend fun fail(operation: String, call: ToolCall = call(), resultId: String = "result") {
        coordinator.toolIntent(operation, call, call.args.toString(), ReplayPolicy.NEVER, 1)
        coordinator.toolSettled(operation, ToolResult(resultId, 3, call.id, false, "SECRET-OUTPUT"), 1)
    }
    private suspend fun snapshot(operation: String) = json.decodeFromString(OperationSnapshot.serializer(), repository.findOperation(operation)!!.stateJson)

    @Test fun committedFailureSurvivesRestartProviderRoundsApprovalAndSuspension() = runBlocking {
        val operation = start()
        fail(operation)
        val expected = snapshot(operation).failureMemory
        assertEquals("result", expected!!.records.single().resultEntryId)
        assertNotNull(repository.findEntry("s", "result"))
        coordinator.providerIntent(operation, "response", 2, 1, 3)
        coordinator.providerSettled(operation, AssistantText("response", 4, "claimed fixed"), round = 2)
        coordinator.waitingApproval(operation)
        coordinator.suspendOperation(operation, "restart")
        assertEquals(expected, snapshot(operation).failureMemory)
        val restored = freshCoordinator().failureMemoryBlock("s")
        assertTrue(restored.contains("call / result"))
        assertFalse(restored.contains("SECRET"))
    }

    @Test fun newOperationAndOtherLaneNeverInheritPriorFailures() = runBlocking {
        val first = start()
        fail(first)
        assertTrue(coordinator.failureMemoryBlock("other").isEmpty())
        val child = start(lane = "child")
        assertTrue(coordinator.failureMemoryBlock("s", "child").isEmpty())
        fail(child, call("child-call", "different"), "child-result")
        assertFalse(coordinator.failureMemoryBlock("s").contains("child-call"))
        assertFalse(coordinator.failureMemoryBlock("s", "child").contains("call / result"))
        coordinator.finish("s", "failed")
        assertTrue(coordinator.failureMemoryBlock("s").isEmpty())
        coordinator.acceptRun("s", UserMessage("new-input", 5, "new task"))
        assertTrue(coordinator.failureMemoryBlock("s").isEmpty())
    }

    @Test fun resultInsertFailureRollsBackMemoryAndPreservesPendingIntent() = runBlocking {
        val operation = start()
        val call = call()
        coordinator.toolIntent(operation, call, call.args.toString(), ReplayPolicy.NEVER, 1)
        val before = repository.findOperation(operation)
        val conflicting = object : HarnessRuntimeRepository by repository {
            override suspend fun settleEffect(entry: HarnessEntryEntity?, usage: HarnessUsageEntity?,
                operation: HarnessOperationEntity, lane: HarnessLaneEntity) {
                // Inject a real DAO insertion conflict within the production transaction boundary.
                db.harnessRuntimeDao().settleEffect(entry!!.copy(id = call.id), usage, operation, lane)
            }
        }
        val failure = runCatching { freshCoordinator(conflicting).toolSettled(operation,
            ToolResult("result", 3, call.id, false, "failure"), 1) }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(before, repository.findOperation(operation))
        assertTrue(snapshot(operation).failureMemory!!.records.isEmpty())
        assertTrue(coordinator.failureMemoryBlock("s").isEmpty())
        assertNull(repository.findEntry("s", "result"))
        assertEquals(call.id, repository.findLane("s", "main")!!.leafId)
    }

    @Test fun sourceReadFailureCannotCommitAnUnsupportedMemoryOrResult() = runBlocking {
        val operation = start()
        val call = call()
        coordinator.toolIntent(operation, call, call.args.toString(), ReplayPolicy.NEVER, 1)
        val before = repository.findOperation(operation)
        val broken = object : HarnessRuntimeRepository by repository {
            override suspend fun findEntry(sessionId: String, entryId: String): HarnessEntryEntity? = error("source unavailable")
        }
        assertNotNull(runCatching { freshCoordinator(broken).toolSettled(operation, ToolResult("result", 3, call.id, false, "failure"), 1) }.exceptionOrNull())
        assertEquals(before, repository.findOperation(operation))
        assertNull(repository.findEntry("s", "result"))
    }

    @Test fun parallelToolSettlementsRetainEveryFailureAndTheCommittedBranch() = runBlocking {
        val operation = start()
        val calls = (1..8).map { call("call-$it", "command-$it") }
        calls.forEach { coordinator.toolIntent(operation, it, it.args.toString(), ReplayPolicy.NEVER, 1) }
        withTimeout(10_000) {
            calls.map { call -> async(Dispatchers.Default) {
                coordinator.toolSettled(operation, ToolResult("result-${call.id}", 3, call.id, false, "failed"), 1)
            } }.awaitAll()
        }
        assertEquals(8, snapshot(operation).failureMemory!!.records.size)
        val lane = repository.findLane("s", "main")!!
        val history = repository.branch("s", lane.leafId)
        assertEquals(8, history.count { it.customType == "tool_result" })
        assertEquals(8, history.count { it.customType == "tool_call" })
    }

    @Test fun changedOperationPointerDuringReadDiscardsObsoleteMemory() = runBlocking {
        val operation = start()
        fail(operation)
        val moving = object : HarnessRuntimeRepository by repository {
            override suspend fun findOperation(operationId: String): HarnessOperationEntity? {
                val result = repository.findOperation(operationId)
                repository.clearLaneOperation("s", "main")
                return result
            }
        }
        assertTrue(freshCoordinator(moving).failureMemoryBlock("s").isEmpty())
    }

    @Test fun foreignOperationPointerFailsBeforeMemoryDisclosure() = runBlocking {
        val operation = start()
        fail(operation)
        val other = repository.ensureLane("other", "main")
        db.harnessRuntimeDao().upsertLane(other.copy(currentOperationId = operation))
        assertNotNull(runCatching { coordinator.failureMemoryBlock("other") }.exceptionOrNull())
    }

    @Test fun deletingReviewedFailuresPersistsAndPreservesPendingRecoveryAndNewFailures() = runBlocking {
        val operation = start()
        fail(operation)
        val reviewed = coordinator.failureMemories.inspect("s")!!
        fail(operation, call("new-call", "new-command"), "new-result")
        val pending = call("pending-call", "pending-command")
        coordinator.toolIntent(operation, pending, pending.args.toString(), ReplayPolicy.NEVER, 3)
        val before = repository.findOperation(operation)!!
        assertTrue(coordinator.failureMemories.delete("s", reviewed))
        val after = repository.findOperation(operation)!!
        assertEquals(before.copy(stateJson = after.stateJson), after)
        assertEquals(snapshot(operation).copy(failureMemory = null),
            json.decodeFromString(OperationSnapshot.serializer(), before.stateJson).copy(failureMemory = null))
        val restored = freshCoordinator().failureMemories.inspect("s")!!
        assertEquals("new-result", restored.records.single().resultEntryId)
        assertNotNull(repository.findEntry("s", "result"))
        assertFalse(coordinator.failureMemoryBlock("s").contains("call / result"))
        assertTrue(coordinator.failureMemories.delete("s", restored))
        coordinator.providerIntent(operation, "response", 4, 1, 1)
        assertTrue(freshCoordinator().failureMemoryBlock("s").isEmpty())
    }

    @Test fun staleDeletionDoesNotRemoveRefailedArgumentsOrAnotherOperationOrLane() = runBlocking {
        val operation = start()
        fail(operation)
        val reviewed = coordinator.failureMemories.inspect("s")!!
        fail(operation, call("retried-call"), "retried-result")
        assertTrue(coordinator.failureMemories.delete("s", reviewed))
        assertEquals("retried-result", coordinator.failureMemories.inspect("s")!!.records.single().resultEntryId)
        val child = start(lane = "child")
        fail(child, call("child-call"), "child-result")
        assertFalse(coordinator.failureMemories.delete("s", reviewed, "child"))
        assertEquals(1, coordinator.failureMemories.inspect("s", "child")!!.records.size)
        coordinator.finish("s", "aborted")
        val next = coordinator.acceptRun("s", UserMessage("next", 5, "next task"))
        fail(next, call("next-call"), "next-result")
        assertFalse(coordinator.failureMemories.delete("s", reviewed))
        assertEquals("next-result", coordinator.failureMemories.inspect("s")!!.records.single().resultEntryId)
    }

    @Test fun observerRefreshesAfterDeletionWithoutChangingLaneOrHistory() = runBlocking {
        val operation = start()
        fail(operation)
        val reviewed = coordinator.failureMemories.observe("s").first { !it?.records.isNullOrEmpty() }!!
        val lane = repository.findLane("s", "main")
        val observer = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { coordinator.failureMemories.observe("s").first { it?.records?.isEmpty() == true } }
        }
        coordinator.failureMemories.delete("s", reviewed)
        assertTrue(observer.await()!!.records.isEmpty())
        assertEquals(lane, repository.findLane("s", "main"))
        assertNotNull(repository.findEntry("s", "result"))
    }

    @Test fun deletionAndConcurrentSettlementDoNotRestoreOldRecordsOrLoseNewOnes() = runBlocking {
        val operation = start()
        fail(operation)
        val reviewed = coordinator.failureMemories.inspect("s")!!
        val nextCall = call("next-call", "different-command")
        coordinator.toolIntent(operation, nextCall, nextCall.args.toString(), ReplayPolicy.NEVER, 2)
        withTimeout(5_000) {
            listOf(async(Dispatchers.Default) { coordinator.failureMemories.delete("s", reviewed) },
                async(Dispatchers.Default) { coordinator.toolSettled(operation,
                    ToolResult("next-result", 4, nextCall.id, false, "failure"), 2) }).awaitAll()
        }
        assertEquals("next-result", coordinator.failureMemories.inspect("s")!!.records.single().resultEntryId)
    }
}
