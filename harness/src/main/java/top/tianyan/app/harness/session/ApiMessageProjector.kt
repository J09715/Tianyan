package top.tianyan.app.harness.session

import top.tianyan.app.harness.ApiFunctionCall
import top.tianyan.app.harness.ApiMessage
import top.tianyan.app.harness.ApiToolCall
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.CapabilityEvent
import top.tianyan.app.harness.HarnessApiMapper
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.ModelSwitchEvent
import top.tianyan.app.harness.ProviderClient
import top.tianyan.app.harness.SkillSuggestion
import top.tianyan.app.harness.TaskDelivery
import top.tianyan.app.harness.TurnChangesEvent
import top.tianyan.app.harness.TextToolCallCodec
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolCallMode
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.harness.ContextWindowPolicy
import top.tianyan.app.harness.UserMessage
import top.tianyan.app.harness.ResponsesReplay
import top.tianyan.app.harness.modelOutput

/**
 * Harness 消息 → 提供商协议消息的统一投影。
 *
 * 从 ApiContextAssembler 的转换循环原样抽出，供两处共用同一投影口径：
 * - 主对话组装（ApiContextAssembler）；
 * - 压缩摘要的 cache-replay 请求（CompactionSummarizer）——重放与主请求字节一致
 *   的历史前缀，是命中 provider KV 缓存的前提。
 *
 * [recallSuffixes] 把持久化的用户轮召回后缀（userMessageId → 文本）追加到对应
 * user 消息正文之后；后缀随轮持久化、永不变化，不在此处重新计算。
 */
object ApiMessageProjector {

