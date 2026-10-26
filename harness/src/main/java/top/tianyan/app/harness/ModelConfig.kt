package top.tianyan.app.harness

import top.tianyan.app.harness.core.LlmApi
import top.tianyan.app.harness.core.ModelCapabilities
import top.tianyan.app.harness.core.ModelDescriptor
import top.tianyan.app.core.model.McpToolInfo

/** 解析后的模型运行配置。 */
data class ModelConfig(
    val name: String,
    val provider: String,
    val model: String,
    val baseUrl: String,
    val apiKey: String?,
    /** 同一接口地址下参与轮询的 Key 池；为空时兼容使用 [apiKey]。 */
    val apiKeys: List<String> = emptyList(),
    /** 单 Key 每分钟请求上限；0 表示不限。 */
    val requestsPerMinutePerKey: Int = 0,
    /** 接入协议：OPENAI 兼容或 Anthropic Messages API。 */
    val protocol: ApiProtocol = ApiProtocol.OPENAI,
    /** 推理参数（null = 服务端默认）。 */
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    /** 推理开关：AUTO = 跟随模型服务端默认。 */
    val reasoningMode: ReasoningMode = ReasoningMode.AUTO,
    /** 推理强度：null = 服务端默认。 */
    val reasoningEffort: ReasoningEffort? = null,
    /**
     * 工具调用模式：NATIVE = OpenAI 标准 function calling（注入 tools）；
     * JSON_TEXT = 工具列表写入系统提示词，模型用文本输出工具调用；
     * DISABLED = 禁用工具（纯聊天）。
     */
    val toolCallMode: ToolCallMode = ToolCallMode.NATIVE,
    val dynamicMcpTools: List<McpToolInfo> = emptyList(),
    /** 上下文 Token 容量上限（如 128000，超出时滑动窗口压缩）。 */
    val contextTokens: Int? = null,
    /**
     * 每模型压缩预算覆盖（对齐 pi compaction.modelOverrides）：
     * 压缩触发时保留的最近 token 上限（null = 不启用该收紧）。
     */
    val compactionKeepRecentTokens: Int? = null,
    /** 为 LLM 响应预留的 token（null = 使用内置默认 8192）。 */
    val compactionReserveTokens: Int? = null,
    /** 自定义请求头（多行 Key: Value 格式）。 */
    val customHeaders: String = "",
    /** 纯净排查模式：不注入系统提示词与工具。 */
    val pureChatMode: Boolean = false,
    /** 是否支持视觉多模态直接发送图片。 */
    val visionEnabled: Boolean = true,
    /** 是否使用 OpenAI Responses API（true = POST /responses；false = /chat/completions）。 */
    val responseApiEnabled: Boolean = false,
    /**
     * Anthropic Prompt Caching：请求时注入 cache_control 断点
     * （System 末尾 / Tools 末尾 / 倒数第二轮真实 User 消息）。仅对原生 Anthropic
     * Messages API 生效；由 toModelConfig 按协议自动启用，避免给不支持的代理注入字段。
     */
    val promptCachingEnabled: Boolean = false,
    /** 是否使用 1 小时缓存 TTL（cache_control.ttl=1h + 扩展 beta 头）；默认 5 分钟。 */
    val promptCacheTtl1h: Boolean = false,
    /**
     * 等待首个模型事件或心跳的毫秒数。默认 90 秒；0 表示关闭这项本机上限。
     * 由全局设置在解析模型时写入，不是供应商接口参数。
     */
    val firstResponseTimeoutMs: Long = top.tianyan.app.core.model.FirstResponseTimeoutPolicy.DEFAULT_TIMEOUT_MS,
) {
    /** Legacy flags are decoded once here; Responses retains its historical precedence. */
    val api: LlmApi
        get() = when {
            responseApiEnabled -> LlmApi.OPENAI_RESPONSES
            protocol == ApiProtocol.ANTHROPIC -> LlmApi.ANTHROPIC_MESSAGES
            else -> LlmApi.OPENAI_COMPLETIONS
        }

    val effectiveToolCallMode: ToolCallMode
        get() = if (pureChatMode) ToolCallMode.DISABLED else toolCallMode

    /** Effective profile settings, not a claim of remotely discovered model support. */
    val capabilities: ModelCapabilities
        get() {
            val reasoning = ReasoningAdapter.capabilities(this)
            return ModelCapabilities(
                images = visionEnabled,
                nativeTools = effectiveToolCallMode == ToolCallMode.NATIVE,
                textTools = effectiveToolCallMode == ToolCallMode.JSON_TEXT,
                promptCaching = promptCachingEnabled && api == LlmApi.ANTHROPIC_MESSAGES,
                reasoningDisable = reasoning.supportsDisable,
                reasoningEffort = reasoning.supportsEffort,
                contextWindow = contextTokens,
                maxOutputTokens = maxTokens,
            )
        }

    val descriptor: ModelDescriptor
        get() = ModelDescriptor(model, provider, api, capabilities)
}

/** LLM 接入协议：绝大多数厂商提供 OpenAI 兼容端点；Anthropic Claude 需要专用适配。 */
enum class ApiProtocol { OPENAI, ANTHROPIC }

/** 工具调用模式：NATIVE = 标准函数调用；JSON_TEXT = 文本 JSON 标记；DISABLED = 禁用。 */
enum class ToolCallMode { NATIVE, JSON_TEXT, DISABLED }
