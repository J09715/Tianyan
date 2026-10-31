package top.tianyan.app.harness

import top.tianyan.app.harness.core.LlmApi
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Anthropic Messages API 适配层：把内部统一的 OpenAI 风格 [ApiMessage] 列表
 * 翻译成 Claude 的 /v1/messages 请求，并把响应（含 SSE 流式）映射回 [ChatResult]。
 *
 * 主要协议差异：
 * - 鉴权头为 x-api-key + anthropic-version，而非 Authorization Bearer；
 * - 系统提示是顶层 system 字段，不在 messages 数组里；
 * - 工具结果以 user 角色 content 中的 tool_result 块回传，且同一轮的多个
 *   tool_result 必须合并在同一条 user 消息里；
 */
internal class AnthropicApi(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) : LlmApiAdapter {
    override val api = LlmApi.ANTHROPIC_MESSAGES
    @OptIn(InternalCoroutinesApi::class)
    override suspend fun chat(model: ModelConfig, messages: List<ApiMessage>): ChatResult =
        withContext(Dispatchers.IO) {
            val call = okHttpClient.newCall(buildRequest(model, messages, stream = false))
            // 与流式路径一致：取消时立即关闭 socket，避免"停止"后阻塞到读超时
            val cancelHandle = coroutineContext[Job]?.invokeOnCompletion(onCancelling = true) { call.cancel() }
            try {
                call.execute().use { response ->
                    val body = response.body.string()
                    if (!response.isSuccessful) {
                        if (response.code == 429) {
                            throw ProviderClient.rateLimitException(response.code, body, response.header("Retry-After"))
                        }
                        if (response.code in 500..599) {
                            throw ProviderClient.transientHttpException(response.code, body, response.header("Retry-After"))
                        }
                        throw httpError(response.code, body)
                    }
                    parseFinalResponse(body)
                }
            } finally {
                cancelHandle?.dispose()
            }
        }

    /** pause_turn 原样回放 assistant 消息续流，各段增量顺序上屏，用量累加。 */
    override suspend fun chatStream(
        model: ModelConfig,
        messages: List<ApiMessage>,
        onReasoning: (String) -> Unit,
        onToolProgress: (ToolCallStreamProgress) -> Unit,
        onDelta: (String) -> Unit,
    ): ChatResult = withContext(Dispatchers.IO) {
        var conversation = messages
        val mergedContent = StringBuilder()
        val mergedReasoning = StringBuilder()
        val mergedToolCalls = mutableListOf<ApiToolCallSpec>()
        var mergedUsage = ChatUsage()
        var continuations = 0
        var finalResult = ChatResult(content = null, toolCalls = emptyList())
        var done = false
        val budget = StreamBudget() // 按整轮 chatStream 计（跨 pause_turn 续跑共用一个预算）
        while (!done) {
            val round = streamOnce(model, conversation, onReasoning, onToolProgress, onDelta, budget)
            round.content?.takeIf { it.isNotEmpty() }?.let { mergedContent.append(it) }
            round.reasoningContent?.takeIf { it.isNotEmpty() }?.let { mergedReasoning.append(it) }
            mergedToolCalls += round.toolCalls
            mergedUsage = ChatUsage(
                inputTokens = mergedUsage.inputTokens + round.usage.inputTokens,
                outputTokens = mergedUsage.outputTokens + round.usage.outputTokens,
                reasoningTokens = mergedUsage.reasoningTokens + round.usage.reasoningTokens,
                cacheReadTokens = mergedUsage.cacheReadTokens + round.usage.cacheReadTokens,
                cacheWriteTokens = mergedUsage.cacheWriteTokens + round.usage.cacheWriteTokens,
            )
            if (round.stopReason == "pause_turn" && continuations >= MAX_PAUSE_TURN_CONTINUATIONS) {
                throw IllegalStateException("模型连续暂停思考，已达到 $MAX_PAUSE_TURN_CONTINUATIONS 次续跑上限，响应尚未完成")
            }
            if (round.stopReason != "pause_turn") {
                finalResult = ChatResult(
                    content = mergedContent.toString().ifEmpty { null },
                    toolCalls = mergedToolCalls,
                    reasoningContent = mergedReasoning.toString().ifEmpty { null },
                    usage = mergedUsage,
                )
                done = true
            } else {
                conversation = conversation + round.toResumeMessage()
                continuations++
            }
        }
        finalResult
    }

