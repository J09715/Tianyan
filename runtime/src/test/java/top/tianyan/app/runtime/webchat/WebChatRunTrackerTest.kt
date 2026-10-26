package top.tianyan.app.runtime.webchat

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test

class WebChatRunTrackerTest {
    private data class Event(val requestId: String, val kind: String, val sessionId: String)
    private class Gateway : WebChatAgentGateway {
        val sessions = mutableMapOf<String, MutableStateFlow<WebChatSessionSnapshot>>()
        val tasks = mutableMapOf<String, MutableStateFlow<WebChatTaskState>>()
        val cancelled = mutableListOf<String>()
        var reads = 0
        var subscriptions = 0
        var beforeRead: suspend () -> Unit = {}
        val finalMessages = listOf(WebChatMessage("final", 0, 1, buildJsonObject { }, 1))
        fun session(id: String = "s") = sessions.getOrPut(id) {
            MutableStateFlow(WebChatSessionSnapshot(emptyList(), running = false, waitingApproval = false))
        }
        fun task(id: String, state: WebChatTaskState = WebChatTaskState.RUNNING) =
            tasks.getOrPut(id) { MutableStateFlow(state) }
        override fun observeSession(sessionId: String): Flow<WebChatSessionSnapshot> {
            subscriptions++; return session(sessionId)
        }
        override fun observeTask(sessionId: String, taskId: String) = task(taskId)
        override suspend fun messages(sessionId: String): List<WebChatMessage> {
            reads++; beforeRead(); return finalMessages
        }
        override fun cancel(sessionId: String) { cancelled += sessionId }
        override suspend fun createSession(title: String, workspace: String): String = error("unused")
        override suspend fun deleteSession(sessionId: String): Unit = error("unused")
        override suspend fun pendingApprovals(sessionId: String): List<WebChatApproval> = error("unused")
        override suspend fun send(sessionId: String, text: String, imageUrls: List<String>, mode: WebChatInputMode):
            WebChatInputReceipt = error("unused")
        override suspend fun resolveApproval(sessionId: String, requestId: String, approved: Boolean): Boolean = error("unused")
        override suspend fun setMessageFeedback(sessionId: String, messageId: String, rating: String, note: String?) = error("unused")
        override suspend fun messageFeedbacks(sessionId: String) = error("unused")
    }
    private class Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gateway = Gateway()
        val events = Channel<Event>(Channel.UNLIMITED)
        val displayed = mutableListOf<WebChatSessionSnapshot>()
        val failures = mutableListOf<Throwable>()
        val tracker = WebChatRunTracker(scope, gateway, { _, session -> displayed += session },
            { id, kind, session, _ -> events.trySend(Event(id, kind, session)).getOrThrow() }, { failures += it })
        suspend fun submit(request: String = "request", durable: String? = "task", session: String = "s") =
            tracker.submit(session, request) { WebChatInputReceipt("started", durable) }
        suspend fun next() = withTimeout(5_000) { events.receive() }
        fun noEvent() = assertTrue(events.tryReceive().isFailure)
        fun close() { tracker.stop(); scope.cancel() }
    }
    private fun contract(block: suspend (Fixture) -> Unit) = runBlocking {
        val fixture = Fixture()
        try { block(fixture) } finally { fixture.close() }
    }
    private fun approval() = WebChatApproval("a", "write", "{}", "", "low", "reason", "summary", 1, Long.MAX_VALUE)

    @Test fun alreadyCompletedTaskReportsCompletionWithoutSeeingRunningAndReloadsFinalMessages() = contract { f ->
        f.gateway.task("task", WebChatTaskState.COMPLETED)
        f.submit()
        assertEquals(Event("request", "completed", "s"), f.next())
        assertEquals(f.gateway.finalMessages, f.displayed.last().messages)
        assertEquals(1, f.gateway.reads)
        assertNull(f.tracker.requestForSession("s"))
        assertFalse(f.tracker.cancel("request"))
    }

    @Test fun failedCancelledSuspendedMissingAndUnknownTasksNeverReportSuccess() = contract { f ->
        for (state in listOf(WebChatTaskState.FAILED, WebChatTaskState.CANCELLED, WebChatTaskState.SUSPENDED,
            WebChatTaskState.MISSING, WebChatTaskState.UNKNOWN)) {
            f.gateway.task(state.name, state)
            f.submit(state.name, state.name)
            assertEquals("error", f.next().kind)
        }
    }

    @Test fun queuedTaskDoesNotCompleteWhenAnotherSessionRunBecomesIdle() = contract { f ->
        f.gateway.task("task", WebChatTaskState.QUEUED)
        f.submit()
        f.gateway.session().value = f.gateway.session().value.copy(running = true)
        yield()
        f.gateway.session().value = f.gateway.session().value.copy(running = false, error = "previous run failure")
        yield(); f.noEvent()
        f.gateway.task("task").value = WebChatTaskState.COMPLETED
        assertEquals("completed", f.next().kind)
    }

    @Test fun successiveRequestsInOneSessionKeepIndependentObservers() = contract { f ->
        f.gateway.task("first", WebChatTaskState.RUNNING)
        f.gateway.task("second", WebChatTaskState.QUEUED)
        f.submit("first-request", "first"); f.submit("second-request", "second")
        f.gateway.task("first").value = WebChatTaskState.COMPLETED
        assertEquals("first-request", f.next().requestId)
        assertEquals("second-request", f.tracker.requestForSession("s"))
        f.gateway.task("second").value = WebChatTaskState.FAILED
        assertEquals(Event("second-request", "error", "s"), f.next())
        f.noEvent()
    }

    @Test fun approvalEventsBelongToWaitingTaskAndDoNotLeakToQueuedSuccessor() = contract { f ->
        f.gateway.session().value = f.gateway.session().value.copy(waitingApproval = true, approvals = listOf(approval()))
        f.gateway.task("waiting", WebChatTaskState.WAITING_APPROVAL)
        f.gateway.task("queued", WebChatTaskState.QUEUED)
        f.submit("waiting-request", "waiting"); f.submit("queued-request", "queued")
        assertEquals(Event("waiting-request", "waiting_approval", "s"), f.next())
        f.noEvent()
        assertEquals("waiting-request", f.tracker.requestForSession("s"))
        f.gateway.task("waiting").value = WebChatTaskState.FAILED
        assertEquals(Event("waiting-request", "error", "s"), f.next())
    }

    @Test fun approvalNotificationWaitsForDetailsAndRepeatsAfterResumption() = contract { f ->
        f.gateway.task("task", WebChatTaskState.WAITING_APPROVAL)
        f.submit(); yield(); f.noEvent()
        f.gateway.session().value = f.gateway.session().value.copy(waitingApproval = true, approvals = listOf(approval()))
        assertEquals("waiting_approval", f.next().kind)
        f.gateway.session().value = f.gateway.session().value.copy(error = "unrelated update")
        yield(); f.noEvent()
        f.gateway.task("task").value = WebChatTaskState.RUNNING
        yield()
        f.gateway.task("task").value = WebChatTaskState.WAITING_APPROVAL
        assertEquals("waiting_approval", f.next().kind)
    }

    @Test fun duplicateRequestIsRejectedBeforeItCanDispatchAnotherInput() = contract { f ->
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val pending = f.scope.async { f.tracker.submit("s", "request") {
            entered.complete(Unit); release.await(); WebChatInputReceipt("queued", "task")
        } }
        entered.await()
        try { f.tracker.submit("other", "request") { fail("must not dispatch"); error("unreachable") }; fail("must reject") }
        catch (failure: IllegalArgumentException) { assertEquals("任务 ID 已在使用", failure.message) }
        assertFalse(f.tracker.cancel("request"))
        release.complete(Unit); pending.await()
        assertEquals("request", f.tracker.requestForSession("s"))
    }

    @Test fun failedAdmissionReleasesCorrelationForRetry() = contract { f ->
        try { f.tracker.submit("s", "request") { throw IOException("disk") }; fail("must throw") }
        catch (_: IOException) { }
        assertEquals(0, f.gateway.subscriptions)
        f.submit()
        assertEquals("request", f.tracker.requestForSession("s"))
    }

    @Test fun stopRequestsTargetSessionAndRemainObservedUntilSettlement() = contract { f ->
        f.submit(session = "remote")
        assertTrue(f.tracker.cancel("request"))
        assertFalse(f.tracker.cancel("request"))
        assertEquals(listOf("remote"), f.gateway.cancelled)
        assertEquals("request", f.tracker.requestForSession("remote"))
        f.gateway.task("task").value = WebChatTaskState.CANCELLED
        assertEquals(Event("request", "error", "remote"), f.next())
        assertNull(f.tracker.requestForSession("remote"))
    }

    @Test fun stoppingServerDuringAdmissionCannotLeaveLateObserverAndRestartAcceptsNewRequests() = contract { f ->
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val pending = f.scope.async { f.tracker.submit("s", "request") {
            entered.complete(Unit); release.await(); WebChatInputReceipt("started", "task")
        } }
        entered.await(); f.tracker.stop(); release.complete(Unit); pending.await()
        assertEquals(0, f.gateway.subscriptions)
        assertNull(f.tracker.requestForSession("s"))
        try { f.submit(); fail("must reject stopped service") } catch (_: IllegalStateException) { }
        f.tracker.enable(); f.submit()
        assertEquals("request", f.tracker.requestForSession("s"))
    }

    @Test fun finalHistoryFailureReportsErrorAndReleasesObserver() = contract { f ->
        val failure = IOException("private storage details")
        f.gateway.beforeRead = { throw failure }
        f.gateway.task("task", WebChatTaskState.COMPLETED)
        f.submit()
        assertEquals("error", f.next().kind)
        assertTrue(f.failures.single() is IOException)
        assertTrue("Failed final history must not overwrite visible messages", f.displayed.isEmpty())
        assertEquals(failure.message, f.failures.single().message)
        assertNull(f.tracker.requestForSession("s"))
        f.noEvent()
    }

    @Test fun steeringWithoutIndependentTaskRetainsSessionFallback() = contract { f ->
        f.gateway.session().value = f.gateway.session().value.copy(running = true)
        f.submit(durable = null)
        yield()
        f.gateway.session().value = f.gateway.session().value.copy(running = false)
        assertEquals("completed", f.next().kind)
    }

    @Test fun nonCancellableFinalReadCannotEmitCompletionAfterObserverCancellation() = contract { f ->
        f.gateway.beforeRead = {
            val job = currentCoroutineContext().job
            withContext(NonCancellable) { job.cancel() }
        }
        f.gateway.task("task", WebChatTaskState.COMPLETED)
        f.submit(); yield(); f.noEvent()
        assertTrue(f.failures.isEmpty())
        assertNull(f.tracker.requestForSession("s"))
    }

    @Test fun storageExceptionAfterCancellationCannotEmitSpuriousErrorEvent() = contract { f ->
        f.gateway.beforeRead = {
            val job = currentCoroutineContext().job
            withContext(NonCancellable) { job.cancel(); throw IOException("late read failure") }
        }
        f.gateway.task("task", WebChatTaskState.COMPLETED)
        f.submit(); yield(); f.noEvent()
        assertTrue(f.failures.isEmpty())
        assertNull(f.tracker.requestForSession("s"))
    }
}
