package top.tianyan.app.harness.groupchat

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.withTimeoutOrNull
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.model.SessionRunState
import top.tianyan.app.core.model.ChunkedText
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.GroupToolStep
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.harness.session.SessionControl

internal data class GroupReplyProgress(
    val contentText: ChunkedText,
    val reasoning: String,
    val steps: List<GroupToolStep>,
    val executionWarning: Boolean = false,
) {
    constructor(content: String, reasoning: String, steps: List<GroupToolStep>) :
        this(ChunkedText.of(content), reasoning, steps)
    val content: String get() = contentText.toString()
}

/** Projects provider output without mixing reasoning, transient status, and answer text. */
internal class GroupReplyObserver(
    private val sessionControl: SessionControl,
    private val logger: AppLogger,
    private val taskTracker: GroupReplyTaskTracker? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    internal data class ReplyOutcome(
        val candidate: AssistantText?,
        val firstTokenTimedOut: Boolean = false,
        val steps: List<GroupToolStep> = emptyList(),
        val replyTimedOut: Boolean = false,
        val reasoning: String = "",
        val stalled: Boolean = false,
        val failed: Boolean = false,
        val interrupted: Boolean = false,
        val noExecutionProgress: Boolean = false,
    )

    suspend fun awaitReply(
        sessionId: String,
        bot: Bot,
        baseline: Set<String>,
        startedAt: Long,
        baselineOutputRevision: Long = 0L,
        completionOnly: Boolean = false,
        taskId: String? = null,
        executionPolicy: GroupExecutionPolicy? = null,
        onProgress: suspend (GroupReplyProgress) -> Unit,
    ): ReplyOutcome = coroutineScope {
        var latestWithText: AssistantText? = null
        var latestCandidate: AssistantText? = null
        var latestSteps: List<GroupToolStep> = emptyList()
        var reasoning = ""
        var seenRunning = false
        var lastProgress: GroupReplyProgress? = null
        var firstTokenDeadline = startedAt + FIRST_TOKEN_TIMEOUT_MS
        var deadline = startedAt + REPLY_TIMEOUT_MS
        var previous = startedAt
        var previousState: SessionRunState? = null
        var receivedOutput = false
        var progressDeadline = startedAt + FIRST_TOKEN_TIMEOUT_MS
        var previousOutput = emptyList<Pair<ChunkedText, String?>>()
        var previousSteps = emptyList<GroupToolStep>()
        var outputRevision = baselineOutputRevision
        var taskStarted = false
        var execution = executionPolicy?.let { GroupExecutionWatchdog(startedAt, it) }
        var telemetry = GroupExecutionTelemetry(startedAt)
        val messageFlow = sessionControl.messagesForSession(sessionId)
        val taskFlow = if (taskId != null && taskTracker != null) taskTracker.observe(sessionId, taskId) else flowOf(null)
        val events = combine(messageFlow, sessionControl.sessionRunStates, sessionControl.modelOutputRevisions, taskFlow, sessionControl.providerActivities) { messages, states, revisions, task, activities ->
            ReplySnapshot(messages, states[sessionId] ?: SessionRunState.IDLE, revisions[sessionId] ?: 0L, task, activities[sessionId])
        }.distinctUntilChanged().conflate().produceIn(this)
        try {
            var snapshot = events.receive()
            while (true) {
                val now = clock()
                val task = snapshot.task
                if (taskId != null && taskTracker != null) {
                    if (task == null) return@coroutineScope ReplyOutcome(null, failed = true, interrupted = true)
                    if (task.completed) return@coroutineScope taskTracker.completed(sessionId, taskId)
                    if (task.failed) return@coroutineScope ReplyOutcome(null, failed = true, interrupted = task.interrupted)
                    // Queue time belongs to the preceding task, and consumes none of our output budget.
                    if (task.queued) {
                        snapshot = events.receive()
                        continue
                    }
                    if (!taskStarted) {
                        val began = task.startedAt ?: now
                        deadline = began + REPLY_TIMEOUT_MS
                        firstTokenDeadline = now + FIRST_TOKEN_TIMEOUT_MS
                        progressDeadline = firstTokenDeadline
                        previous = now
                        outputRevision = snapshot.revision
                        taskStarted = true
                        execution = executionPolicy?.let { GroupExecutionWatchdog(began, it) }
                        telemetry = GroupExecutionTelemetry(began)
                    }
                }
                val messages = task?.messages(snapshot.messages) ?: snapshot.messages
                val state = task?.state ?: snapshot.state
                val revision = snapshot.revision
                if (revision > outputRevision) {
                    telemetry.output(now)
                    receivedOutput = true
                    progressDeadline = now + FIRST_TOKEN_TIMEOUT_MS
                    outputRevision = revision
                }
                if (previousState == SessionRunState.WAITING_APPROVAL) {
                    deadline += (now - previous).coerceAtLeast(0)
                    firstTokenDeadline = now + FIRST_TOKEN_TIMEOUT_MS
                    progressDeadline = now + FIRST_TOKEN_TIMEOUT_MS
                }
                previous = now
                previousState = state
                val candidates = messages.filterIsInstance<AssistantText>()
                    .filter { it.id !in baseline && it.createdAt >= startedAt - CLOCK_SKEW_MS }
                candidates.lastOrNull()?.let { candidate ->
                    latestCandidate = candidate
                    if (candidate.textContent.hasText) latestWithText = candidate
                }
                reasoning = candidates.mapNotNull { it.reasoning?.takeIf(String::isNotBlank) }
                    .joinToString("\n\n").take(MAX_REASONING_CHARS)
                latestSteps = groupReplyToolSteps(messages, baseline, startedAt)
                // Observe actual output, including reasoning beyond the bounded UI preview.
                val output = candidates.map { it.textContent to it.reasoning }
                val modelChanged = output != previousOutput && output.any { it.first.hasText || !it.second.isNullOrBlank() }
                if ((output != previousOutput || latestSteps != previousSteps) &&
                    (output.any { it.first.hasText || !it.second.isNullOrBlank() } || latestSteps.isNotEmpty())) {
                    receivedOutput = true
                    progressDeadline = now + FIRST_TOKEN_TIMEOUT_MS
                    if (modelChanged) telemetry.output(now)
                }
                previousOutput = output
                previousSteps = latestSteps
                execution?.update(now, latestSteps, state == SessionRunState.WAITING_APPROVAL)
                val warn = execution?.shouldWarn(now) == true
                if (warn) execution.markWarned()
                execution?.let { watchdog ->
                    if (taskId != null) telemetry.checkpoint(now, state, reasoning, latestSteps, watchdog,
                        provider = snapshot.activity?.takeIf { task?.operationId == it.operationId })?.let { taskTracker?.recordProgress(taskId, it) }
                }
                val progress = GroupReplyProgress(latestWithText?.textContent ?: ChunkedText.EMPTY, reasoning, latestSteps,
                    execution?.warned == true)
                if (progress != lastProgress) {
                    onProgress(progress)
                    lastProgress = progress
                }
                if (state == SessionRunState.RUNNING || state == SessionRunState.WAITING_APPROVAL) seenRunning = true
                // A persisted text response can still consume follow-ups and continue this run.
                // The background watcher may attach long after the turn started, so it waits for
                // evidence that the turn ran or produced output before treating IDLE as settled.
                // Settled must win over a deadline: a completed turn may attach after the deadline.
                val settled = state in TERMINAL_STATES && (
                    if (completionOnly) seenRunning || latestCandidate != null || latestSteps.isNotEmpty()
                    else seenRunning || now - startedAt >= START_GRACE_MS
                    )
                if (settled) return@coroutineScope ReplyOutcome(latestWithText ?: latestCandidate,
                    steps = latestSteps, reasoning = reasoning, failed = state == SessionRunState.FAILED,
                    interrupted = state == SessionRunState.IDLE)
                val toolRunning = latestSteps.any { it.success == null }
                val expired = execution?.expired(now) == true || state != SessionRunState.WAITING_APPROVAL && (
                    now >= deadline || !completionOnly && (
                        !receivedOutput && now >= firstTokenDeadline ||
                            receivedOutput && !toolRunning && now >= progressDeadline))
                if (expired) {
                    // Publishing progress suspends. Completion, approval or a newly started tool
                    // must win over stale deadlines; fresh text/heartbeats cannot defer a stop.
                    val freshTask = if (taskId != null && taskTracker != null) taskTracker.snapshot(sessionId, taskId) else null
                    val freshMessages = messageFlow.value
                    val freshState = sessionControl.sessionRunStates.value[sessionId] ?: SessionRunState.IDLE
                    val freshSteps = groupReplyToolSteps(freshTask?.messages(freshMessages) ?: freshMessages, baseline, startedAt)
                    if (freshTask != task || freshState != snapshot.state || freshSteps != latestSteps) {
                        snapshot = ReplySnapshot(freshMessages, freshState,
                            sessionControl.modelOutputRevisions.value[sessionId] ?: 0L, freshTask,
                            sessionControl.providerActivities.value[sessionId])
                        continue
                    }
                }
                if (execution?.expired(now) == true) {
                    logger.w("[GroupReplyObserver] Bot ${bot.name} 无有效执行进展，停止原轮次并回报负责人")
                    cancel(sessionId, taskId)
                    return@coroutineScope ReplyOutcome(latestWithText ?: latestCandidate, steps = latestSteps,
                        reasoning = reasoning, noExecutionProgress = true)
                }
                if (state != SessionRunState.WAITING_APPROVAL && now >= deadline) {
                    cancel(sessionId, taskId)
                    return@coroutineScope ReplyOutcome(latestWithText ?: latestCandidate, steps = latestSteps, replyTimedOut = true, reasoning = reasoning)
                }
                if (!completionOnly && state != SessionRunState.WAITING_APPROVAL && !receivedOutput && now >= firstTokenDeadline) {
                    logger.w("[GroupReplyObserver] Bot ${bot.name} 等待首 token 超时，已请求取消")
                    cancel(sessionId, taskId)
                    return@coroutineScope ReplyOutcome(null, firstTokenTimedOut = true, steps = latestSteps, reasoning = reasoning)
                }
                if (!completionOnly && state != SessionRunState.WAITING_APPROVAL && receivedOutput && !toolRunning && now >= progressDeadline) {
                    logger.w("[GroupReplyObserver] Bot ${bot.name} 连续 ${FIRST_TOKEN_TIMEOUT_MS / 1000} 秒无新输出，已请求取消")
                    cancel(sessionId, taskId)
                    return@coroutineScope ReplyOutcome(latestWithText ?: latestCandidate, steps = latestSteps,
                        reasoning = reasoning, stalled = true)
                }
                val wakeAt = when {
                    state == SessionRunState.WAITING_APPROVAL -> Long.MAX_VALUE
                    completionOnly -> deadline
                    state in TERMINAL_STATES && !seenRunning -> startedAt + START_GRACE_MS
                    !receivedOutput -> minOf(deadline, firstTokenDeadline)
                    !toolRunning -> minOf(deadline, progressDeadline)
                    else -> deadline
                }
                // No polling: wait for model/state changes, with timers only for actual deadlines.
                val next = withTimeoutOrNull((minOf(wakeAt, execution?.nextWakeAt() ?: Long.MAX_VALUE) - clock()).coerceAtLeast(1)) { events.receive() }
                // A receive racing its timeout can discard an event; re-read the authoritative flows.
                snapshot = next ?: snapshot.copy(messages = messageFlow.value,
                    state = sessionControl.sessionRunStates.value[sessionId] ?: SessionRunState.IDLE,
                    revision = sessionControl.modelOutputRevisions.value[sessionId] ?: 0L)
            }
            @Suppress("UNREACHABLE_CODE")
            ReplyOutcome(latestWithText ?: latestCandidate, steps = latestSteps, reasoning = reasoning)
        } finally {
            events.cancel()
        }
    }

    private suspend fun cancel(sessionId: String, taskId: String?) {
        if (taskId != null && taskTracker != null) taskTracker.cancelIfActive(sessionId, taskId)
        else sessionControl.cancel(sessionId)
    }

    private data class ReplySnapshot(
        val messages: List<HarnessMessage>, val state: SessionRunState, val revision: Long,
        val task: GroupReplyTaskTracker.Snapshot?,
        val activity: top.tianyan.app.harness.session.ProviderActivity? = null,
    )

    private companion object {
        const val REPLY_TIMEOUT_MS = GroupReplyTimeoutPolicy.REPLY_TIMEOUT_MS
        const val FIRST_TOKEN_TIMEOUT_MS = GroupReplyTimeoutPolicy.OUTPUT_WAIT_MS
        const val START_GRACE_MS = 2_000L
        const val CLOCK_SKEW_MS = 2_000L
        const val MAX_REASONING_CHARS = 32_000
        val TERMINAL_STATES = setOf(SessionRunState.COMPLETED, SessionRunState.FAILED, SessionRunState.IDLE)
    }
}

internal fun groupReplyToolSteps(messages: List<HarnessMessage>, baseline: Set<String>, startedAt: Long): List<GroupToolStep> {
    val earliest = if (startedAt == Long.MIN_VALUE) Long.MIN_VALUE else startedAt - 2_000L
    val results = messages.filterIsInstance<ToolResult>().groupBy { it.toolCallId }
    return messages.filterIsInstance<ToolCall>()
        .filter { it.id !in baseline && it.createdAt >= earliest }
        .map { call ->
            val settled = results[call.id]?.lastOrNull { !it.awaitingApproval }
            val name = call.rawToolName ?: call.tool.name
            GroupToolStep(call.id, name, settled?.success, settled?.durationMs, toolStepDetail(name, call.args))
        }
}
