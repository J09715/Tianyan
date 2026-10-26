package top.tianyan.app.harness

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import top.tianyan.app.harness.core.LlmApi

/** Shared wire contract. Authentication and provider-specific fields stay in each adapter. */
interface LlmApiAdapter {
    val api: LlmApi

    suspend fun chat(model: ModelConfig, messages: List<ApiMessage>): ChatResult

    suspend fun chatStream(
        model: ModelConfig,
        messages: List<ApiMessage>,
        onReasoning: (String) -> Unit = {},
        onToolProgress: (ToolCallStreamProgress) -> Unit = {},
        onDelta: (String) -> Unit,
    ): ChatResult
}

/** Immutable registry: no silent fallback and no ambiguous protocol registration. */
internal class LlmApiRegistry(adapters: List<LlmApiAdapter>, providerOverrides: Map<Pair<LlmApi, String>, LlmApiAdapter> = emptyMap(),
    private val liveOverrides: (() -> Map<Pair<LlmApi, String>, LlmApiAdapter>)? = null) {
    private val providerOverrides = providerOverrides.toMap()
    private val byApi = adapters.associateBy { it.api }

    init {
        require(byApi.size == adapters.size) { "Duplicate LLM API adapter" }
        require(byApi.keys == LlmApi.entries.toSet()) { "Missing LLM API adapter" }
        require(providerOverrides.all { (key, adapter) -> key.first == adapter.api }) { "Provider API mismatch" }
    }

    fun forModel(model: ModelConfig): LlmApiAdapter {
        val overrides = liveOverrides?.invoke() ?: providerOverrides
        if (model.provider.startsWith("plugin.")) return overrides.entries.singleOrNull { it.key.second == model.provider }?.value
            ?: error("外部 Provider 未安装或版本已改变")
        overrides[model.api to model.provider]?.let { return it }
        return byApi.getValue(model.api)
    }

    companion object {
        fun builtIn(client: OkHttpClient, json: Json): LlmApiRegistry = LlmApiRegistry(
            listOf(ChatApi(client, json), ResponsesApi(client, json), AnthropicApi(client, json)),
        )
    }
}
