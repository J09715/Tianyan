package top.tianyan.app.runtime.webchat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/** Tracks each accepted request independently, including terminal tasks observed after completion. */
internal class WebChatRunTracker(
    private val scope: CoroutineScope,
    private val gateway: WebChatAgentGateway,
    private val messages: (String, WebChatSessionSnapshot) -> Unit,
    private val event: (String, String, String, List<WebChatApproval>) -> Unit,
    private val failure: (Throwable) -> Unit,
) {
    private class Run(val sessionId: String) {
        var job: Job? = null
        var waiting = false
        var cancelRequested = false
    }
    private val lock = Any()
    private val runs = mutableMapOf<String, Run>()
    private var enabled = true

    fun enable() = synchronized(lock) { enabled = true }

    /** Reserves correlation before admission, so duplicate IDs cannot dispatch a second input. */
    suspend fun submit(sessionId: String, requestId: String, admit: suspend () -> WebChatInputReceipt): WebChatInputReceipt {
        val run = synchronized(lock) {
            check(enabled) { "Web 服务已停止" }
            require(requestId !in runs) { "任务 ID 已在使用" }
            Run(sessionId).also { runs[requestId] = it }
        }
        try {
            val receipt = admit()
            currentCoroutineContext().ensureActive()
            val job = scope.launch(start = CoroutineStart.LAZY) { observe(requestId, run, receipt) }
            job.invokeOnCompletion { synchronized(lock) { if (runs[requestId] === run) runs.remove(requestId) } }
            synchronized(lock) {
                if (runs[requestId] === run) { run.job = job; job.start() } else job.cancel()
            }
            return receipt
        } catch (error: Throwable) {
            synchronized(lock) { if (runs[requestId] === run) runs.remove(requestId) }
            throw error
        }
    }

    fun requestForSession(sessionId: String): String? = synchronized(lock) {
        runs.entries.firstOrNull { it.value.sessionId == sessionId && it.value.waiting }?.key
            ?: runs.entries.firstOrNull { it.value.sessionId == sessionId && it.value.job != null }?.key
    }

    /** Existing Web stop is session-wide; observers stay until durable settlement is reported. */
    fun cancel(requestId: String): Boolean {
        val sessionId = synchronized(lock) {
            val run = runs[requestId] ?: return false
            if (run.cancelRequested || run.job == null) return false
            run.cancelRequested = true
            run.sessionId
        }
        gateway.cancel(sessionId)
        return true
    }

    fun stop() = synchronized(lock) {
        enabled = false
        val jobs = runs.values.mapNotNull { it.job }
        runs.clear()
        jobs.forEach(Job::cancel)
    }

    private suspend fun observe(requestId: String, run: Run, receipt: WebChatInputReceipt) {
        val progress = WebChatRunProgress(receipt.taskId != null)
        try {
            val taskFlow = receipt.taskId?.let { gateway.observeTask(run.sessionId, it) } ?: flowOf(null)
            combine(gateway.observeSession(run.sessionId), taskFlow) { session, task -> session to task }
                .takeWhile { (session, task) ->
                    currentCoroutineContext().ensureActive()
                    val update = progress.update(session, task)
                    val displayed = if (update.terminal) session.copy(messages = finalMessages(run.sessionId)) else session
                    currentCoroutineContext().ensureActive()
                    messages(run.sessionId, displayed)
                    synchronized(lock) { run.waiting = update.waiting }
                    update.kind?.let { event(requestId, it, run.sessionId, if (update.waiting) session.approvals else emptyList()) }
                    !update.terminal
                }.collect { }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            failure(error)
            event(requestId, "error", run.sessionId, emptyList())
        } finally {
            synchronized(lock) { if (runs[requestId] === run) runs.remove(requestId) }
        }
    }

    // Check the collecting coroutine before an exception unwinds through combine's child scope.
    private suspend fun finalMessages(sessionId: String): List<WebChatMessage> = try {
        gateway.messages(sessionId).also { currentCoroutineContext().ensureActive() }
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        throw error
    }
}

/** Session flags are a fallback only for steering/follow-up instructions without their own task. */
internal class WebChatRunProgress(private val durable: Boolean) {
    data class Update(val kind: String? = null, val waiting: Boolean = false, val terminal: Boolean = false)
    private var observedRunning = false
    private var waitingSent = false

    fun update(session: WebChatSessionSnapshot, task: WebChatTaskState?): Update {
        val terminal = if (durable) when (task) {
            WebChatTaskState.COMPLETED -> "completed"
            WebChatTaskState.CANCELLED, WebChatTaskState.FAILED, WebChatTaskState.SUSPENDED,
            WebChatTaskState.MISSING, WebChatTaskState.UNKNOWN -> "error"
            else -> null
        } else if (observedRunning && !session.running && !session.waitingApproval) {
            if (session.error == null) "completed" else "error"
        } else null
        if (terminal != null) return Update(terminal, terminal = true)
        if (session.running) observedRunning = true
        val waiting = if (durable) task == WebChatTaskState.WAITING_APPROVAL else session.waitingApproval
        // Wait for approval details rather than emitting an empty, unrecoverable approval event.
        val emitWaiting = waiting && session.approvals.isNotEmpty() && !waitingSent
        waitingSent = if (waiting) waitingSent || emitWaiting else false
        return Update(if (emitWaiting) "waiting_approval" else null, waiting)
    }
}
