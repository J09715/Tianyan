package top.tianyan.app.harness.plugins

import kotlinx.serialization.json.*
import top.tianyan.app.harness.*
import top.tianyan.app.harness.core.LlmApi

/** Buffered adapter over an external MCP tool. Only the selected model's transcript is shared. */
internal class ExternalPluginProvider(
    override val api: LlmApi,
    private val invoke: suspend (JsonObject) -> PluginToolOutput,
) : LlmApiAdapter {
    override suspend fun chat(model: ModelConfig, messages: List<ApiMessage>): ChatResult {
        // External providers configure their own credentials; the host key store is never forwarded.
        require(model.apiKey.isNullOrBlank() && model.apiKeys.all { it.isBlank() }) {
            "外部 Provider 请在插件内配置凭据，不接受宿主模型密钥"
        }
        val arguments = buildJsonObject {
            put("model", model.model)
            put("messages", Json.encodeToJsonElement(messages))
            put("maxTokens", model.maxTokens?.let(::JsonPrimitive) ?: JsonNull)
            put("temperature", model.temperature?.let(::JsonPrimitive) ?: JsonNull)
            put("tools", buildJsonArray { })
        }
        require(arguments.toString().toByteArray(Charsets.UTF_8).size <= 240 * 1024) { "外部 Provider 请求过大" }
        val output = invoke(arguments)
        check(output.success) { "外部 Provider 调用失败" }
        require(output.text.toByteArray(Charsets.UTF_8).size <= 262_144) { "外部 Provider 返回过大" }
        val result = Json.parseToJsonElement(output.text).jsonObject
        require(result.keys.all { it in setOf("content", "reasoning", "toolCalls") }) { "Provider 结果含未知字段" }
        fun text(key: String): String? = result[key]?.let {
            if (it == JsonNull) null else (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content
                ?: error("Provider 文字结果无效")
        }
        require(result["toolCalls"] == null || result["toolCalls"] is JsonArray) { "Provider 工具调用结果无效" }
        val tools = (result["toolCalls"] as? JsonArray).orEmpty().map { element ->
            val call = element.jsonObject
            require(call.keys == setOf("id", "name", "arguments") && call["arguments"] is JsonObject)
            require(listOf("id", "name").all { key -> (call[key] as? JsonPrimitive)?.let { it.isString && it.content.length in 1..128 } == true })
            ApiToolCallSpec(call.getValue("id").jsonPrimitive.content, call.getValue("name").jsonPrimitive.content,
                call.getValue("arguments").toString())
        }
        require(tools.size <= 32 && tools.map { it.id }.distinct().size == tools.size)
        return ChatResult(text("content"), tools, text("reasoning"))
    }

    override suspend fun chatStream(model: ModelConfig, messages: List<ApiMessage>, onReasoning: (String) -> Unit,
        onToolProgress: (ToolCallStreamProgress) -> Unit, onDelta: (String) -> Unit): ChatResult =
        chat(model, messages).also { result ->
            result.reasoningContent?.let(onReasoning)
            result.content?.let(onDelta)
        }
}
