package top.tianyan.app.harness

import kotlinx.serialization.json.Json
import top.tianyan.app.harness.task.TaskDeliveryReport

/** Read-only UTF-16 text pages over a stable call/result group, before any display truncation. */
internal object HistoryReadPage {
    const val DEFAULT_LIMIT = 8_000
    const val MAX_LIMIT = 16_000

    /**
     * Preview the target message after ONE redaction pass over its whole related group: a bare echo
     * whose label lives in a sibling message cannot be learned from the target text alone.
     */
    fun preview(messages: List<HarnessMessage>, targetId: String, redact: (String) -> String = { it }): String {
        require(messages.isNotEmpty()) { "未找到指定历史消息" }
        val full = redact(groupText(messages))
        val marker = "id=$targetId\n"
        val start = full.indexOf(marker)
        if (start >= 0) return full.substring(start + marker.length).take(240)
        // A rare redaction hit on the id marker itself degrades to the single-message scope.
        return messages.firstOrNull { it.id == targetId }?.let { redact(parts(it).joinToString("")).take(240) }.orEmpty()
    }

    fun render(messages: List<HarnessMessage>, offset: Int, limit: Int, redact: (String) -> String = { it }): String {
        require(messages.isNotEmpty()) { "未找到指定历史消息" }
        require(offset >= 0 && limit in 1..MAX_LIMIT) { "无效的历史分页范围" }
        // Redact the complete related text in ONE pass before slicing: SecretRedactor learns echoed
        // values only within a single call, so per-message passes erase the key hint in the call and
        // leave a bare echo in a later result unredacted.
        val full = redact(groupText(messages))
        val total = full.length.toLong()
        require(offset.toLong() <= total) { "offset 超过历史文本长度 $total" }
        var end = minOf(total, offset.toLong() + limit)
        require(offset.toLong() >= total || !(offset > 0 && full[offset].isLowSurrogate() &&
            full[offset - 1].isHighSurrogate())) { "offset 位于 UTF-16 字符对中间，请减 1" }
        if (end < total && full[end.toInt() - 1].isHighSurrogate() && full[end.toInt()].isLowSurrogate()) end--
        require(end > offset || offset.toLong() == total) { "limit 太小，无法容纳完整 UTF-16 字符对" }
        return "[history_read message_id=${messages.first().id} offset=$offset total_chars=$total " +
            "truncated=${end < total} next_offset=${if (end < total) end else "null"}; " +
            "offset/limit 为包含 id 与标签的脱敏关联文本的 UTF-16 字符数；续读使用同一 message_id]\n" +
            full.substring(offset, end.toInt())
    }

    /** Layout shared by [render] and [preview] so both redact the exact same text scope. */
    private fun groupText(messages: List<HarnessMessage>): String = messages.mapIndexed { index, message ->
        (if (index == 0) "" else "\n\n") + "id=${message.id}\n" + parts(message).joinToString("")
    }.joinToString("")

    private fun parts(message: HarnessMessage): List<String> = when (message) {
        is CapabilityEvent -> listOf("能力事件 ${message.name}: ", message.details)
        is SkillSuggestion -> listOf("技能建议 ${message.skillName}: ", message.description)
        is TaskDelivery -> listOf("任务交付记录：", Json.encodeToString(TaskDeliveryReport.serializer(), message.report))
        is ModelSwitchEvent -> listOf("切换模型 ${message.fromLabel} → ${message.toLabel}")
        is TurnChangesEvent -> listOf("轮文件改动：", (message.created + message.modified + message.touched).joinToString("、"))
        is UserMessage -> listOf("用户：", message.text, if (message.imageUrls.isEmpty()) "" else
            "\n[history_media=NOT_RESTORED count=${message.imageUrls.size}：本工具仅回读文本，不含图片内容；需要原图时请用户重新提供，不能据此推断视觉事实]")
        is AssistantText -> listOf("助手：", message.text, "\nreasoning:\n", message.reasoning.orEmpty())
        is ToolCall -> listOf("工具调用 ${message.rawToolName ?: message.tool}: ", message.args.toString(),
            "\nreasoning:\n", message.reasoning.orEmpty())
        is ToolResult -> listOf("工具结果：", message.output, artifactReference(message),
            if (message.imageAttachments.isEmpty() && message.imageDataUrl == null) "" else
                "\n[history_media=NOT_RESTORED：图片载荷未恢复；仅在原文件仍可访问时用当前环境 read 重新读取]")
    }

    private fun artifactReference(message: ToolResult): String =
        ToolOutputArtifact.decode(message.metadata[ToolOutputArtifact.METADATA_KEY])?.let { artifact ->
            "\n[history_artifact status=UNVERIFIED path=${artifact.path} sha256=${artifact.sha256} bytes=${artifact.bytes} complete=${artifact.complete}; " +
                "用 read(path, expected_sha256, offset=1, limit=200) 核验后分页读取；引用可能因7天/64MiB/256文件清理失效，不保证仍存在，不自动重放原操作]"
        }.orEmpty()
}
