package top.tianyan.app.harness.events

import kotlinx.coroutines.flow.first
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.datastore.AgentPreferences
import top.tianyan.app.harness.ModelConfig

/** Agent 侧结构化事件日志（ToolCall / ModelRequest / Steering…），受用户开关门控。 */
class AgentEventLogger(
    private val preferences: AgentPreferences,
    private val logger: AppLogger,
) {
    suspend fun log(sessionId: String, tag: String, message: String, throwable: Throwable? = null) {
        val enabled = runCatching { preferences.agentLoggingEnabled.first() }.getOrDefault(false)
        if (enabled) {
            logger.logAgent(sessionId, tag, message, throwable)
        }
        // 模型/网络/失败类事件始终落运行日志，便于导出诊断时定位卡在哪一步。
        if (tag in ALWAYS_ON_TAGS) {
            logger.i("[AgentEvent][$tag][session:$sessionId] $message", throwable)
        }
    }

    /** 每次真正发起（含重试）的模型请求都留痕，卡住时可从导出日志判断停在哪一次。 */
    suspend fun logModelAttempt(
        sessionId: String,
        model: ModelConfig,
        messageCount: Int,
        attempt: Int,
        maxAttempts: Int,
    ) {
        log(
            sessionId = sessionId,
            tag = "ModelRequest",
            message = "第 $attempt/$maxAttempts 次请求 · model=${model.name} · provider=${model.provider}" +
                " · protocol=${model.protocol} · messages=$messageCount" +
                " · maxTokens=${model.maxTokens ?: "默认"}",
        )
    }

    private companion object {
        val ALWAYS_ON_TAGS = setOf(
            "ModelRequest",
            "ModelError",
            "NetworkRetry",
            "RateLimitRetry",
            "QuotaExhausted",
            "OutputLimitRecovery",
            "EmptyModelResponse",
            "EmptyModelResponseRetry",
            "LargeContextRetryPolicy",
            "VisionFallback",
            "Cancelled",
            "RunFailure",
        )
    }
}
