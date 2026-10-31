package top.tianyan.app.harness.groupchat

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.tianyan.app.core.model.SessionRunState
import top.tianyan.app.core.model.bot.GroupToolStep

/** Compact host observations. No provider heartbeat or retry reason is inferred from reasoning text. */
internal class GroupExecutionTelemetry(private val startedAt: Long) {
    private var lastOutputAt: Long? = null
    private var lastSavedAt = Long.MIN_VALUE
    private var lastState = ""

    fun output(now: Long) { lastOutputAt = now }
    fun checkpoint(now: Long, state: SessionRunState, reasoning: String, steps: List<GroupToolStep>,
        watchdog: GroupExecutionWatchdog,
        provider: top.tianyan.app.harness.session.ProviderActivity? = null): String? {
        val phase = when {
            state == SessionRunState.WAITING_APPROVAL -> "waiting_approval"
            steps.any { it.success == null } -> "tool_running"
            watchdog.warned -> "warning_no_execution_progress"
            provider != null -> provider.phase
            reasoning.isNotBlank() -> "thinking"
            lastOutputAt != null -> "model_output"
            else -> "waiting_model"
        }
        if (phase == lastState && lastSavedAt != Long.MIN_VALUE && now - lastSavedAt < 5_000) return null
        lastState = phase
        lastSavedAt = now
        return buildJsonObject {
            put("phase", phase)
            put("executionStartedAt", startedAt)
            lastOutputAt?.let { put("lastModelOutputAt", it) }
            put("lastExecutionProgressAt", watchdog.lastProgressAt)
            put("executionDeadlineExceeded", watchdog.expired(now))
            put("observedAt", now)
            put("completedTools", steps.count { it.success != null })
            provider?.let {
                put("requestStartedAt", it.requestStartedAt)
                put("requestAttempt", it.attempt)
                put("providerPhase", it.phase)
                it.lastTransportAt?.let { at -> put("lastTransportAt", at) }
                it.lastOutputAt?.let { at -> put("lastModelOutputAt", at) }
                it.retryReason?.let { reason -> put("retryReason", reason) }
            }
        }.toString()
    }
}
