package top.tianyan.app.harness.session

import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.harness.PendingMessage
import top.tianyan.app.harness.queue.PromptQueue
import top.tianyan.app.harness.session.PromptSubmission.*

class SessionInputControllerTest {
    private class Runtime : SessionInputRuntime {
        var exists = true
        var busy = false
        var backlog = false
        var started = true
        val events = mutableListOf<String>()
        val recorded = mutableListOf<PendingMessage>()
        val enqueued = mutableListOf<Pair<PromptQueue, PendingMessage>>()
        var onExists: suspend () -> Unit = {}
        var onRecord: suspend () -> Unit = {}
        var onEnqueue: suspend () -> Unit = {}
        var onStart: suspend () -> Unit = {}
        var onRefresh: suspend () -> Unit = {}
        val refreshFailures = mutableListOf<Exception>()
        override suspend fun exists(sessionId: String): Boolean { events += "exists:$sessionId"; onExists(); return exists }
        override fun busy(sessionId: String) = busy
        override suspend fun settleExpiredApprovals(sessionId: String) { events += "settle" }
        override suspend fun hasNextRun(sessionId: String) = backlog
        override suspend fun record(sessionId: String, input: PendingMessage) {
            events += "record"; onRecord(); recorded += input
        }
        override suspend fun enqueue(sessionId: String, queue: PromptQueue, input: PendingMessage): String {
            events += "enqueue"; onEnqueue(); enqueued += queue to input; return "item-${enqueued.size}"
        }
        override suspend fun start(sessionId: String, input: PendingMessage): Boolean {
            events += "start"; onStart(); if (started) busy = true; return started
        }
        override suspend fun startNext(sessionId: String) { events += "startNext"; busy = true }
        override suspend fun refresh(sessionId: String) { events += "refresh:$sessionId"; onRefresh() }
        override fun refreshFailed(sessionId: String, failure: Exception) { refreshFailures += failure }
    }
    private fun controller(runtime: Runtime, coordinator: SessionTurnCoordinator = SessionTurnCoordinatorImpl()) =
        SessionInputController(coordinator, runtime)

    @Test fun `host business identity is captured before launch and survives queued serialization without leaking to next user input`() = runBlocking {
        val runtime = Runtime()
        runtime.onStart = { assertEquals("work", runtime.recorded.single().groupWorkId) }
        withContext(top.tianyan.app.harness.groupchat.GroupWorkContext("work", "g")) {
            controller(runtime).submit("s", "start", emptyList(), PromptQueue.NEXT_RUN)
            controller(runtime).submit("s", "next", emptyList(), PromptQueue.NEXT_RUN)
        }
        val queued = runtime.enqueued.single().second
        val payload = kotlinx.serialization.json.Json.encodeToString(PendingMessage.serializer(), queued)
        val restored = kotlinx.serialization.json.Json.decodeFromString(PendingMessage.serializer(), payload)
        assertEquals("work", restored.groupWorkId)
        assertEquals("g", restored.groupId)
        controller(runtime).submit("s", "new user task", emptyList(), PromptQueue.NEXT_RUN)
        assertNull(runtime.recorded.last().groupWorkId)
        assertNull(runtime.recorded.last().groupId)
    }

    @Test fun emptyInputAndBlankSessionProduceNoSideEffects() = runBlocking {
        val runtime = Runtime(); val controller = controller(runtime)
        assertEquals(Rejected(Rejection.EMPTY_INPUT), controller.submit("s", "  ", emptyList(), PromptQueue.NEXT_RUN))
        assertEquals(Rejected(Rejection.INVALID_SESSION), controller.submit(" ", "hello", emptyList(), PromptQueue.NEXT_RUN))
        assertTrue(runtime.events.isEmpty())
    }

    @Test fun startedAcknowledgmentWaitsForRecordAndStart() = runBlocking {
        val runtime = Runtime(); val gate = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        runtime.onStart = { entered.complete(Unit); gate.await() }
        val request = async { controller(runtime).submit("remote", " hi ", emptyList(), PromptQueue.NEXT_RUN) }
        entered.await()
        assertFalse(request.isCompleted)
        assertEquals("hi", runtime.recorded.single().text)
        gate.complete(Unit)
        val accepted = request.await() as Accepted
        assertEquals(Disposition.STARTED, accepted.disposition)
        assertEquals(runtime.recorded.single().taskId, accepted.taskId)
        assertNull(accepted.queueItemId)
        assertEquals(listOf("exists:remote", "settle", "record", "start", "refresh:remote"), runtime.events)
    }

