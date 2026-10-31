package top.tianyan.app.harness.subagent

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import top.tianyan.app.harness.ApiMessage
import top.tianyan.app.harness.ChatResult
import top.tianyan.app.harness.LlmOutputLimitException
import top.tianyan.app.harness.ModelConfig
import top.tianyan.app.harness.ProviderClient
import top.tianyan.app.harness.outputRecoveryModel

/** 子智能体单轮请求：网络重试 + 输出预算翻倍恢复。 */
internal suspend fun subagentChatWithRetry(
    providerClient: ProviderClient,
    model: ModelConfig,
    messages: List<ApiMessage>,
    text: StringBuilder, sessionKey: String? = null,
): ChatResult {
    var requestModel = model
    var networkAttempts = 0
    var outputRecoveries = 0
    var lastFailure: IOException? = null
    while (true) {
        try {
            return providerClient.chatStream(requestModel, messages, onReasoning = {}, sessionKey = sessionKey) { text.append(it) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (limit: LlmOutputLimitException) {
            text.clear()
            // 子任务常产出较长方案；沿用主会话的翻倍恢复策略，避免直接以 length 失败。
            val recovered = if (outputRecoveries < MAX_OUTPUT_RECOVERIES) {
                outputRecoveryModel(requestModel)
            } else {
                null
            }
            if (recovered == null) throw limit
            outputRecoveries++
            requestModel = recovered
        } catch (failure: IOException) {
            lastFailure = failure
            text.clear()
            networkAttempts++
            if (networkAttempts >= NETWORK_ATTEMPTS) throw failure
            delay(NETWORK_RETRY_DELAY_MS)
        }
    }
}

private const val NETWORK_ATTEMPTS = 2
private const val NETWORK_RETRY_DELAY_MS = 1_000L
private const val MAX_OUTPUT_RECOVERIES = 2
