package top.tianyan.app.harness

import kotlinx.serialization.Serializable

@Serializable
data class ApiMessage(
    val role: String,
    val content: String? = null,
    val reasoning_content: String? = null,
    val tool_calls: List<ApiToolCall>? = null,
    val tool_call_id: String? = null,
    val imageUrls: List<String> = emptyList(),
    val responsesTurn: ResponsesTurn? = null,
)

@Serializable
data class ApiToolCall(
    val id: String,
    val type: String = "function",
    val function: ApiFunctionCall,
)

/** Preserve the existing retry-policy estimate; diagnostics do not alter retry decisions. */
internal fun estimateRetryRequestTokens(messages: List<ApiMessage>): Int = messages.sumOf { message ->
    ContextWindowPolicy.estimateTokens(message.content.orEmpty()) +
        ContextWindowPolicy.estimateTokens(message.reasoning_content.orEmpty()) +
        message.tool_calls.orEmpty().sumOf { call ->
            ContextWindowPolicy.estimateTokens(call.function.name) +
                ContextWindowPolicy.estimateTokens(call.function.arguments)
        } + message.imageUrls.size * ContextWindowPolicy.ESTIMATED_IMAGE_TOKENS
}

