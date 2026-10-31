package top.tianyan.app.harness

import java.net.URI
import top.tianyan.app.harness.core.LlmApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 推理开关：AUTO = 跟随模型服务端默认。 */
enum class ReasoningMode { AUTO, DISABLED, ENABLED }

/** 推理强度：null = 服务端默认。 */
enum class ReasoningEffort { LOW, MEDIUM, HIGH, MAX }

/** 当前协议适配器对「推理开关 / 强度」的编码能力。 */
data class ReasoningCapabilities(
    val supportsDisable: Boolean,
    val supportsEffort: Boolean,
)

/**
 * 把统一的「推理开关 + 强度」配置翻译成各家 API 的参数格式。
 *
 * 各厂商字段差异：
 * - OpenAI（Chat Completions）：`reasoning_effort: none/low/medium/high`
 * - Gemini（OpenAI 兼容端点）：`thinking_config: {"thinkingBudget": 0..32768}`
 * - 智谱 GLM：`thinking: {"type": enabled/disabled, thinking_budget?}`
 * - 豆包（火山方舟）：`thinking: {"type": enabled/disabled}`
 * - OpenRouter：`reasoning: {"effort": none/low/medium/high}`
 * - Anthropic Claude（Messages API）：`thinking: {"type": enabled/disabled, budget_tokens}`
 *
 * 规则：
 * - AUTO：一律不注入任何参数（服务端默认）；
 * - 未知厂商（DeepSeek reasoner 等不支持调节的）保守不注入，避免未知字段 400。
 */
object ReasoningAdapter {

    /** OpenAI 兼容协议下，追加到请求体顶层的字段（空 Map = 不注入）。 */
    fun openAiFields(model: ModelConfig): Map<String, JsonElement> {
        if (model.reasoningMode == ReasoningMode.AUTO) return emptyMap()
        val host = hostOf(model.baseUrl)
        val provider = model.provider.lowercase()
        val modelName = model.model.lowercase()
        return when {
            isGemini(host, provider, modelName) -> geminiFields(model)
            isZhipu(host, provider, modelName) -> zhipuFields(model)
            isDoubao(host, provider, modelName) -> thinkingTypeFields(model)
            isOpenRouter(host, provider) -> openRouterFields(model)
            isOpenAiOfficial(host, provider) -> openAiOfficialFields(model)
            // 中转站/自定义网关（OneAPI / NewAPI / 自建反代等）：
            // 绝大多数走 OpenAI 兼容协议，注入行业标准的 reasoning_effort（已被 DeepSeek、Qwen、Kimi、OpenAI 等官方及中转网关广泛支持）。
            else -> openAiOfficialFields(model)
        }
    }

    /** Anthropic Messages API 的 thinking 参数（null = 不注入）。 */
    fun anthropicThinking(model: ModelConfig, effectiveMaxTokens: Int = model.maxTokens ?: 8_192): JsonObject? {
        if (model.reasoningMode == ReasoningMode.AUTO) return null
        return if (model.reasoningMode == ReasoningMode.DISABLED) {
            buildJsonObject { put("type", "disabled") }
        } else {
            // Anthropic cannot represent enabled thinking when the entire output budget is <= 1024.
            if (effectiveMaxTokens <= 1_024) return null
            buildJsonObject {
                put("type", "enabled")
                // budget_tokens 必填；按强度映射并保证严格小于 max_tokens（Anthropic 硬性要求）
                val budget = model.reasoningEffort.budgetTokens()
                put(
                    "budget_tokens",
                    budget.coerceIn(1_024, effectiveMaxTokens - 1),
                )
            }
        }
    }

    /**
     * OpenAI Responses API（POST /responses）的推理参数：顶层 `reasoning: {effort: low|medium|high}`。
     *
     * 与 Chat Completions 的 `reasoning_effort` 不同，Responses API 没有合法的 "none" 档位，
     * 因此 DISABLED 时省略该字段（跟随服务端默认），避免发送未知取值被 400。
     * ENABLED 但未指定强度时使用中等档位 medium。
     */
    fun responsesFields(model: ModelConfig): Map<String, JsonElement> {
        if (model.reasoningMode == ReasoningMode.AUTO) return emptyMap()
        if (model.reasoningMode == ReasoningMode.DISABLED) return emptyMap()
        return mapOf(
            "reasoning" to buildJsonObject {
                put("effort", model.reasoningEffort?.openAiName() ?: "medium")
            },
        )
    }

    // ---------- 厂商判定 ----------

