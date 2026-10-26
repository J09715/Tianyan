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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/**
 * OpenAI Responses API（POST /responses）适配层：把内部统一的 OpenAI 风格 [ApiMessage] 列表
 * 翻译成 Responses API 的 `input` items 结构，并把响应（含 SSE 流式）映射回 [ChatResult]。
 *
 * 与 [ChatApi]（chat/completions）的主要协议差异：
 * - 系统提示词放在顶层 `instructions`，不在 messages/input 里；
 * - 历史消息用 `input` items 表示：`{role:user|assistant, content:[...]}`、
 *   `{type:"function_call", call_id, name, arguments}`、
 *   `{type:"function_call_output", call_id, output}`；
 * - 工具定义为扁平结构 `{type:"function", name, description, parameters}`；
 * - 流式事件为 response.* 事件族：`response.output_text.delta`（正文增量）、
 *   `response.reasoning_text.delta` / `response.reasoning_summary_text.delta`（推理增量）、
 *   `response.output_item.added` / `response.function_call_arguments.delta`（工具调用分片）、
 *   `response.completed`（最终 usage 与完整输出）。
 */
internal class ResponsesApi(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) : LlmApiAdapter {
    override val api = LlmApi.OPENAI_RESPONSES
    @OptIn(InternalCoroutinesApi::class)
    override suspend fun chat(model: ModelConfig, messages: List<ApiMessage>): ChatResult =
        withContext(Dispatchers.IO) {
            val call = okHttpClient.newCall(buildResponsesRequest(model, messages, stream = false))
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
                    parseFinalResponse(body, model)
                }
            } finally {
                cancelHandle?.dispose()
            }
        }

    @OptIn(InternalCoroutinesApi::class)
    override suspend fun chatStream(
        model: ModelConfig,
        messages: List<ApiMessage>,
        onReasoning: (String) -> Unit,
        onToolProgress: (ToolCallStreamProgress) -> Unit,
        onDelta: (String) -> Unit,
    ): ChatResult = withContext(Dispatchers.IO) {
        val call = okHttpClient.newCall(buildResponsesRequest(model, messages, stream = true))
        // 与 ChatApi 一致：取消时立即关闭 socket，保证"停止"秒级生效
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion(onCancelling = true) { call.cancel() }
        val firstEventTimeoutMs = firstEventTimeoutMs(messages, model)
        val firstEventState = AtomicInteger(ProviderClient.FIRST_EVENT_WAITING)
        val firstEventWatchdog = launch { awaitFirstEventTimeout(firstEventTimeoutMs, firstEventState) { call.cancel() } }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val rawBody = response.body.string().take(512)
                    if (response.code == 429) {
                        throw ProviderClient.rateLimitException(response.code, rawBody, response.header("Retry-After"))
                    }
                    if (response.code in 500..599) {
                        throw ProviderClient.transientHttpException(response.code, rawBody, response.header("Retry-After"))
                    }
                    throw httpError(response.code, rawBody)
                }
                val source = response.body.source()
                // 单轮流式输出硬失败预算：正文/思考经 demuxer 计入，工具入参在累积点计入。
                val budget = StreamBudget()
                val demuxer = ThinkTagStreamDemuxer(onReasoning, onDelta, budget = budget)
                val receivedText = StringBuilder()
                // item_id -> 工具调用累积器（Responses 以 item_id 区分同一轮多个 function_call）
                val toolCalls = mutableMapOf<String, ToolCallAccumulator>()
                var usage = ChatUsage()
                val completedItems = sortedMapOf<Int, JsonObject>()
                var nativeOutput: JsonArray? = null
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
                        // 正文增量
                        "response.output_text.delta" -> {
                            event["delta"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
                                ?.let { receivedText.append(it); demuxer.onContentChunk(it) }
                        }
                        // 推理增量：detail 推理与 summary 摘要都作为推理内容回传
                        "response.reasoning_text.delta", "response.reasoning_summary_text.delta" -> {
                            event["delta"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
                                ?.let { demuxer.onExplicitReasoningChunk(it) }
                        }
                        // function_call item 开始：携带 call_id 与 name
                        "response.output_item.added" -> {
                            val item = event["item"] as? JsonObject ?: continue
                            if (item["type"]?.jsonPrimitive?.contentOrNull == "function_call") {
                                val itemId = item["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                val accum = toolCalls.getOrPut(itemId) { ToolCallAccumulator() }
                                accum.id = item["call_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                                    ?: itemId
                                accum.name = item["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                accum.publishProgress(onToolProgress)
                            }
                        }
                        // function_call 参数增量分片
                        "response.function_call_arguments.delta" -> {
                            val itemId = event["item_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val chunk = event["delta"]?.jsonPrimitive?.contentOrNull
                            if (!chunk.isNullOrEmpty()) {
                                val accum = toolCalls.getOrPut(itemId) { ToolCallAccumulator() }
                                budget.add(chunk)
                                accum.arguments.append(chunk)
                                accum.publishProgress(onToolProgress)
                            }
                        }
                        // 兼容部分网关不下发 arguments.delta、只在 item.done 里带完整参数的情况
                        "response.output_item.done" -> {
                            val item = event["item"] as? JsonObject ?: continue
                            (event["output_index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull())?.let { completedItems[it] = item }
                            if (item["type"]?.jsonPrimitive?.contentOrNull == "function_call") {
                                val itemId = item["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                val accum = toolCalls.getOrPut(itemId) { ToolCallAccumulator() }
                                if (accum.id.isBlank()) {
                                    accum.id = item["call_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                                        ?: itemId
                                }
                                if (accum.name.isBlank()) accum.name = item["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                val args = item["arguments"]?.jsonPrimitive?.contentOrNull
                                if (args != null && accum.arguments.isEmpty() && args.isNotBlank()) {
                                    budget.add(args)
                                    accum.arguments.append(args)
                                }
                                accum.publishProgress(onToolProgress)
                            }
                        }
                        // 结束：携带最终 usage（与完整 output）
                        "response.completed" -> {
                            val finalResponse = event["response"] as? JsonObject
                            completed = finalResponse?.get("status")?.jsonPrimitive?.contentOrNull in listOf(null, "completed")
                            nativeOutput = finalResponse?.get("output") as? JsonArray
                            if (nativeOutput == null && completedItems.isNotEmpty() &&
                                completedItems.keys.toList() == completedItems.keys.indices.toList()
                            ) nativeOutput = JsonArray(completedItems.values.toList())
                            ((event["response"] as? JsonObject)?.get("usage") as? JsonObject)
                                ?.let { usage = parseUsage(it) }
                            break
                        }
                        "response.failed" -> {
                            val error = (event["response"] as? JsonObject)?.get("error") as? JsonObject
                            val message = error?.get("message")?.jsonPrimitive?.contentOrNull ?: "响应处理失败"
                            throw providerStreamError(error?.toString() ?: message)
                        }
                        "response.incomplete" -> {
                            val incompleteResponse = event["response"] as? JsonObject
                            val reason = (incompleteResponse?.get("incomplete_details") as? JsonObject)
                                ?.get("reason")?.jsonPrimitive?.contentOrNull
                            requireOutputCompletion(reason, ProviderClient.estimateApiMessageTokens(messages), receivedText.length + demuxer.fullReasoning.length)
                            throw IllegalStateException("Responses 响应未完成：${reason ?: "未知原因"}")
                        }
                        "error" -> {
                            throw providerStreamError(event.toString())
                        }
                        else -> Unit // response.created / in_progress / content_part.* 等无需处理
                    }
                }
                requireStreamCompletion(completed, "Responses")
                val finalResult = nativeOutput?.takeIf { completed && it.isNotEmpty() }?.let {
                    parseFinalResponse(buildJsonObject { put("output", it) }.toString(), model)
                }
                // Some gateways provide only completed output, or omit the last text delta.
                val emittedText = receivedText.toString()
                finalResult?.content?.takeIf { it.startsWith(emittedText) && it.length > emittedText.length }
                    ?.let { demuxer.onContentChunk(it.substring(emittedText.length)) }
                demuxer.flush()
                toolCalls.values.forEach { it.publishProgress(onToolProgress, force = true) }
                // 无参数函数可能不下发 arguments 分片，空串兜底为 "{}"
                val calls = toolCalls.values.map {
                    ApiToolCallSpec(it.id, it.name, it.arguments.toString().ifBlank { "{}" })
                }
                ChatResult(
                    content = demuxer.fullText.toString().ifEmpty { null },
                    toolCalls = finalResult?.toolCalls ?: calls,
                    reasoningContent = demuxer.fullReasoning.toString().ifEmpty { finalResult?.reasoningContent },
                    usage = usage,
                    responsesTurn = if (completed) ResponsesTurn.capture(model, nativeOutput) else null,
                )
            }
        } catch (io: IOException) {
            throw streamFailure(io, firstEventState.get(), firstEventTimeoutMs, okHttpClient.readTimeoutMillis.toLong())
        } finally {
            firstEventWatchdog.cancel()
            cancelHandle?.dispose()
        }
    }

    /** 非流式响应：解析 output 数组（message / function_call / reasoning）与顶层 usage。 */
    private fun parseFinalResponse(body: String, model: ModelConfig): ChatResult {
        if (!ProviderClient.looksLikeJsonResponse(body)) {
            throw IllegalStateException(ProviderClient.formatHttpErrorMessage(200, body))
        }
        val root = json.parseToJsonElement(body).jsonObject
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val calls = mutableListOf<ApiToolCallSpec>()
        (root["output"]?.jsonArray.orEmpty()).forEach { element ->
            val item = element as? JsonObject ?: return@forEach
            when (item["type"]?.jsonPrimitive?.contentOrNull) {
                "message" -> {
                    (item["content"]?.jsonArray.orEmpty()).forEach { part ->
                        val partObj = part as? JsonObject ?: return@forEach
                        if (partObj["type"]?.jsonPrimitive?.contentOrNull == "output_text") {
                            text.append(partObj["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        }
                    }
                }
                "function_call" -> calls += ApiToolCallSpec(
                    id = item["call_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: item["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: ToolCallIdNormalizer.normalize(null),
                    name = item["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    argumentsJson = item["arguments"]?.jsonPrimitive?.contentOrNull?.ifBlank { "{}" } ?: "{}",
                )
                "reasoning" -> {
                    (item["summary"]?.jsonArray.orEmpty()).forEach { part ->
                        val partObj = part as? JsonObject ?: return@forEach
                        if (partObj["type"]?.jsonPrimitive?.contentOrNull == "summary_text") {
                            reasoning.append(partObj["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        }
                    }
                }
            }
        }
        return ChatResult(
            content = text.toString().ifEmpty { null },
            toolCalls = calls,
            reasoningContent = reasoning.toString().ifEmpty { null },
            usage = (root["usage"] as? JsonObject)?.let { parseUsage(it) } ?: ChatUsage(),
            responsesTurn = if (root["status"]?.jsonPrimitive?.contentOrNull in listOf(null, "completed"))
                ResponsesTurn.capture(model, root["output"] as? JsonArray) else null,
        )
    }

    /** Responses API usage：input/output_tokens + cached/reasoning details。 */
    private fun parseUsage(usage: JsonObject): ChatUsage = ChatUsage(
        inputTokens = usage["input_tokens"]?.jsonPrimitive?.longOrNull ?: 0,
        outputTokens = usage["output_tokens"]?.jsonPrimitive?.longOrNull ?: 0,
        reasoningTokens = (usage["output_tokens_details"] as? JsonObject)
            ?.get("reasoning_tokens")?.jsonPrimitive?.longOrNull ?: 0,
        cacheReadTokens = (usage["input_tokens_details"] as? JsonObject)
            ?.get("cached_tokens")?.jsonPrimitive?.longOrNull ?: 0,
    )

    /** 非 429/5xx 的 HTTP 错误：413 与上下文超限文案走可自愈类型，供引擎紧急压缩后重试。 */
    private fun httpError(code: Int, body: String): Exception {
        val detail = extractError(body)
        val message = "Responses 请求失败 HTTP $code：$detail"
        return when (ProviderClient.contextOverflowException(code, body)) {
            is LlmContextOverflowException -> LlmContextOverflowException(message)
            is LlmInvalidOutputTokensException -> LlmInvalidOutputTokensException(message)
            else -> IllegalStateException(message)
        }
    }

    private fun extractError(body: String): String {
        val message = runCatching {
            val obj = json.parseToJsonElement(body) as? JsonObject
            val error = (obj?.get("error") as? JsonObject)
                ?: (obj?.get("response") as? JsonObject)?.get("error") as? JsonObject
            error?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return message ?: body.take(512)
    }

}
