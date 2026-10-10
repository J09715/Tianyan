package top.tianyan.app.harness

/**
 * HarnessMessage ↔ OpenAI 兼容 API 消息的纯转换逻辑（无依赖，便于测试）。
 */
internal object HarnessApiMapper {
    fun toApiMessage(message: HarnessMessage): ApiMessage = when (message) {
        is CapabilityEvent -> ApiMessage(role = "system", content = null)
        is UserMessage -> ApiMessage(
            role = "user",
            // 动态上下文尾部取自消息自身的**持久化字段**，不是渲染期现算。
            //
            // 这是前缀缓存的硬前提：provider 按精确前缀匹配计费，第 N+1 轮请求的
            // 前 |请求 N| 字节必须与请求 N 完全一致。若尾部在渲染期临时拼接，
            // 下一轮这条消息成为历史消息时尾部消失、字节改变 —— 断裂点只是从
            // system 位置 0 搬到这条消息，其后整段历史照样全量 prefill。
            //
            // 持久化字段让渲染成为「已存状态的纯函数」：字段不变则字节不变。
            content = renderUserContent(message.text, message.contextTail),
            imageUrls = message.imageUrls,
        )
        is AssistantText -> ApiMessage(
            role = "assistant",
            content = message.text,
            reasoning_content = message.reasoning,
        )
        is ToolCall -> ApiMessage(
            role = "assistant",
            content = null,
            reasoning_content = message.reasoning,
            tool_calls = listOf(
                ApiToolCall(
                    id = message.id,
                    function = ApiFunctionCall(
                        name = message.rawToolName ?: apiName(message.tool),
                        arguments = message.args.toString(),
                    ),
                ),
            ),
        )
        is ToolResult -> ApiMessage(
            role = "tool",
            content = message.output,
            tool_call_id = message.toolCallId,
        )
    }

    /** LLM 返回的函数名 → HarnessTool。未知工具统一归入 base 由执行层报错。 */
    fun toolByName(name: String): HarnessTool {
        val trimmed = name.trim()
        val lower = trimmed.lowercase()
        return when {
            lower == "read" -> HarnessTool.READ
            lower == "write" -> HarnessTool.WRITE
            lower == "edit" -> HarnessTool.EDIT
            lower == "verify" -> HarnessTool.VERIFY
            lower == "process" -> HarnessTool.PROCESS
            lower == "host" -> HarnessTool.HOST
            lower == "download" -> HarnessTool.DOWNLOAD
            lower == "memory" -> HarnessTool.MEMORY
            lower == "plan" -> HarnessTool.PLAN
            lower == "scratchpad" -> HarnessTool.SCRATCHPAD
            lower == "history.search" || lower == "history_search" -> HarnessTool.HISTORY_SEARCH
            lower == "history.read" || lower == "history_read" -> HarnessTool.HISTORY_READ
            lower == "build_script" -> HarnessTool.BUILD_SCRIPT
            lower == "invoke_subagent" || lower == "subagent" || lower == "invoke_dual_agent" -> HarnessTool.SUBAGENT
            lower == "load_rule" -> HarnessTool.LOAD_RULE
            lower == "redteam" -> HarnessTool.REDTEAM
            lower == "ask_user_question" -> HarnessTool.ASK_USER
            trimmed.startsWith("mcp__") -> HarnessTool.MCP
            else -> HarnessTool.BASE
        }
    }

    fun apiName(tool: HarnessTool): String = when (tool) {
        HarnessTool.READ -> "read"
        HarnessTool.WRITE -> "write"
        HarnessTool.EDIT -> "edit"
        HarnessTool.VERIFY -> "verify"
        HarnessTool.BASE -> "base"
        HarnessTool.PROCESS -> "process"
        HarnessTool.HOST -> "host"
        HarnessTool.DOWNLOAD -> "download"
        HarnessTool.MEMORY -> "memory"
        HarnessTool.PLAN -> "plan"
        HarnessTool.SCRATCHPAD -> "scratchpad"
        HarnessTool.HISTORY_SEARCH -> "history_search"
        HarnessTool.HISTORY_READ -> "history_read"
        HarnessTool.BUILD_SCRIPT -> "build_script"
        HarnessTool.SUBAGENT -> "invoke_subagent"
        HarnessTool.MCP -> "mcp"
        HarnessTool.LOAD_RULE -> "load_rule"
        HarnessTool.REDTEAM -> "redteam"
        HarnessTool.ASK_USER -> "ask_user_question"
    }

    /**
     * 用户消息正文 + 持久化的动态上下文尾部 → 最终 content。
     *
     * 纯函数：给定同样的 [text] 与 [tail]，永远产出同样的字节。
     * 这是前缀不变式的落点 —— 请求字节只由已持久化的状态决定，
     * 不受当前轮的语气、内存状态或渲染时机影响。
     *
     * [tail] 为空时不追加任何内容（包括分隔符），
     * 否则历史消息会因为多出两个换行而与上一轮不同。
     */
    internal fun renderUserContent(text: String, tail: String): String =
        if (tail.isBlank()) text else text.trimEnd() + "\n\n" + tail
}