    /**
     * 全局偏好只应用到当前序列化器可编码的选项。
     * Messages 用 thinking 开关和预算；Responses 当前只编码强度；
     * Chat Completions 继续按厂商选择兼容字段（豆包只提供开关）。
     */
    fun capabilities(model: ModelConfig): ReasoningCapabilities {
        // Capabilities must describe the selected serializer, even on a custom gateway.
        when (model.api) {
            LlmApi.OPENAI_RESPONSES -> return ReasoningCapabilities(supportsDisable = false, supportsEffort = true)
            LlmApi.ANTHROPIC_MESSAGES -> return ReasoningCapabilities(supportsDisable = true, supportsEffort = true)
            LlmApi.OPENAI_COMPLETIONS -> Unit
        }
        val host = hostOf(model.baseUrl)
        val provider = model.provider.lowercase()
        val modelName = model.model.lowercase()
        val known = isGemini(host, provider, modelName) || isZhipu(host, provider, modelName) || isDoubao(host, provider, modelName) ||
            isOpenRouter(host, provider) || isOpenAiOfficial(host, provider)
        if (!known) {
            // 未知 Chat Completions 网关继续使用既有的 reasoning_effort 兼容策略。
            return ReasoningCapabilities(supportsDisable = true, supportsEffort = true)
        }
        // 豆包：只有开关，无强度档位
        val supportsEffort = !isDoubao(host, provider, modelName)
        return ReasoningCapabilities(supportsDisable = true, supportsEffort = supportsEffort)
    }

    private fun hostOf(baseUrl: String): String =
        runCatching { URI(baseUrl.trim()).host?.lowercase() }.getOrNull().orEmpty()

    private fun isGemini(host: String, provider: String, modelName: String = "") =
        host.contains("generativelanguage.googleapis.com") || provider.contains("gemini") || modelName.contains("gemini")

    private fun isZhipu(host: String, provider: String, modelName: String = "") =
        host.contains("bigmodel.cn") || provider.contains("智谱") || provider.contains("glm") || modelName.contains("glm")

    private fun isDoubao(host: String, provider: String, modelName: String = "") =
        host.contains("volces.com") || provider.contains("豆包") || provider.contains("doubao") || modelName.contains("doubao")

    private fun isOpenRouter(host: String, provider: String) =
        host.contains("openrouter.ai") || provider.contains("openrouter")

    private fun isOpenAiOfficial(host: String, provider: String) =
        host == "api.openai.com" || provider.contains("openai")

    // ---------- 各厂商字段 ----------

    private fun geminiFields(model: ModelConfig): Map<String, JsonElement> =
        mapOf("thinking_config" to buildJsonObject {
            put("thinkingBudget", geminiBudget(model))
        })

    private fun zhipuFields(model: ModelConfig): Map<String, JsonElement> =
        mapOf("thinking" to buildJsonObject {
            put("type", if (model.reasoningMode == ReasoningMode.ENABLED) "enabled" else "disabled")
            if (model.reasoningMode == ReasoningMode.ENABLED) {
                model.reasoningEffort?.let { put("thinking_budget", it.glmBudget()) }
            }
        })

    private fun thinkingTypeFields(model: ModelConfig): Map<String, JsonElement> =
        mapOf("thinking" to buildJsonObject {
            put("type", if (model.reasoningMode == ReasoningMode.ENABLED) "enabled" else "disabled")
        })

    private fun openRouterFields(model: ModelConfig): Map<String, JsonElement> =
        mapOf("reasoning" to buildJsonObject {
            put(
                "effort",
                if (model.reasoningMode == ReasoningMode.DISABLED) {
                    "none"
                } else {
                    model.reasoningEffort?.openAiName() ?: "medium"
                },
            )
        })

    private fun openAiOfficialFields(model: ModelConfig): Map<String, JsonElement> =
        if (model.reasoningMode == ReasoningMode.DISABLED) {
            mapOf("reasoning_effort" to JsonPrimitive("none"))
        } else {
            model.reasoningEffort?.let { mapOf("reasoning_effort" to JsonPrimitive(it.openAiName())) }.orEmpty()
        }

    // ---------- 数值映射 ----------

    private fun ReasoningEffort.openAiName(): String = when (this) {
        ReasoningEffort.LOW -> "low"
        ReasoningEffort.MEDIUM -> "medium"
        ReasoningEffort.HIGH, ReasoningEffort.MAX -> "high"
    }

    private fun ReasoningEffort?.budgetTokens(): Int = when (this) {
        ReasoningEffort.LOW -> 1024
        ReasoningEffort.MEDIUM -> 2048
        ReasoningEffort.HIGH -> 8192
        ReasoningEffort.MAX -> 24576
        null -> 2048
    }

    private fun ReasoningEffort.glmBudget(): Int = when (this) {
        ReasoningEffort.LOW -> 1024
        ReasoningEffort.MEDIUM -> 2048
        ReasoningEffort.HIGH -> 4096
        ReasoningEffort.MAX -> 8192
    }

    private fun geminiBudget(model: ModelConfig): Int = when {
        model.reasoningMode == ReasoningMode.DISABLED -> 0
        model.reasoningEffort == ReasoningEffort.LOW -> 1024
        model.reasoningEffort == ReasoningEffort.MEDIUM -> 2048
        model.reasoningEffort == ReasoningEffort.HIGH -> 8192
        model.reasoningEffort == ReasoningEffort.MAX -> 24576
        else -> 2048
    }
}
