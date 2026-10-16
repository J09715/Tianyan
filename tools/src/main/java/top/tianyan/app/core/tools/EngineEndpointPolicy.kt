package top.tianyan.app.core.tools

/**
 * 引擎进程端点的归一化策略（纯函数，可在 JVM 单测直接断言）。
 *
 * 对齐 OmniBot 的 shared-provider 端点映射：天衍「模型档案」里的 Base URL 可能是
 * OpenAI 兼容端点（/v1、/v1/chat/completions），但 Claude Code / Kimi Code 这类
 * Anthropic Messages 协议引擎需要的是 Messages 根路径。官方主机的对应关系固定，
 * 可以安全重写；通用代理则绝不臆测（不追加 /anthropic 之类路径）。
 */
object EngineEndpointPolicy {
    /** Anthropic Messages 协议引擎（Claude Code / Kimi Code）使用的根路径。 */
    fun normalizeAnthropic(baseUrl: String): String {
        var normalized = ProviderEndpointPolicy.normalizeUrl(baseUrl).trimEnd('/')
        val host = hostOf(normalized)
        // OpenAI 端点后缀不可能是 Anthropic 根，先去掉。
        for (suffix in listOf("/v1/chat/completions", "/chat/completions", "/v1/responses", "/responses")) {
            if (normalized.endsWith(suffix, ignoreCase = true)) {
                normalized = normalized.dropLast(suffix.length).trimEnd('/')
                break
            }
        }
        if (normalized.endsWith("/apps/anthropic", ignoreCase = true)) return normalized

        // 官方主机固定映射到 Anthropic Messages 兼容路径。
        if (host == "api.deepseek.com") return anthropicAuthorityRoot(normalized)
        if (host == "api.minimaxi.com" || host == "api.minimax.io") {
            return when {
                normalized.endsWith("/anthropic/v1", ignoreCase = true) -> normalized.dropLast("/v1".length)
                normalized.endsWith("/anthropic", ignoreCase = true) -> normalized
                else -> anthropicAuthorityRoot(normalized)
            }
        }
        val isAlibaba = host == "dashscope.aliyuncs.com" ||
            host.endsWith(".dashscope.aliyuncs.com") ||
            host.endsWith(".maas.aliyuncs.com")
        if (isAlibaba) {
            val openAiPath = listOf("/compatible-mode/v1", "/v1")
                .firstOrNull { normalized.endsWith(it, ignoreCase = true) }
            return if (openAiPath != null) {
                normalized.dropLast(openAiPath.length).trimEnd('/') + "/apps/anthropic"
            } else {
                normalized
            }
        }

        // 通用：去掉 Messages 端点与 /v1 根，引擎自行追加 /v1/messages。
        for (suffix in listOf("/v1/messages", "/messages", "/v1")) {
            if (normalized.endsWith(suffix, ignoreCase = true)) {
                return normalized.dropLast(suffix.length).trimEnd('/')
            }
        }
        return normalized
    }

    /** OpenAI 兼容协议引擎（Codex / OpenCode）使用的根路径。 */
    fun normalizeOpenAi(baseUrl: String): String {
        var normalized = ProviderEndpointPolicy.normalizeUrl(baseUrl).trimEnd('/')
        for (suffix in listOf("/v1/chat/completions", "/chat/completions", "/v1/responses", "/responses")) {
            if (normalized.endsWith(suffix, ignoreCase = true)) {
                normalized = normalized.dropLast(suffix.length).trimEnd('/')
                break
            }
        }
        if (normalized.isEmpty() || normalized.contains('#')) return normalized
        if (normalized.endsWith("/v1", ignoreCase = true) ||
            normalized.endsWith("/compatible-mode/v1", ignoreCase = true)
        ) {
            return normalized
        }
        return "$normalized/v1"
    }

    /**
     * DeepSeek Harness（dsh）走的也是 Anthropic Messages 线协议（llm-deepseek 默认
     * `https://api.deepseek.com/anthropic`），因此与 Claude Code 共用同一套根路径归一化。
     */
    fun normalizeDeepSeek(baseUrl: String): String = normalizeAnthropic(baseUrl)

    /**
     * dsh `llm-pi-ai` 通用 provider 的 wire 协议判定。对齐 OmniBot 的
     * `resolveDeepSeekHarnessApi`：Anthropic Messages 官方端点走 anthropic-messages，
     * DeepSeek 官方主机也走 anthropic-messages（DeepSeek 对外暴露 Messages 兼容端点），
     * 其余一律按 OpenAI Chat Completions 兼容处理。
     */
    fun inferPiAiApi(baseUrl: String): String {
        val host = hostOf(baseUrl)
        return when (host) {
            "api.anthropic.com" -> "anthropic-messages"
            "api.deepseek.com" -> "anthropic-messages"
            else -> "openai-completions"
        }
    }

    private fun anthropicAuthorityRoot(url: String): String {
        val uri = runCatching { java.net.URI(url) }.getOrNull()
        return if (uri != null && !uri.scheme.isNullOrBlank() && !uri.rawAuthority.isNullOrBlank()) {
            "${uri.scheme}://${uri.rawAuthority}/anthropic"
        } else {
            url
        }
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host?.lowercase() }.getOrNull().orEmpty()
}