    @Test fun busyNextRunAcknowledgmentWaitsForDurableEnqueue() = runBlocking {
        val runtime = Runtime().apply { busy = true }
        val gate = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        runtime.onEnqueue = { entered.complete(Unit); gate.await() }
        val request = async { controller(runtime).submit("s", "later", emptyList(), PromptQueue.NEXT_RUN) }
        entered.await(); assertFalse(request.isCompleted); assertEquals(1, runtime.recorded.size)
        gate.complete(Unit)
        val result = request.await() as Accepted
        assertEquals(Disposition.QUEUED, result.disposition)
        assertEquals(PromptQueue.NEXT_RUN, result.queue)
        assertEquals("item-1", result.queueItemId)
        assertEquals(runtime.recorded.single().taskId, runtime.enqueued.single().second.taskId)
        assertFalse(runtime.events.contains("start"))
    }

    @Test fun steeringAndFollowUpAttachToCurrentTaskWhenBusy() = runBlocking {
        for (queue in listOf(PromptQueue.STEER, PromptQueue.FOLLOW_UP)) {
            val runtime = Runtime().apply { busy = true }
            val accepted = controller(runtime).submit("s", "instruction", emptyList(), queue) as Accepted
            assertEquals(queue, accepted.queue)
            assertNull(accepted.taskId)
            assertTrue(runtime.recorded.isEmpty())
            assertEquals(queue, runtime.enqueued.single().first)
        }
    }

    @Test fun explicitInputStartsNewTaskWhenIdle() = runBlocking {
        for (queue in listOf(PromptQueue.STEER, PromptQueue.FOLLOW_UP)) {
            val runtime = Runtime()
            val accepted = controller(runtime).submit("s", "instruction", emptyList(), queue) as Accepted
            assertEquals(Disposition.STARTED, accepted.disposition)
            assertNotNull(accepted.taskId)
            assertTrue(runtime.enqueued.isEmpty())
        }
    }

    @Test fun existingNextRunBacklogKeepsFifoBeforeNewInput() = runBlocking {
        val runtime = Runtime().apply { backlog = true }
        val result = controller(runtime).submit("s", "new", emptyList(), PromptQueue.NEXT_RUN) as Accepted
        assertEquals(Disposition.QUEUED, result.disposition)
        assertEquals(listOf("exists:s", "settle", "record", "enqueue", "startNext", "refresh:s"), runtime.events)
    }

