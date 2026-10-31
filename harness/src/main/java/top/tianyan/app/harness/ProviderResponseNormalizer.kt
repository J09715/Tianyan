package top.tianyan.app.harness

import kotlinx.serialization.json.Json
import top.tianyan.app.harness.core.TurnResponse

/** Protocol normalization boundary shared by the main loop and independently testable. */
class ProviderResponseNormalizer(
    private val json: Json,
) {
    fun normalize(result: ChatResult, rawText: String, toolsEnabled: Boolean): NormalizedProviderResponse {
        // Structured native calls are authoritative: skip the textual codec so the display text
        // stays byte-identical with rawText and stray markers in the content do not get stripped
        // as if they were executed. The textual branch only runs as a fallback.
        if (!toolsEnabled || result.toolCalls.isNotEmpty()) {
            return NormalizedProviderResponse(
                result = result,
                rawText = rawText,
                displayText = rawText,
                toolCalls = result.toolCalls,
                textToolCallCount = 0,
                invalidMarkerCount = 0,
                hasUnresolvedMarkers = false,
            )
        }
        val textNormalization = TextToolCallCodec.normalize(json, rawText)
        if (textNormalization.calls.isNotEmpty() || textNormalization.hasUnresolvedMarkers) {
            return NormalizedProviderResponse(
                result = result,
                rawText = rawText,
                displayText = textNormalization.displayText,
                toolCalls = textNormalization.calls,
                textToolCallCount = textNormalization.calls.size,
                invalidMarkerCount = textNormalization.invalidMarkerCount,
                hasUnresolvedMarkers = textNormalization.hasUnresolvedMarkers,
            )
        }

        return NormalizedProviderResponse(
            result = result,
            rawText = rawText,
            displayText = rawText,
            toolCalls = emptyList(),
            textToolCallCount = 0,
            invalidMarkerCount = 0,
            hasUnresolvedMarkers = false,
        )
    }

}

data class NormalizedProviderResponse(
    val result: ChatResult,
    val rawText: String,
    val displayText: String,
    override val toolCalls: List<ApiToolCallSpec>,
    val textToolCallCount: Int,
    val invalidMarkerCount: Int,
    val hasUnresolvedMarkers: Boolean,
) : TurnResponse<ApiToolCallSpec> {
    override val failureMessage: String?
        get() = when {
            isBlankResponse -> ProviderClient.EMPTY_RESPONSE_MESSAGE
            toolCalls.isEmpty() && hasUnresolvedMarkers ->
                "模型返回了无法解析的文本工具调用；已停止，避免把未执行的工具请求误判为完成"
            else -> null
        }

    /**
     * 空的一轮：模型没产出正文、（有效）推理，也没有（原生或文本）工具调用。
     *
     * 残渣级思考（几字符的截断空壳）同样视为空：线上事故里中转在长上下文下返回
     * HTTP 200 + reasoning 只剩 2~3 字符的空壳，若放行会落进"无工具调用 → Complete"
     * 分支，把"什么都没说"记成任务完成（Outcome=completed），前台既不报错也没有内容。
     * 阈值与判定复用 [isNullOrResidualReasoning]，与 ProviderClient 流式/非流式层同口径。
     *
     * 判据取 rawText 而非 result.content：rawText 是流式累积的真实回包，两者应当一致，
     * 但工具协议解析失败时 displayText 会被清空而 rawText 保留原文，此时不属于"空响应"。
     */
    val isBlankResponse: Boolean
        get() = rawText.isBlank() &&
            result.reasoningContent.isNullOrResidualReasoning() &&
            toolCalls.isEmpty()
}
