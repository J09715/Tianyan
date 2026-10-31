package top.tianyan.app.harness.groupchat

import top.tianyan.app.core.model.bot.GroupToolStep

/** Output/heartbeats cannot buy more execution time. Long tools and approvals own their deadlines. */
internal data class GroupExecutionPolicy(val warnAfterMs: Long = 120_000, val stopAfterMs: Long = 300_000) {
    init { require(warnAfterMs > 0 && stopAfterMs > warnAfterMs) }
}

internal class GroupExecutionWatchdog(startedAt: Long, private val policy: GroupExecutionPolicy) {
    var lastProgressAt = startedAt
        private set
    private var deadline = startedAt + policy.stopAfterMs
    private var warningAt = startedAt + policy.warnAfterMs
    private var previous = startedAt
    private var paused = false
    private val completed = mutableSetOf<String>()
    var warned = false
        private set

    fun update(now: Long, steps: List<GroupToolStep>, waitingApproval: Boolean) {
        if (paused) {
            val elapsed = (now - previous).coerceAtLeast(0)
            deadline += elapsed
            warningAt += elapsed
        }
        previous = now
        paused = waitingApproval || steps.any { it.success == null }
        if (completed.addAll(steps.filter { it.success != null }.map { it.id })) {
            lastProgressAt = now
            deadline = now + policy.stopAfterMs
            warningAt = now + policy.warnAfterMs
            warned = false
        }
    }

    fun shouldWarn(now: Long): Boolean = !paused && !warned && now >= warningAt
    fun markWarned() { warned = true }
    fun expired(now: Long): Boolean = !paused && now >= deadline
    fun nextWakeAt(): Long = if (paused) Long.MAX_VALUE else if (!warned) warningAt else deadline
}