    fun project(
        msgs: List<HarnessMessage>,
        toolCallMode: ToolCallMode,
        visionEnabled: Boolean,
        recallSuffixes: Map<String, String> = emptyMap(),
    ): List<ApiMessage> {
        val answeredIds = msgs.filterIsInstance<ToolResult>().mapTo(mutableSetOf()) { it.toolCallId }
        // 虚拟屏一轮轮截图会把每张全屏图都留在后续请求里。模型实际只需要最近几张，
        // 更早的画面继续堆叠会把上下文撑到上游只回空 delta 的程度。
        val keptToolImageIds = msgs.filterIsInstance<ToolResult>()
            .filter { it.success && !it.imageDataUrl.isNullOrBlank() }
            .map { it.id }
            .takeLast(MAX_RECENT_TOOL_IMAGES)
            .toSet()
        val toolCallDetails = msgs.filterIsInstance<ToolCall>().associate {
            it.id to ((it.rawToolName ?: HarnessApiMapper.apiName(it.tool)) to it.args)
        }
        // JSON 文本模式：工具调用以文本表达，tool 消息需转成 user 文本（API 不认识 tool 角色）
        val toolNames = toolCallDetails.mapValuesTo(mutableMapOf()) { it.value.first }

        fun apiToolCall(tc: ToolCall) = ApiToolCall(
            id = tc.id,
            function = ApiFunctionCall(
                name = tc.rawToolName ?: HarnessApiMapper.apiName(tc.tool),
                arguments = tc.args.toString(),
            ),
        )

        fun withRecallSuffix(mapped: ApiMessage, message: HarnessMessage): ApiMessage {
            val suffix = (message as? UserMessage)?.let { recallSuffixes[it.id] }.orEmpty()
            if (suffix.isBlank()) return mapped
            return mapped.copy(content = (mapped.content ?: "") + "\n\n" + suffix)
        }

        // 图片预算：每轮都把全部历史图重发，请求体会随会话线性膨胀（413 / 费用失控）。
        // 额度从最新消息往前发，超出的旧图改成一行说明，用户仍能在 UI 里看到原图。
        val imageBudget = ImageBudget(MAX_TOTAL_IMAGE_BYTES)
        if (visionEnabled) {
            msgs.asReversed().forEach { message ->
                if (message is UserMessage && message.imageUrls.isNotEmpty()) imageBudget.admit(message.id, message.imageUrls)
            }
        }

        fun applyImagePolicy(mapped: ApiMessage, message: HarnessMessage): ApiMessage {
            if (message !is UserMessage) return mapped
            if (!visionEnabled) return mapped.copy(imageUrls = emptyList())
            val admitted = imageBudget.urls(message)
            val dropped = imageBudget.dropped(message)
            if (dropped <= 0) return mapped.copy(imageUrls = admitted)
            val note = "\n[为控制请求体积，本次未重新附上 $dropped 张历史图片]"
            return mapped.copy(imageUrls = admitted, content = (mapped.content ?: "") + note)
        }

        return buildList {
            var i = 0
            while (i < msgs.size) {
                val message = msgs[i]
                if (message is CapabilityEvent || message is ModelSwitchEvent || message is SkillSuggestion ||
                    message is TaskDelivery || message is TurnChangesEvent
                ) {
                    i++
                    continue
                }
                if (toolCallMode == ToolCallMode.JSON_TEXT) {
                    when (message) {
                        is ToolCall -> {
                            toolNames[message.id] = message.rawToolName ?: HarnessApiMapper.apiName(message.tool)
                            // 回放调用意图：落库的 assistant 文本已剥离工具标记，跳过会让模型
                            // 看不到自己上一轮调用了什么参数，结果无法与调用关联，易重复调用。
                            // 与 NATIVE 分支同口径：无结果的悬空调用不回放。
                            if (message.id in answeredIds) {
                                add(
                                    ApiMessage(
                                        role = "assistant",
                                        content = TextToolCallCodec.encodeCall(
                                            message.rawToolName ?: HarnessApiMapper.apiName(message.tool),
                                            message.args.toString(),
                                        ),
                                    ),
                                )
                            }
                            i++
                        }
                        is ToolResult -> {
                            val name = toolNames[message.toolCallId] ?: "工具"
                            val status = if (message.success) "成功" else "失败"
                            val content = "【工具 $name 执行结果·$status】\n${message.modelOutput()}"
                            add(ApiMessage(role = "user", content = content))
                            visionBridgeMessage(message, visionEnabled)?.let { add(it) }
                            i++
                        }
                        else -> {
                            val mapped = when (message) {
                                is AssistantText ->
                                    HarnessApiMapper.toApiMessage(message).copy(
                                        content = ContextWindowPolicy.assistantTextForContext(
                                            ProviderClient.stripThinkTags(message.text) ?: message.text,
                                        ),
                                        reasoning_content = null,
                                    )
                                else -> HarnessApiMapper.toApiMessage(message)
                            }
                            val withSuffix = withRecallSuffix(mapped, message)
                            add(applyImagePolicy(withSuffix, message))
                            i++
                        }
                    }
                    continue
                }
                if (message is AssistantText || message is ToolCall) {
                    if (message is ToolCall && message.id !in answeredIds) {
                        i++
                        continue
                    }
                    val text = (message as? AssistantText)?.text?.let {
                        ContextWindowPolicy.assistantTextForContext(ProviderClient.stripThinkTags(it) ?: it)
                    }
                    val toolCalls = mutableListOf<ApiToolCall>()
                    if (message is ToolCall) toolCalls.add(apiToolCall(message))
                    var j = i + 1
                    while (j < msgs.size && msgs[j] is ToolCall) {
                        val tc = msgs[j] as ToolCall
                        if (tc.id in answeredIds) toolCalls.add(apiToolCall(tc))
                        j++
                    }
                    // 携带 tools 的请求必须原样回传所有历史 assistant 的 reasoning_content，
                    // 包括未调用工具的文字回复；仅保留 tool_calls 轮会导致 DeepSeek 400。
                    // https://api-docs.deepseek.com/guides/thinking_mode/
                    val reasoning = if (toolCallMode == ToolCallMode.NATIVE || toolCalls.isNotEmpty()) {
                        (message as? AssistantText)?.reasoning
                            ?: (message as? ToolCall)?.reasoning
                            ?: msgs.subList(i, j).filterIsInstance<ToolCall>().firstNotNullOfOrNull { it.reasoning }
                    } else {
                        null
                    }
                    val projected = ApiMessage(
                        role = "assistant", content = text, reasoning_content = reasoning,
                        tool_calls = toolCalls.takeIf { it.isNotEmpty() },
                    )
                    val turn = (message as? AssistantText)?.responsesTurn?.takeIf {
                        ResponsesReplay.canProject(it, projected, msgs.subList(i, j).filterIsInstance<ToolCall>(), msgs)
                    }
                    add(projected.copy(responsesTurn = turn))
                    i = j
                } else if (message is ToolResult) {
                    val imageOmitted = visionEnabled &&
                        message.success &&
                        !message.imageDataUrl.isNullOrBlank() &&
                        message.id !in keptToolImageIds
                    val content = if (imageOmitted) {
                        message.modelOutput() + "\n[这张历史截图未再次附上，上下文只保留最近 $MAX_RECENT_TOOL_IMAGES 张画面。需要旧画面请重新截图。]"
                    } else {
                        message.modelOutput()
                    }
                    add(
                        ApiMessage(
                            role = "tool",
                            content = content,
                            tool_call_id = message.toolCallId,
                        ),
                    )
                    // 沙箱图片多模态直通：紧跟 tool 结果追加一条图片 user 消息
                    if (!imageOmitted) {
                        visionBridgeMessage(message, visionEnabled)?.let { add(it) }
                    }
                    i++
                } else {
                    val mapped = HarnessApiMapper.toApiMessage(message)
                    val withSuffix = withRecallSuffix(mapped, message)
                    add(applyImagePolicy(withSuffix, message))
                    i++
                }
            }
        }
    }

