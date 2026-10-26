package top.tianyan.app.harness.session

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import top.tianyan.app.harness.PendingMessage
import top.tianyan.app.harness.queue.PromptQueue
import top.tianyan.app.harness.session.PromptSubmission.Accepted
import top.tianyan.app.harness.session.PromptSubmission.Disposition
import top.tianyan.app.harness.session.PromptSubmission.Rejected
import top.tianyan.app.harness.session.PromptSubmission.Rejection

/** Scheduling/durability adapter; every method except refresh runs under the session mutex. */
internal interface SessionInputRuntime {
    suspend fun exists(sessionId: String): Boolean
    fun busy(sessionId: String): Boolean
    suspend fun settleExpiredApprovals(sessionId: String)
    suspend fun hasNextRun(sessionId: String): Boolean
    suspend fun record(sessionId: String, input: PendingMessage)
    suspend fun enqueue(sessionId: String, queue: PromptQueue, input: PendingMessage): String
    suspend fun start(sessionId: String, input: PendingMessage): Boolean
    suspend fun startNext(sessionId: String)
    suspend fun refresh(sessionId: String)
    fun refreshFailed(sessionId: String, failure: Exception) = Unit
}

/** One admission path for UI fire-and-forget commands and awaited remote requests. */
internal class SessionInputController(
    private val coordinator: SessionTurnCoordinator,
    private val runtime: SessionInputRuntime,
) {
    suspend fun submit(
        sessionId: String,
        text: String,
        imageUrls: List<String>,
        queue: PromptQueue,
    ): PromptSubmission = checked {
        if (sessionId.isBlank()) return@checked Rejected(Rejection.INVALID_SESSION)
        val trimmed = text.trim()
        if (trimmed.isEmpty() && imageUrls.isEmpty()) return@checked Rejected(Rejection.EMPTY_INPUT)
        // Capture mutable caller-owned attachments before the first suspension.
        val images = imageUrls.toList()
        val result = coordinator.withSessionMutex(sessionId) {
            val exists = runtime.exists(sessionId)
            currentCoroutineContext().ensureActive()
            if (!exists) return@withSessionMutex Rejected(Rejection.SESSION_NOT_FOUND)
            runtime.settleExpiredApprovals(sessionId)
            currentCoroutineContext().ensureActive()
            val busy = runtime.busy(sessionId)
            val backlog = queue == PromptQueue.NEXT_RUN && runtime.hasNextRun(sessionId)
            currentCoroutineContext().ensureActive()
            val work = currentCoroutineContext()[top.tianyan.app.harness.groupchat.GroupWorkContext]
            val pending = PendingMessage(trimmed, images, taskId =
                if (!busy || queue == PromptQueue.NEXT_RUN) UUID.randomUUID().toString() else null,
                groupWorkId = work?.workId, groupId = work?.groupId)
            if (pending.taskId != null) {
                runtime.record(sessionId, pending)
                currentCoroutineContext().ensureActive()
            }
            if (busy || backlog) {
                val itemId = runtime.enqueue(sessionId, queue, pending)
                currentCoroutineContext().ensureActive()
                if (!busy && backlog) runtime.startNext(sessionId)
                Accepted(Disposition.QUEUED, pending.taskId, queue, itemId)
            } else {
                val started = runtime.start(sessionId, pending)
                currentCoroutineContext().ensureActive()
                if (started) Accepted(Disposition.STARTED, pending.taskId)
                else Rejected(Rejection.LAUNCH_REJECTED)
            }
        }
        if (result is Accepted) {
            try { runtime.refresh(sessionId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                // Admission is committed; a stale UI projection must not invite duplicate input.
                runtime.refreshFailed(sessionId, failure)
            }
        }
        currentCoroutineContext().ensureActive()
        result
    }

    private suspend fun <T> checked(block: suspend () -> T): T = try {
        block().also { currentCoroutineContext().ensureActive() }
    } catch (failure: Exception) {
        currentCoroutineContext().ensureActive()
        throw failure
    }
}
