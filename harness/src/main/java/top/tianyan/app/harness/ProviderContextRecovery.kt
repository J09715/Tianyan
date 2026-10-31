package top.tianyan.app.harness

import kotlinx.coroutines.CancellationException
import top.tianyan.app.harness.compaction.CompactionManager
import top.tianyan.app.harness.events.AgentEventLogger

/** No model request here: an overflowing prefix must not be replayed to generate its summary. */
internal suspend fun recoverFromContextOverflow(
    sessionId: String,
    model: ModelConfig,
    compaction: CompactionManager,
    logger: AgentEventLogger,
): Pair<Int, Int>? = try {
    val context = compaction.project(sessionId)
    if (context.messages.size <= 1) null else {
        val keepFrom = ContextWindowPolicy.computeKeepFromIndex(
            context.messages,
            HarnessProviderRunner.emergencyFoldBudget(model),
            systemTokens = 0,
            reserveTokens = 0,
            toolSchemaReserveTokens = ContextWindowPolicy.toolSchemaReserveTokensFor(
                pureChat = model.pureChatMode,
                toolDisabled = model.toolCallMode == ToolCallMode.DISABLED,
            ),
        )
        if (keepFrom < 1) null else {
            val compacted = compaction.compact(sessionId, context, keepFrom, model = null)
            val folded = context.messages.size - compacted.messages.size
            if (folded <= 0) null else folded to compacted.messages.size
        }
    }
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (throwable: Throwable) {
    logger.log(sessionId, "ContextOverflowRecoveryFailed", "紧急压缩失败：${throwable.message}", throwable)
    null
}

/** Use window-relative pressure for small models, and also recover unexplained large-prefix empty replies. */
internal fun shouldCompactEmptyResponse(inputTokens: Int, model: ModelConfig): Boolean =
    inputTokens >= minOf(64_000, ContextWindowPolicy.clampedBudget(
        model.contextTokens, 128_000, modelId = model.model, providerId = model.provider,
    ) * 3 / 4)
