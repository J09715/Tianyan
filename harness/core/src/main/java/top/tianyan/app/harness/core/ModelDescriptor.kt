package top.tianyan.app.harness.core

/** Wire format is selected per model, independently of the provider's name. */
enum class LlmApi { OPENAI_COMPLETIONS, OPENAI_RESPONSES, ANTHROPIC_MESSAGES }

/** Effective capabilities supplied by a catalog or explicit model configuration. */
data class ModelCapabilities(
    val images: Boolean,
    val nativeTools: Boolean,
    val textTools: Boolean,
    val promptCaching: Boolean,
    val reasoningDisable: Boolean,
    val reasoningEffort: Boolean,
    val contextWindow: Int?,
    val maxOutputTokens: Int?,
)

/** Contains no credentials, HTTP client, persisted entities or platform types. */
data class ModelDescriptor(
    val id: String,
    val provider: String,
    val api: LlmApi,
    val capabilities: ModelCapabilities,
)
