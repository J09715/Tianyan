package top.tianyan.app.harness

import top.tianyan.app.harness.compaction.CompactionManager
import top.tianyan.app.harness.events.AgentEventLogger

/** One bounded replay, using a larger output reservation only for this request. */
internal fun outputRecoveryModel(model: ModelConfig): ModelConfig? {
    val current = ContextWindowPolicy.normalizeOutputTokens(model.maxTokens, 8_192)
    val target = minOf(current.toLong() * 2, 32_768L).toInt()
    if (target <= current) return null
    return model.copy(maxTokens = target,
        compactionReserveTokens = maxOf(model.compactionReserveTokens ?: 8_192, target))
}

internal suspend fun recoverFromOutputLimit(
    sessionId: String,
    model: ModelConfig,
    messages: List<ApiMessage>,
    toolSchemaTokens: Int,
    compaction: CompactionManager,
    logger: AgentEventLogger,
): ModelConfig? {
    val candidate = outputRecoveryModel(model) ?: model
    val current = ContextWindowPolicy.outputBudget(model.maxTokens, 8_192, messages,
        model.contextTokens, model.model, model.provider, toolSchemaTokens)
    val available = ContextWindowPolicy.outputBudget(candidate.maxTokens, 8_192, messages,
        candidate.contextTokens, candidate.model, candidate.provider, toolSchemaTokens)
    // length alone does not prove context overflow. Fold only when the window limits output.
    val target = ContextWindowPolicy.normalizeOutputTokens(candidate.maxTokens, 8_192)
    if (available < target) {
        if (recoverFromContextOverflow(sessionId, candidate, compaction, logger) != null) return candidate
    }
    return candidate.takeIf { available > current }
}
