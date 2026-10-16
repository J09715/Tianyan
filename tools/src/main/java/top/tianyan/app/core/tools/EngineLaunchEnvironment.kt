package top.tianyan.app.core.tools

/**
 * 引擎进程启动环境的纯函数映射：把「模型档案」的凭据 + 端点 + 模型，
 * 翻译成各 ACP 引擎进程认的环境变量。不含任何 Android / Room / DataStore，
 * 因此可在普通 JVM 单测直接断言。
 *
 * 设计对齐 OmniBot 的 shared-provider 模型：宿主不按引擎 id 去硬猜 provider，
 * 而是把**当前活跃模型档案**（用户在天衍「设置 → 模型」里配置并激活的那份）
 * 统一作为凭据来源，再由本映射按**引擎协议**决定变量名：
 *
 *   - Claude Code / DeepSeek Harness / Kimi Code 走 Anthropic Messages 线协议；
 *   - Codex / OpenCode 走 OpenAI 兼容线协议。
 *
 * 一个活跃档案的 Base URL 可能是 OpenAI 兼容端点，Claude Code 需要的是
 * Messages 根，因此端点按引擎协议做归一化（见 [EngineEndpointPolicy]）。
 */
object EngineLaunchEnvironment {

    /** 从活跃模型档案解析出的凭据三要素。 */
    data class Credentials(
        val apiKey: String,
        val baseUrl: String,
        val model: String,
    )

    /**
     * 构建指定引擎的启动环境。
     *
     * @param engineId 引擎目录（engines.json）里的 id。
     * @param credentials 活跃模型档案凭据；apiKey 为空时返回空表（不注入半个变量）。
     */
    fun build(engineId: String, credentials: Credentials): Map<String, String> {
        val apiKey = credentials.apiKey.trim()
        if (apiKey.isEmpty()) return emptyMap()
        val baseUrl = credentials.baseUrl.trim()
        val model = credentials.model.trim()

        return when (engineId) {
            "claude-code" -> anthropicEnvironment(apiKey, baseUrl, model)
            "deepseek-harness" -> deepSeekEnvironment(apiKey, baseUrl, model)
            "kimi-code" -> kimiEnvironment(apiKey, baseUrl, model)
            "codex" -> openAiEnvironment(apiKey, baseUrl)
            "opencode" -> openAiEnvironment(apiKey, baseUrl)
            else -> emptyMap()
        }
    }

    private fun anthropicEnvironment(apiKey: String, baseUrl: String, model: String): Map<String, String> =
        buildMap {
            put("ANTHROPIC_API_KEY", apiKey)
            put("ANTHROPIC_AUTH_TOKEN", apiKey)
            putAnthropicBase(this, baseUrl)
            if (model.isNotBlank()) {
                put("ANTHROPIC_MODEL", model)
                put("ANTHROPIC_SMALL_FAST_MODEL", model)
            }
        }

    private fun deepSeekEnvironment(apiKey: String, baseUrl: String, model: String): Map<String, String> =
        buildMap {
            put("DEEPSEEK_API_KEY", apiKey)
            if (baseUrl.isNotBlank() && ProviderEndpointPolicy.isSafeBaseUrl(baseUrl)) {
                put("DEEPSEEK_BASE_URL", EngineEndpointPolicy.normalizeDeepSeek(baseUrl))
            }
            if (model.isNotBlank()) put("DSH_MODEL", model)
        }

    private fun kimiEnvironment(apiKey: String, baseUrl: String, model: String): Map<String, String> =
        buildMap {
            // Kimi Code 是 Claude Code fork：认 Anthropic 面，也有自己的 KIMI_MODEL_* 面。
            put("ANTHROPIC_API_KEY", apiKey)
            put("ANTHROPIC_AUTH_TOKEN", apiKey)
            put("KIMI_MODEL_API_KEY", apiKey)
            putAnthropicBase(this, baseUrl)
            if (baseUrl.isNotBlank() && ProviderEndpointPolicy.isSafeBaseUrl(baseUrl)) {
                put("KIMI_MODEL_BASE_URL", EngineEndpointPolicy.normalizeAnthropic(baseUrl))
            }
            if (model.isNotBlank()) {
                put("ANTHROPIC_MODEL", model)
                put("ANTHROPIC_SMALL_FAST_MODEL", model)
                put("KIMI_MODEL_NAME", model)
            }
        }

    private fun openAiEnvironment(apiKey: String, baseUrl: String): Map<String, String> =
        buildMap {
            put("OPENAI_API_KEY", apiKey)
            if (baseUrl.isNotBlank() && ProviderEndpointPolicy.isSafeBaseUrl(baseUrl)) {
                put("OPENAI_BASE_URL", EngineEndpointPolicy.normalizeOpenAi(baseUrl))
            }
        }

    private fun putAnthropicBase(target: MutableMap<String, String>, baseUrl: String) {
        if (baseUrl.isNotBlank() && ProviderEndpointPolicy.isSafeBaseUrl(baseUrl)) {
            target["ANTHROPIC_BASE_URL"] = EngineEndpointPolicy.normalizeAnthropic(baseUrl)
        }
    }
}
