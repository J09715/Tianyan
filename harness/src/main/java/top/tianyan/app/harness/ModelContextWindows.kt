package top.tianyan.app.harness

/**
 * Mainstream model context-window registry.
 *
 * This is intentionally a local, offline fallback: provider `/models` endpoints rarely
 * expose the context window, so harnesses such as Pi/OpenCode/Cline keep a model
 * metadata table and resolve it by model id before applying per-profile overrides.
 *
 * Resolution order used by [ContextWindowPolicy]:
 * 1. explicit model profile `contextTokens`;
 * 2. a known mainstream model id from this registry;
 * 3. a conservative provider default;
 * 4. the global fallback budget.
 */
object ModelContextWindows {
    private data class Rule(
        val label: String,
        val pattern: Regex,
        val tokens: Int,
    )

    /**
     * Order matters: specific families must appear before broad provider fallbacks.
     * Values are rounded to the API context-window values that mainstream harnesses use.
     */
    private val rules = listOf(
        Rule("DeepSeek V4", Regex("^deepseek-v4"), 1_000_000),
        Rule("DeepSeek V3/R1", Regex("^deepseek-(v3|r1|chat|reasoner)"), 128_000),
        Rule("DeepSeek", Regex("^deepseek"), 128_000),
        Rule("GPT-4.1", Regex("^gpt-4\\.1"), 1_047_576),
        Rule("GPT-4o", Regex("^gpt-4o"), 128_000),
        Rule("GPT-5", Regex("^gpt-5"), 400_000),
        Rule("o-series", Regex("^(o1|o3|o4)"), 200_000),
        Rule("gpt-oss", Regex("^gpt-oss"), 131_072),
        Rule("GPT legacy", Regex("^gpt-4"), 128_000),
        Rule("GPT", Regex("^gpt"), 128_000),
        Rule("Claude", Regex("^claude"), 200_000),
        Rule("Gemini", Regex("^gemini"), 1_048_576),
        Rule("GLM-5/4.6", Regex("^glm-(5|4\\.6)"), 200_000),
        Rule("GLM", Regex("^glm"), 128_000),
        Rule("Doubao 32k", Regex("^doubao.*32k"), 32_768),
        Rule("Doubao Seed", Regex("^doubao-seed"), 262_144),
        Rule("Doubao", Regex("^doubao"), 128_000),
        Rule("Qwen3 Coder", Regex("^qwen3-coder"), 1_000_000),
        Rule("Qwen3 Max", Regex("^qwen3.*max"), 1_000_000),
        Rule("Qwen3", Regex("^qwen3"), 262_144),
        Rule("Qwen2.5", Regex("^qwen2\\.5"), 32_768),
        Rule("Qwen", Regex("^qwen"), 131_072),
        Rule("Kimi K3", Regex("^kimi-k3"), 262_144),
        Rule("Kimi K2", Regex("^kimi-k2"), 262_144),
        Rule("Moonshot", Regex("^moonshot"), 128_000),
        Rule("MiniMax M3", Regex("^minimax-m3"), 1_000_000),
        Rule("MiniMax M2", Regex("^minimax-m2"), 204_800),
        Rule("MiniMax", Regex("^minimax"), 204_800),
        Rule("Grok", Regex("^grok"), 256_000),
        Rule("Llama 4", Regex("^llama-4"), 1_000_000),
        Rule("Llama 3", Regex("^llama-3"), 131_072),
        Rule("Mistral Large", Regex("^mistral-large"), 131_072),
        Rule("Mistral Medium", Regex("^mistral-medium"), 131_072),
        Rule("Mistral/Mixtral", Regex("^(mistral|mixtral)"), 32_768),
    )

    /** Resolve a model id to a context window, or null when the model cannot be identified. */
    fun resolve(modelId: String?, providerId: String? = null): Int? {
        val normalized = normalize(modelId)
        if (normalized != null) {
            rules.firstOrNull { it.pattern.containsMatchIn(normalized) }?.let { return it.tokens }
        }
        return providerDefault(providerId)
    }

    fun resolveOrDefault(
        modelId: String?,
        providerId: String? = null,
        defaultTokens: Int = DEFAULT_FALLBACK_TOKENS,
    ): Int = resolve(modelId, providerId) ?: defaultTokens

    /**
     * Normalize OpenRouter/SiliconFlow aliases and multi-model profile fields.
     * `deepseek-ai/DeepSeek-V3.2:free` -> `deepseek-v3.2`
     */
    private fun normalize(raw: String?): String? {
        val cleaned = raw
            ?.substringBefore(',')
            ?.trim()
            ?.lowercase()
            ?.substringAfterLast('/')
            ?.substringBefore(':')
            ?.trim()
        return cleaned?.takeIf { it.isNotBlank() }
    }

    private fun providerDefault(providerId: String?): Int? {
        val provider = providerId?.trim()?.lowercase() ?: return null
        return when {
            provider.contains("anthropic") || provider.contains("claude") -> 200_000
            provider.contains("gemini") || provider.contains("google") -> 1_048_576
            provider.contains("deepseek") -> 128_000
            provider.contains("zhipu") || provider.contains("glm") -> 128_000
            provider.contains("doubao") || provider.contains("volc") -> 128_000
            provider.contains("qwen") || provider.contains("dashscope") || provider.contains("aliyun") -> 128_000
            provider.contains("moonshot") || provider.contains("kimi") -> 128_000
            provider.contains("minimax") -> 204_800
            provider.contains("xai") || provider.contains("grok") -> 256_000
            provider.contains("mistral") -> 32_768
            provider.contains("groq") -> 131_072
            provider.contains("openai") -> 128_000
            provider.contains("openrouter") || provider.contains("together") ||
                provider.contains("nvidia") || provider.contains("siliconflow") ||
                provider.contains("modelscope") -> 128_000
            else -> null
        }
    }

    const val DEFAULT_FALLBACK_TOKENS = 128_000
}