    /**
     * 沙箱图片多模态直通：把 read 工具留下的 data URL 组装成一条 user 图片消息。
     * 仅在模型开启视觉且工具调用成功时注入；非视觉模型直接忽略，避免请求被拒绝。
     */
    private fun visionBridgeMessage(message: ToolResult, visionEnabled: Boolean): ApiMessage? {
        val payload = message.imageDataUrl ?: return null
        if (!visionEnabled || !message.success) return null
        return ApiMessage(
            role = "user",
            content = "[工具结果附带的图片，已作为多模态图像提供]",
            imageUrls = listOf(payload),
        )
    }

    /** 发给模型的工具截图上限。更早的 imageDataUrl 仍留在会话记录里，只是不再重复发送。 */
    internal const val MAX_RECENT_TOOL_IMAGES = 2

    /**
     * 一次请求里所有用户图片（base64 data URL）的总额度。
     * 单张 1280px JPEG 的 data URL 约 300–500KB，8MB 约能容纳 20 张；
     * 额度按字符数计算（data URL 全为 ASCII，字符数即字节数）；
     * 写成常量是因为上游 413 与费用都随请求体线性增长，而不只是受上下文窗口限制。
     */
    internal const val MAX_TOTAL_IMAGE_BYTES = 8L * 1024 * 1024

    /** 按最新优先发放图片额度；同一批里也是从后往前发，保证最近看到的图一定保留。 */
    private class ImageBudget(private val maxBytes: Long) {
        private var remaining = maxBytes
        private val kept = HashMap<String, List<String>>()
        private val dropped = HashMap<String, Int>()

        fun admit(messageId: String, urls: List<String>) {
            val keepMask = BooleanArray(urls.size)
            for (index in urls.indices.reversed()) {
                val size = urls[index].length.toLong()
                if (size <= remaining) {
                    remaining -= size
                    keepMask[index] = true
                }
            }
            val admitted = urls.filterIndexed { index, _ -> keepMask[index] }
            kept[messageId] = admitted
            dropped[messageId] = urls.size - admitted.size
        }

        fun urls(message: UserMessage): List<String> = kept[message.id] ?: message.imageUrls

        fun dropped(message: UserMessage): Int = dropped[message.id] ?: 0
    }
}
