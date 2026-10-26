package top.tianyan.app.harness

import kotlinx.serialization.Serializable

@Serializable
data class ContextAllocation(val kind: String, val beforeTokens: Int, val allocatedTokens: Int, val reason: String)

data class ContextBudgetPlan(val allocations: List<ContextAllocation>, val recall: Map<String, String>,
    val failureMemory: String, val pluginContext: String)

/** Pure allocation policy. Authority never depends on a source's size or model-written priority. */
object ContextBudgetPlanner {
    fun plan(budget: Int, system: String, summary: String, activeTokens: Int,
        reserveTokens: Int?, toolTokens: Int, ratioPercent: Int, recall: Map<String, String>,
        failureMemory: String, pluginContext: String): ContextBudgetPlan {
        val available = ContextWindowPolicy.foldingLimitFor(budget, ratioPercent,
            tokens(system) + tokens(summary), reserveTokens, toolTokens).coerceAtLeast(0)
        // Reserve capacity for the current task and historical evidence. Background material gets
        // at most a quarter of the remaining message budget; stale recall yields before fresh recall.
        var remaining = minOf(available / 4, (available - activeTokens).coerceAtLeast(0))
        val allocations = mutableListOf(
            ContextAllocation("system", tokens(system), tokens(system), "SYSTEM_INSTRUCTIONS"),
            ContextAllocation("summary", tokens(summary), tokens(summary), "ROLLING_SUMMARY"),
            ContextAllocation("active_history", activeTokens, activeTokens, "CURRENT_TASK"))
        fun allocate(kind: String, text: String): String {
            val before = tokens(text)
            val fitted = fit(text, remaining)
            val used = tokens(fitted)
            remaining = (remaining - used).coerceAtLeast(0)
            allocations += ContextAllocation(kind, before, used,
                if (used < before) "BACKGROUND_BUDGET" else "WITHIN_BUDGET")
            return fitted
        }
        // Failure observations are task-local, then latest recall, then plugin background.
        val failures = allocate("failure_memory", failureMemory)
        val selected = linkedMapOf<String, String>()
        recall.entries.toList().asReversed().forEach { (id, text) -> selected[id] = allocate("recall", text) }
        val plugin = allocate("plugin_context", pluginContext)
        return ContextBudgetPlan(allocations.toList(), recall.keys.associateWith { selected.getValue(it) }, failures, plugin)
    }

    /** Keeps source wrappers intact: omit an optional block rather than cutting its trust boundary. */
    private fun fit(text: String, limit: Int) = if (tokens(text) <= limit) text else ""
    private fun tokens(text: String) = ContextWindowPolicy.estimateTokens(text)
}
