package top.tianyan.app.harness

import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal fun buildResponsesRequest(model: ModelConfig, messages: List<ApiMessage>, stream: Boolean): Request {
    val systemPrompt = StringBuilder()
    val nativeResultIds = mutableMapOf<String, String>()
    val nativeCallIds = mutableSetOf<String>()
    val nativeItemIds = mutableSetOf<String>()
    val inputItems = buildJsonArray {
        var index = 0
        while (index < messages.size) {
            val message = messages[index]
            when (message.role) {
                "system" -> {
                    // 系统提示词聚合到顶层 instructions
                    if (!message.content.isNullOrBlank()) {
                        if (systemPrompt.isNotEmpty()) systemPrompt.append("\n\n")
                        systemPrompt.append(message.content)
                    }
                    index++
                }
                "user" -> {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "content",
                                buildJsonArray {
                                    if (!message.content.isNullOrBlank()) {
                                        add(
                                            buildJsonObject {
                                                put("type", "input_text")
                                                put("text", message.content)
                                            },
                                        )
                                    }
                                    message.imageUrls.forEach { url ->
                                        add(
                                            buildJsonObject {
                                                put("type", "input_image")
                                                put("image_url", url)
                                            },
                                        )
                                    }
                                    if (message.content.isNullOrBlank() && message.imageUrls.isEmpty()) {
                                        // 空 user 消息补一个空文本，避免 content 空数组被 400
                                        add(
                                            buildJsonObject {
                                                put("type", "input_text")
                                                put("text", "")
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                    index++
                }
                "assistant" -> {
                    nativeResultIds.clear()
                    val turn = message.responsesTurn?.takeIf { it.matches(model) }
                    val mapping = turn?.let { ResponsesReplay.mappingForRequest(it, message, messages.drop(index + 1)) }
                        ?.takeIf { candidate -> candidate.values.none { it in nativeCallIds } &&
                            turn.output.none { (it["id"] as? JsonPrimitive)?.contentOrNull in nativeItemIds } }
                    if (turn != null && mapping != null) {
                        turn.output.forEach { add(it) }
                        nativeResultIds.putAll(mapping)
                        nativeCallIds.addAll(mapping.values)
                        turn.output.mapNotNull { (it["id"] as? JsonPrimitive)?.contentOrNull }.forEach { nativeItemIds.add(it) }
                        index++
                        continue
                    }
                    // Legacy text has no native reasoning identity; never fabricate an opaque reasoning item.
                    add(
                        buildJsonObject {
                            put("role", "assistant")
                            put(
                                "content",
                                buildJsonArray {
                                    if (!message.content.isNullOrBlank()) {
                                        add(
                                            buildJsonObject {
                                                put("type", "output_text")
                                                put("text", message.content)
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                    // 历史工具调用：以 function_call item 逐条回传
                    message.tool_calls.orEmpty().forEach { call ->
                        add(
                            buildJsonObject {
                                put("type", "function_call")
                                put("call_id", call.id)
                                put("name", call.function.name)
                                put("arguments", call.function.arguments.ifBlank { "{}" })
                            },
                        )
                    }
                    index++
                }
                "tool" -> {
                    add(
                        buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", nativeResultIds[message.tool_call_id] ?: message.tool_call_id.orEmpty())
                            put("output", message.content.orEmpty())
                        },
                    )
                    index++
                }
                else -> index++
            }
        }
    }

    val dynamicTools = if (model.pureChatMode) emptyList() else ProviderClient.buildDynamicTools(model, model.redTeamEnabled)
    // JSON_TEXT 模式：工具定义写进 instructions，模型用文本输出工具调用
    if (model.capabilities.textTools && dynamicTools.isNotEmpty()) {
        systemPrompt.append("\n\n## 可用工具 JSON 定义（必须严格按此 name 与参数输出）\n")
            .append(ProviderClient.buildToolsTextDescription(dynamicTools))
    }
    // NATIVE 模式下 tools 数组独立于 input，输出预算必须显式扣掉 schema
    val toolSchemaTokens =
        if (model.capabilities.nativeTools) {
            ContextWindowPolicy.estimateToolDefinitionTokens(dynamicTools)
        } else {
            0
        }

    val requestBody = buildJsonObject {
        put("model", model.model)
        put("stream", stream)
        put("include", buildJsonArray { add(JsonPrimitive("reasoning.encrypted_content")) })
        model.temperature?.let { put("temperature", it) }
        model.topP?.let { put("top_p", it) }
        // Responses API 的输出上限字段名与 chat/completions 不同
        put("max_output_tokens", ContextWindowPolicy.outputBudget(
            model.maxTokens,
            8_192,
            messages,
            model.contextTokens,
            model.model,
            model.provider,
            toolSchemaTokens,
        ))
        // 推理开关/强度：Responses 专用 reasoning.effort 格式
        ReasoningAdapter.responsesFields(model).forEach { (key, value) -> put(key, value) }
        if (systemPrompt.isNotBlank()) put("instructions", systemPrompt.toString())
        put("input", inputItems)
        // 仅 NATIVE 模式注入标准 tools；纯净模式与 JSON_TEXT / DISABLED 均不注入
        if (model.capabilities.nativeTools && dynamicTools.isNotEmpty()) {
            put(
                "tools",
                buildJsonArray {
                    dynamicTools.forEach { definition ->
                        add(
                            buildJsonObject {
                                put("type", "function")
                                put("name", definition.function.name)
                                put("description", definition.function.description)
                                put("parameters", definition.function.parameters)
                            },
                        )
                    }
                },
            )
            put("tool_choice", "auto")
        }
    }

    return Request.Builder()
        .url("${model.baseUrl.trimEnd('/')}/responses")
        .header("Content-Type", "application/json")
        .apply {
            model.apiKey?.let { header("Authorization", "Bearer $it") }
            ProviderClient.parseCustomHeaders(model.customHeaders).forEach { (name, value) ->
                header(name, value)
            }
        }
        .post(requestBody.toString().encodeToByteArray().toRequestBody(ProviderClient.JSON_MEDIA_TYPE))
        .build()
}