    @Test fun deletionWhileWaitingForSessionLockCannotCreateOrphanTask() = runBlocking {
        val runtime = Runtime(); val coordinator = SessionTurnCoordinatorImpl()
        val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        val deletion = launch { coordinator.withSessionMutex("s") { entered.complete(Unit); gate.await(); runtime.exists = false } }
        entered.await()
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            controller(runtime, coordinator).submit("s", "late", emptyList(), PromptQueue.NEXT_RUN)
        }
        assertTrue(runtime.events.isEmpty())
        gate.complete(Unit); deletion.join()
        assertEquals(Rejected(Rejection.SESSION_NOT_FOUND), request.await())
        assertEquals(listOf("exists:s"), runtime.events)
    }

    @Test fun concurrentInputsOccupyExactlyOneSessionSlot() = runBlocking {
        val runtime = Runtime(); val controller = controller(runtime)
        val results = (1..20).map { async { controller.submit("s", "input-$it", emptyList(), PromptQueue.NEXT_RUN) } }.awaitAll()
        assertEquals(1, results.count { (it as Accepted).disposition == Disposition.STARTED })
        assertEquals(19, runtime.enqueued.size)
        assertEquals(20, runtime.recorded.map { it.taskId }.distinct().size)
    }

    @Test fun attachmentSnapshotSurvivesCallerMutationWhileWaiting() = runBlocking {
        val runtime = Runtime(); val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        val images = mutableListOf("data:image/png;base64,original")
        runtime.onExists = { entered.complete(Unit); gate.await() }
        val request = async { controller(runtime).submit("s", "", images, PromptQueue.NEXT_RUN) }
        entered.await(); images[0] = "changed"; gate.complete(Unit)
        assertTrue(request.await() is Accepted)
        assertEquals(listOf("data:image/png;base64,original"), runtime.recorded.single().imageUrls)
    }

    @Test fun persistenceFailurePropagatesWithoutLaunchingOrAcknowledging() = runBlocking {
        for (stage in listOf("record", "enqueue")) {
            val runtime = Runtime().apply { busy = true }
            val failure = IOException("storage unavailable")
            if (stage == "record") runtime.onRecord = { throw failure } else runtime.onEnqueue = { throw failure }
            try { controller(runtime).submit("s", "hello", emptyList(), PromptQueue.NEXT_RUN); fail("must throw") }
            catch (caught: IOException) { assertSame(failure, caught) }
            assertFalse(runtime.events.any { it.startsWith("refresh") || it == "start" })
        }
    }

    @Test fun failedLaunchReturnsRejection() = runBlocking {
        val runtime = Runtime().apply { started = false }
        assertEquals(Rejected(Rejection.LAUNCH_REJECTED), controller(runtime).submit("s", "hello", emptyList(), PromptQueue.NEXT_RUN))
    }

    @Test fun projectionFailureCannotRejectAnAlreadyStartedOrQueuedInput() = runBlocking {
        for (busy in listOf(false, true)) {
            val failure = IOException("projection unavailable")
            val runtime = Runtime().apply { this.busy = busy; onRefresh = { throw failure } }
            val result = controller(runtime).submit("s", "hello", emptyList(), PromptQueue.NEXT_RUN) as Accepted
            assertEquals(if (busy) Disposition.QUEUED else Disposition.STARTED, result.disposition)
            assertEquals(listOf(failure), runtime.refreshFailures)
            assertEquals(1, runtime.recorded.size)
        }
    }

    @Test fun projectionFailureAfterCancellationStillPropagatesCancellation() = runBlocking {
        val runtime = Runtime()
        val request = async {
            val job = currentCoroutineContext().job
            runtime.onRefresh = { withContext(NonCancellable) {
                job.cancel(); throw IOException("late projection failure")
            } }
            controller(runtime).submit("s", "hello", emptyList(), PromptQueue.NEXT_RUN)
        }
        try { request.await(); fail("must cancel") } catch (_: CancellationException) { }
        assertTrue(runtime.refreshFailures.isEmpty())
    }

    @Test fun nonCancellableRepositoryReturnCannotMaskCallerCancellation() = runBlocking {
        for (stage in listOf("exists", "record", "enqueue")) {
            val runtime = Runtime().apply { busy = true }
            val request = async {
                val job = currentCoroutineContext().job
                val cancel: suspend () -> Unit = { withContext(NonCancellable) { job.cancel() } }
                when (stage) {
                    "exists" -> runtime.onExists = cancel
                    "record" -> runtime.onRecord = cancel
                    "enqueue" -> runtime.onEnqueue = cancel
                }
                controller(runtime).submit("s", "hello", emptyList(), PromptQueue.NEXT_RUN)
            }
            try { request.await(); fail("must propagate cancellation") } catch (_: CancellationException) { }
            assertFalse(runtime.events.any { it.startsWith("refresh") || it == "start" })
            if (stage == "exists") assertTrue(runtime.recorded.isEmpty())
        }
    }
    @Test fun lateStorageExceptionCannotReplaceCancellation() = runBlocking {
        val runtime = Runtime()
        val request = async {
            val job = currentCoroutineContext().job
            runtime.onExists = { withContext(NonCancellable) {
                job.cancel(); throw IOException("late storage failure")
            } }
            controller(runtime).submit("s", "hello", emptyList(), PromptQueue.NEXT_RUN)
        }
        try { request.await(); fail("must cancel") } catch (_: CancellationException) { }
        assertEquals(listOf("exists:s"), runtime.events)
    }
}
