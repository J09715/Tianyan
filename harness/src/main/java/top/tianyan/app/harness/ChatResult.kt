package top.tianyan.app.harness

data class ChatResult(
    val content: String?,
    val toolCalls: List<ApiToolCallSpec>,
    /** 推理模型输出的思考内容（DeepSeek 等），多轮对话需原样传回 API。 */
    val reasoningContent: String? = null,
    /** Provider 报告的本轮 token 用量；未报告时全部为 0。 */
    val usage: ChatUsage = ChatUsage(),
    /**
     * 思考流确切耗时（毫秒）：首个 reasoning 增量到 reasoning 结束之间。
     * 由 ProviderClient.chatStream 统一测量，非推理模型或未观测到 reasoning 时为 null。
     */
    val reasoningMs: Long? = null,
    val responsesTurn: ResponsesTurn? = null,
) {
    val hasToolCalls: Boolean get() = toolCalls.isNotEmpty()

    /**
     * 空的一轮：没有正文、没有（有效）推理、也没有任何工具调用。
     * 判定覆盖两类形态：三者全空的"干净空响应"，以及正文/工具全空、思考只剩
     * 残渣级字符（[BLANK_REASONING_RESIDUAL_CHARS] 以下）的"准空响应"——后者是
     * 中转/网关在长上下文或不稳定时静默截断的典型产物，必须同样显式报错可重试，
     * 而不是被上层"无工具调用 → Complete"记成任务完成。
     * 真正的推理-only 轮次（成段思考 + 无正文 + 无工具）仍不算空：那是模型自主收束的合法形态。
     */
    val isBlankResponse: Boolean
        get() = content.isNullOrBlank() &&
            reasoningContent.isNullOrResidualReasoning() &&
            toolCalls.isEmpty()
}

