package top.tianyan.app.harness

/**
 * HarnessMessage ↔ OpenAI 兼容 API 消息的纯转换逻辑（无依赖，便于测试）。
 */
internal object HarnessApiMapper {
    fun toApiMessage(message: HarnessMessage): ApiMessage = when (message) {
        is CapabilityEvent, is ModelSwitchEvent, is SkillSuggestion, is TaskDelivery, is TurnChangesEvent -> ApiMessage(role = "system", content = null)
        is UserMessage -> ApiMessage(
            role = "user",
            content = renderUserContent(message.text, message.attachments),
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
            content = message.modelOutput(),
            tool_call_id = message.toolCallId,
        )
    }

    /**
     * 用户消息正文 + 附件挂载说明 → 最终 content。
     *
     * 纯函数：给定同样的 [text] 与 [attachments]，永远产出同样的字节 —— 这是前缀缓存
     * 不变式的落点，请求字节只由已持久化的状态决定。
     *
     * 附件说明只在**这一层**（发给模型）展开，不写回 [UserMessage.text]：
     * 气泡层据此渲染纯正文 + 文件卡片，模型仍能拿到沙箱绝对路径去读文件。
     */
    internal fun renderUserContent(
        text: String,
        attachments: List<MessageAttachment> = emptyList(),
    ): String {
        if (attachments.isEmpty()) return text
        return text.trimEnd() + "\n\n" + renderAttachmentManifest(attachments)
    }

    /**
     * 附件挂载说明（模型可见文本）。
     *
     * 与 UI 的文件卡片职责互补：卡片给人看，这段给模型看。
     * 没有这段，模型不知道附件存在 —— 只能瞎猜文件名或干脆忽略。
     */
    internal fun renderAttachmentManifest(attachments: List<MessageAttachment>): String = buildString {
        append("[附件：已复制并挂载到 Linux 沙箱]\n")
        attachments.forEachIndexed { index, attachment ->
            val kind = if (attachment.isImage) "图片" else "文件"
            val guestPath = attachment.guestPath.ifBlank { "/attachments/${attachment.name}" }
            append(index + 1)
            append(". [")
            append(kind)
            append("] ")
            append(attachment.name)
            append(" (")
            append(formatAttachmentSize(attachment.sizeBytes))
            append(") -> Linux 沙箱绝对路径: ")
            append(guestPath)
            append('\n')
        }
        append("需要通过工具访问时，请使用 base 在上述绝对路径读取；不要使用 Android content:// URI 或宿主机路径。")
    }

    /** 附件体积的人读写法（B / KB / MB），与 UI 侧 formatFileSize 保持一致的口径。 */
    internal fun formatAttachmentSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1fKB", bytes / 1024.0)
        else -> String.format(java.util.Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0))
    }

    /** LLM 返回的函数名 → HarnessTool。未知工具统一归入 base 由执行层报错。 */
    fun toolByName(name: String): HarnessTool {
        val trimmed = name.trim()
        val lower = trimmed.lowercase()
        return when {
            lower == "read" -> HarnessTool.READ
            lower == "write" -> HarnessTool.WRITE
            lower == "edit" -> HarnessTool.EDIT
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
            lower == "load_skill" -> HarnessTool.LOAD_SKILL
            lower == "compress" -> HarnessTool.COMPRESS
            lower == "ask_user" -> HarnessTool.ASK_USER
            lower == "render_surface" -> HarnessTool.RENDER_SURFACE
            lower == "red_team" -> HarnessTool.RED_TEAM
            lower == "use_capability" -> HarnessTool.MCP
            trimmed.startsWith("mcp__") -> HarnessTool.MCP
            else -> HarnessTool.BASE
        }
    }

    fun apiName(tool: HarnessTool): String = when (tool) {
        HarnessTool.READ -> "read"
        HarnessTool.WRITE -> "write"
        HarnessTool.EDIT -> "edit"
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
        HarnessTool.LOAD_SKILL -> "load_skill"
        HarnessTool.COMPRESS -> "compress"
        HarnessTool.ASK_USER -> "ask_user"
        HarnessTool.RENDER_SURFACE -> "render_surface"
        HarnessTool.RED_TEAM -> "red_team"
    }
}