    /** 单轮流式结果：除内容/工具/用量外，额外携带 Anthropic 的 stop_reason。 */
    private data class StreamRound(
        val content: String?,
        val reasoningContent: String?,
        val toolCalls: List<ApiToolCallSpec>,
        val usage: ChatUsage,
        val stopReason: String?,
    )

    /** pause_turn 续跑：把本轮 assistant 部分回复转成下轮请求的前缀消息。 */
    private fun StreamRound.toResumeMessage(): ApiMessage = ApiMessage(
        role = "assistant",
        content = content,
        tool_calls = toolCalls.map { call ->
            ApiToolCall(
                id = call.id,
                function = ApiFunctionCall(name = call.name, arguments = call.argumentsJson),
            )
        },
    )

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun streamOnce(
        model: ModelConfig,
        messages: List<ApiMessage>,
        onReasoning: (String) -> Unit,
        onToolProgress: (ToolCallStreamProgress) -> Unit = {},
        onDelta: (String) -> Unit,
        budget: StreamBudget,
    ): StreamRound = withContext(Dispatchers.IO) {
        val call = okHttpClient.newCall(buildRequest(model, messages, stream = true))
        // 与 ChatApi 一致：取消时立即关闭 socket，保证"停止"秒级生效
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion(onCancelling = true) { call.cancel() }
        val firstEventTimeoutMs = firstEventTimeoutMs(messages, model)
        val firstEventState = AtomicInteger(ProviderClient.FIRST_EVENT_WAITING)
        val firstEventWatchdog = launch { awaitFirstEventTimeout(firstEventTimeoutMs, firstEventState) { call.cancel() } }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body.string()
                    if (response.code == 429) {
                        throw ProviderClient.rateLimitException(response.code, errorBody, response.header("Retry-After"))
                    }
                    if (response.code in 500..599) {
                        throw ProviderClient.transientHttpException(response.code, errorBody, response.header("Retry-After"))
                    }
                    throw httpError(response.code, errorBody)
                }
                val source = response.body.source()
                val text = StringBuilder()
                val reasoningText = StringBuilder()
                // index -> 工具调用累积器（Claude 以 content block index 标识每个 tool_use）
                val toolCalls = mutableMapOf<Int, ToolCallAccumulator>()
                var usage = ChatUsage()
                var stopReason: String? = null
                var completed = false
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    observeStreamActivity(line, firstEventState, firstEventWatchdog)
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty()) continue
                    val event = runCatching {
                        json.parseToJsonElement(data) as? JsonObject
                    }.getOrNull() ?: continue
                    if (firstEventState.compareAndSet(
                            ProviderClient.FIRST_EVENT_WAITING,
                            ProviderClient.FIRST_EVENT_RECEIVED,
                        )
                    ) {
                        firstEventWatchdog.cancel()
                    }
                    when (event["type"]?.jsonPrimitive?.contentOrNull) {
                        "message_start" -> {
                            val messageUsage = (event["message"] as? JsonObject)?.get("usage") as? JsonObject
                            if (messageUsage != null) usage = parseUsage(messageUsage)
                        }
                        "message_delta" -> {
                            val messageUsage = event["usage"] as? JsonObject
                            val outputTokens = messageUsage?.get("output_tokens")?.jsonPrimitive?.longOrNull
                            if (outputTokens != null) usage = usage.copy(outputTokens = outputTokens)
                            // pause_turn：服务端长思考/服务端工具暂停，客户端需原样回放续跑
                            (event["delta"] as? JsonObject)?.get("stop_reason")
                                ?.jsonPrimitive?.contentOrNull?.let { stopReason = it }
                        }
                        "content_block_start" -> {
                            val index = event["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                            val block = event["content_block"] as? JsonObject
                            if (block != null && block["type"]?.jsonPrimitive?.contentOrNull == "tool_use") {
                                toolCalls.getOrPut(index) { ToolCallAccumulator() }.apply {
                                    id = block["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    name = block["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    publishProgress(onToolProgress)
                                }
                            }
                        }
                        "content_block_delta" -> {
                            val index = event["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                            val delta = event["delta"] as? JsonObject ?: continue
                            when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                                "text_delta" -> delta["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { chunk ->
                                    budget.add(chunk)
                                    text.append(chunk)
                                    onDelta(chunk)
                                }
                                "thinking_delta" -> delta["thinking"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { chunk ->
                                    budget.add(chunk)
                                    if (reasoningText.length < ProviderClient.MAX_STREAM_REASONING_CHARS) {
                                        reasoningText.append(chunk.take(ProviderClient.MAX_STREAM_REASONING_CHARS - reasoningText.length))
                                    }
                                    onReasoning(chunk)
                                }
                                "input_json_delta" -> delta["partial_json"]?.jsonPrimitive?.contentOrNull?.let { chunk ->
                                    toolCalls.getOrPut(index) { ToolCallAccumulator() }.apply {
                                        arguments.append(chunk).also { budget.add(chunk) }
                                        publishProgress(onToolProgress)
                                    }
                                }
                            }
                        }
                        "message_stop" -> {
                            completed = true
                            break
                        }
                        "error" -> throw providerStreamError(event.toString())
                        else -> Unit
                    }
                }
                requireStreamCompletion(completed, "Anthropic")
                requireOutputCompletion(stopReason, ProviderClient.estimateApiMessageTokens(messages), text.length + reasoningText.length)
                toolCalls.values.forEach { it.publishProgress(onToolProgress, force = true) }
                StreamRound(
                    content = text.toString().ifEmpty { null },
                    // 无参数的工具调用（input={}）不会下发 input_json_delta，累积结果为空串，须兜底为 "{}"
                    toolCalls = toolCalls.values.map {
                        ApiToolCallSpec(it.id, it.name, it.arguments.toString().ifBlank { "{}" })
                    },
                    reasoningContent = reasoningText.toString().ifEmpty { null },
                    usage = usage,
                    stopReason = stopReason,
                )
            }
        } catch (io: IOException) {
            throw streamFailure(io, firstEventState.get(), firstEventTimeoutMs, okHttpClient.readTimeoutMillis.toLong())
        } finally {
            firstEventWatchdog.cancel()
            cancelHandle?.dispose()
        }
    }

    private fun buildRequest(model: ModelConfig, messages: List<ApiMessage>, stream: Boolean): Request {
        val systemPrompt = StringBuilder()
        val anthropicMessages = mutableListOf<JsonObject>()
        var index = 0
        while (index < messages.size) {
            val message = messages[index]
            when (message.role) {
                "system" -> {
                    if (message.content != null) {
                        if (systemPrompt.isNotEmpty()) systemPrompt.append("\n\n")
                        systemPrompt.append(message.content)
                    }
                    index++
                }
                "user" -> {
                    anthropicMessages += buildJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            buildJsonArray {
                                if (!message.content.isNullOrBlank()) {
                                    add(
                                        buildJsonObject {
                                            put("type", "text")
                                            put("text", message.content)
                                        },
                                    )
                                }
                                message.imageUrls.forEach { url ->
                                    if (url.startsWith("data:")) {
                                        val match = Regex("""data:(image/[a-zA-Z+]+);base64,(.+)""").matchEntire(url)
                                        if (match != null) {
                                            val mediaType = match.groupValues[1]
                                            val b64Data = match.groupValues[2]
                                            add(
                                                buildJsonObject {
                                                    put("type", "image")
                                                    put(
                                                        "source",
                                                        buildJsonObject {
                                                            put("type", "base64")
                                                            put("media_type", mediaType)
                                                            put("data", b64Data)
                                                        },
                                                    )
                                                },
                                            )
                                        }
                                    } else if (url.startsWith("https://", ignoreCase = true) ||
                                        url.startsWith("http://", ignoreCase = true)
                                    ) {
                                        // Messages API 支持 url 类型 source：https 图片直接透传，
                                        // 不再无声丢弃（本地无图可发时用户会以为模型"看不见"图）
                                        add(
                                            buildJsonObject {
                                                put("type", "image")
                                                put(
                                                    "source",
                                                    buildJsonObject {
                                                        put("type", "url")
                                                        put("url", url)
                                                    },
                                                )
                                            },
                                        )
                                    }
                                }
                            },
                        )
                    }
                    index++
                }
                "assistant" -> {
                    val content = buildJsonArray {
                        if (!message.content.isNullOrBlank()) {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", message.content)
                            })
                        }
                        message.tool_calls.orEmpty().forEach { call ->
                            add(
                                buildJsonObject {
                                    put("type", "tool_use")
                                    put("id", call.id)
                                    put("name", call.function.name)
                                    put(
                                        "input",
                                        runCatching {
                                            json.parseToJsonElement(call.function.arguments.ifBlank { "{}" })
                                        }.getOrElse { JsonPrimitive("") },
                                    )
                                },
                            )
                        }
                    }
                    anthropicMessages += buildJsonObject {
                        put("role", "assistant")
                        put("content", content)
                    }
                    index++
                }
                "tool" -> {
                    // 同一轮的多个 tool_result 必须合并进同一条 user 消息（Claude 协议硬性要求）
                    val results = buildJsonArray {
                        while (index < messages.size && messages[index].role == "tool") {
                            val toolMessage = messages[index]
                            add(
                                buildJsonObject {
                                    put("type", "tool_result")
                                    put("tool_use_id", toolMessage.tool_call_id.orEmpty())
                                    put("content", toolMessage.content.orEmpty())
                                },
                            )
                            index++
                        }
                    }
                    anthropicMessages += buildJsonObject {
                        put("role", "user")
                        put("content", results)
                    }
                }
                else -> index++
            }
        }

        val dynamicTools = if (model.pureChatMode) emptyList() else ProviderClient.buildDynamicTools(model, model.redTeamEnabled)
        // NATIVE 模式下 tools 数组独立于 messages，输出预算必须显式扣掉 schema
        val toolSchemaTokens =
            if (model.capabilities.nativeTools) {
                ContextWindowPolicy.estimateToolDefinitionTokens(dynamicTools)
            } else {
                0
            }

        val requestBody = buildJsonObject {
            put("model", model.model)
            // Anthropic 必填；未配置时用安全默认值
            val effectiveMaxTokens = ContextWindowPolicy.outputBudget(
                model.maxTokens,
                DEFAULT_MAX_TOKENS,
                messages,
                model.contextTokens,
                model.model,
                model.provider,
                toolSchemaTokens,
            )
            put("max_tokens", effectiveMaxTokens)
            // 推理开关/强度：thinking enabled 时 Anthropic 强制要求 temperature=1（省略即默认），
            // 且此时不再发送 temperature/top_p 以免 400。
            val thinking = ReasoningAdapter.anthropicThinking(model, effectiveMaxTokens)
            thinking?.let { put("thinking", it) }
            val thinkingEnabled = thinking?.get("type")?.jsonPrimitive?.contentOrNull == "enabled"
            if (!thinkingEnabled) {
                model.temperature?.let { put("temperature", it) }
                model.topP?.let { put("top_p", it) }
            }
            put("stream", stream)
            if (model.capabilities.textTools && dynamicTools.isNotEmpty()) {
                // JSON 文本模式：工具定义写进 system，模型用文本输出工具调用
                systemPrompt.append("\n\n## 可用工具 JSON 定义（必须严格按此 name 与参数输出）\n")
                    .append(ProviderClient.buildToolsTextDescription(dynamicTools))
            }
            if (!model.pureChatMode && systemPrompt.isNotEmpty()) {
                if (model.capabilities.promptCaching) {
                    // Prompt Caching 断点 1：System 提示词末尾
                    put(
                        "system",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", systemPrompt.toString())
                                    put("cache_control", cacheControl(model))
                                },
                            )
                        },
                    )
                } else {
                    put("system", systemPrompt.toString())
                }
            }
            // 合并相邻的 user 消息：视觉直通会在 tool_result 之后紧跟一条图片 user 消息，
            // 而 Claude 要求 role 交替；合并后 tool_result 与 image 同处一条 user 消息。
            val normalizedMessages = mergeConsecutiveUserMessages(anthropicMessages)
            // Prompt Caching 断点 3：倒数第二条「真实用户」消息（不含 tool_result 回包），
            // 该消息及之前的历史在上一轮已完全确定，可全量命中前缀缓存。
            applyUserCacheBreakpoint(model, normalizedMessages)
            put("messages", JsonArray(normalizedMessages))
            // 仅 NATIVE 模式注入标准 tools；纯净模式与 JSON_TEXT / DISABLED 均不注入
            if (model.capabilities.nativeTools && dynamicTools.isNotEmpty()) {
                put(
                    "tools",
                    buildJsonArray {
                        dynamicTools.forEachIndexed { toolIndex, definition ->
                            add(
                                buildJsonObject {
                                    put("name", definition.function.name)
                                    put("description", definition.function.description)
                                    put("input_schema", definition.function.parameters)
                                    // Prompt Caching 断点 2：Tools 数组最后一个工具定义
                                    if (model.capabilities.promptCaching && toolIndex == dynamicTools.lastIndex) {
                                        put("cache_control", cacheControl(model))
                                    }
                                },
                            )
                        }
                    },
                )
                put("tool_choice", buildJsonObject { put("type", "auto") })
            }
        }

        return Request.Builder()
            .url("${model.baseUrl.trimEnd('/')}/messages")
            .header("Content-Type", "application/json")
            .header("anthropic-version", ANTHROPIC_VERSION)
            .apply {
                if (model.capabilities.promptCaching && model.promptCacheTtl1h) {
                    header("anthropic-beta", EXTENDED_CACHE_TTL_BETA)
                }
                model.apiKey?.let { header("x-api-key", it) }
                ProviderClient.parseCustomHeaders(model.customHeaders).forEach { (name, value) ->
                    header(name, value)
                }
            }
            // 直接序列化为 ByteArray，省去 JsonObject->String->ByteArray 中间的 String 副本，
            // 高峰时减少一份完整请求体大小的临时堆驻留。
            .post(requestBody.toString().encodeToByteArray().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    /**
     * 合并相邻的 user 消息。Claude 请求要求 user/assistant 交替；视觉直通在
     * tool_result 之后追加图片 user 消息时会产生连续 user，这里把其 content blocks
     * 追加到前一条 user 消息，保持单条 user 内可同时容纳 tool_result 与 image。
     */
    private fun mergeConsecutiveUserMessages(messages: List<JsonObject>): MutableList<JsonObject> {
        val merged = mutableListOf<JsonObject>()
        messages.forEach { message ->
            val role = message["role"]?.jsonPrimitive?.contentOrNull
            val last = merged.lastOrNull()
            if (role == "user" && last != null && last["role"]?.jsonPrimitive?.contentOrNull == "user") {
                val existing = last["content"]?.jsonArray ?: JsonArray(emptyList())
                val incoming = message["content"]?.jsonArray ?: JsonArray(emptyList())
                merged[merged.lastIndex] = JsonObject(
                    last.toMutableMap().apply { this["content"] = JsonArray(existing + incoming) },
                )
            } else {
                merged.add(message)
            }
        }
        return merged
    }

    /** cache_control 断点：默认 5 分钟 ephemeral；开启 1h TTL 时附加 ttl 字段。 */
    private fun cacheControl(model: ModelConfig): JsonObject = buildJsonObject {
        put("type", "ephemeral")
        if (model.promptCacheTtl1h) put("ttl", "1h")
    }

    /**
     * Prompt Caching 断点 3：给倒数第二条「真实用户」消息注入 cache_control。
     * 真实用户消息 = role=user 且 content 含 text block（排除纯 tool_result 回包）。
     */
    private fun applyUserCacheBreakpoint(model: ModelConfig, messages: MutableList<JsonObject>) {
        if (!model.capabilities.promptCaching) return
        val realUserIndices = messages.indices.filter { index ->
            val message = messages[index]
            if (message["role"]?.jsonPrimitive?.contentOrNull != "user") return@filter false
            val blocks = message["content"]?.jsonArray ?: return@filter false
            // 排除纯 tool_result 回包，以及视觉直通产生的「tool_result + text + image」合并消息：
            // 它们虽带 text block，但不是真正的用户轮，落到断点上会让缓存边界错位。
            blocks.none { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "tool_result" } &&
                blocks.any { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text" }
        }
        if (realUserIndices.size < 2) return
        messages[realUserIndices[realUserIndices.size - 2]] =
            injectCacheControlToMessage(messages[realUserIndices[realUserIndices.size - 2]], model)
    }

    /** 给消息 content 数组的最后一个 block 追加 cache_control:{"type":"ephemeral"}。 */
    private fun injectCacheControlToMessage(message: JsonObject, model: ModelConfig): JsonObject {
        val content = message["content"]?.jsonArray ?: return message
        if (content.isEmpty()) return message
        val cachedContent = JsonArray(
            content.mapIndexed { index, block ->
                if (index != content.lastIndex) {
                    block
                } else {
                    JsonObject(
                        block.jsonObject.toMutableMap().apply {
                            this["cache_control"] = cacheControl(model)
                        },
                    )
                }
            },
        )
        return JsonObject(message.toMutableMap().apply { this["content"] = cachedContent })
    }

    private fun parseFinalResponse(body: String): ChatResult {
        if (!ProviderClient.looksLikeJsonResponse(body)) {
            throw IllegalStateException(ProviderClient.formatHttpErrorMessage(200, body))
        }
        val root = json.parseToJsonElement(body).jsonObject
        val contentBlocks = root["content"]?.jsonArray.orEmpty()
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val calls = mutableListOf<ApiToolCallSpec>()
        contentBlocks.forEach { block ->
            val obj = block as? JsonObject ?: return@forEach
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> text.append(obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                "thinking" -> reasoning.append(obj["thinking"]?.jsonPrimitive?.contentOrNull.orEmpty())
                "tool_use" -> calls += ApiToolCallSpec(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull?.ifBlank { ToolCallIdNormalizer.normalize(null) }
                        ?: ToolCallIdNormalizer.normalize(null),
                    name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    argumentsJson = obj["input"]?.toString() ?: "{}",
                )
            }
        }
        return ChatResult(
            content = text.toString().ifEmpty { null },
            toolCalls = calls,
            reasoningContent = reasoning.toString().ifEmpty { null },
            usage = (root["usage"] as? JsonObject)?.let(::parseUsage) ?: ChatUsage(),
        )
    }

    /** Anthropic usage：input/output_tokens + cache_read/cache_creation_input_tokens。 */
    private fun parseUsage(usage: JsonObject): ChatUsage = ChatUsage(
        inputTokens = usage["input_tokens"]?.jsonPrimitive?.longOrNull ?: 0,
        outputTokens = usage["output_tokens"]?.jsonPrimitive?.longOrNull ?: 0,
        cacheReadTokens = usage["cache_read_input_tokens"]?.jsonPrimitive?.longOrNull ?: 0,
        cacheWriteTokens = usage["cache_creation_input_tokens"]?.jsonPrimitive?.longOrNull ?: 0,
    )

    /** 非 429/5xx 的 HTTP 错误：413 与上下文超限文案走可自愈类型，供引擎紧急压缩后重试。 */
    private fun httpError(code: Int, body: String): Exception {
        val detail = extractError(body)
        val message = "Claude 请求失败 HTTP $code：$detail"
        return when (ProviderClient.contextOverflowException(code, body)) {
            is LlmContextOverflowException -> LlmContextOverflowException(message)
            is LlmInvalidOutputTokensException -> LlmInvalidOutputTokensException(message)
            else -> IllegalStateException(message)
        }
    }

    private fun extractError(body: String): String {
        val message = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]
                ?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return message ?: body.take(512)
    }

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
        /** 1 小时缓存 TTL 扩展所需的 Anthropic beta 头。 */
        const val EXTENDED_CACHE_TTL_BETA = "extended-cache-ttl-2025-04-11"
        /** pause_turn 连续续跑上限：防止异常服务端无限暂停拖死会话。 */
        const val MAX_PAUSE_TURN_CONTINUATIONS = 8
        const val DEFAULT_MAX_TOKENS = 8192
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
