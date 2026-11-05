package top.tianyan.app.core.tools

import java.net.URI
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import top.tianyan.app.core.common.net.PrivateNetworkHosts

open class ProviderManager {
    private val providerRepository: ProviderRepository?

    constructor(providerRepository: ProviderRepository) {
        this.providerRepository = providerRepository
    }

    constructor() {
        this.providerRepository = null
    }

    open suspend fun environment(): Map<String, String> {
        val repo = providerRepository ?: return emptyMap()
        val provider = repo.provider.first().trim().lowercase()
        val apiKey = repo.readApiKey()
        val baseUrl = ProviderEndpointPolicy.normalizeUrl(repo.baseUrl.first())
        val model = repo.model.first().trim()
        val environment = linkedMapOf<String, String>()
        val variable = when (provider) {
            "openai" -> "OPENAI_API_KEY"
            "anthropic", "claude" -> "ANTHROPIC_API_KEY"
            "google", "gemini" -> "GEMINI_API_KEY"
            "deepseek" -> "DEEPSEEK_API_KEY"
            "openrouter" -> "OPENROUTER_API_KEY"
            else -> null
        }
        if (!apiKey.isNullOrBlank() && variable != null) environment[variable] = apiKey
        if (baseUrl.isNotBlank() && ProviderEndpointPolicy.isSafeBaseUrl(baseUrl)) {
            environment["LINUXAI_BASE_URL"] = baseUrl
            providerEnvironmentName(provider, "BASE_URL")?.let { environment[it] = baseUrl }
        }
        if (model.isNotBlank()) {
            environment["LINUXAI_MODEL"] = model
            providerEnvironmentName(provider, "MODEL")?.let { environment[it] = model }
        }
        return environment
    }

    private fun providerEnvironmentName(provider: String, suffix: String): String? = when (provider) {
        "openai" -> "OPENAI_$suffix"
        "anthropic", "claude" -> "ANTHROPIC_$suffix"
        "google", "gemini" -> "GEMINI_$suffix"
        "deepseek" -> "DEEPSEEK_$suffix"
        "openrouter" -> "OPENROUTER_$suffix"
        else -> null
    }
}

object ProviderEndpointPolicy {
    private val DASH_REGEX = Regex("[\u2010\u2011\u2012\u2013\u2014\u2015\u2212\uFE58\uFE63\uFF0D\u00AD]")
    private val ZERO_WIDTH_REGEX = Regex("[\u200B\u200C\u200D\uFEFF]")

    /**
     * 对输入的 URL 进行全角/Unicode 符号、空格与协议头标准化清洗：
     * 1. 去除零宽字符与不换行空格；
     * 2. 将各种 Unicode 连字符/破折号（如 U+2011、U+2013、U+FF0D 等）规范为 ASCII '-'；
     * 3. 将全角冒号、斜杠、句点转为标准 ASCII 字符；
     * 4. 若未显式携带 http:// 或 https://，根据主机特征自动补齐（私网/本地默认 http://，外网域名默认 https://）。
     */
    fun normalizeUrl(raw: String): String {
        var clean = raw.trim()
            .replace(ZERO_WIDTH_REGEX, "")
            .replace("\u00A0", " ")
            .replace(DASH_REGEX, "-")
            .replace('\uFF1A', ':')
            .replace('\uFF0F', '/')
            .replace('\u3002', '.')
            .replace('\uFF0E', '.')
        val hasExplicitScheme = clean.contains("://") || clean.startsWith("javascript:", ignoreCase = true) || clean.startsWith("data:", ignoreCase = true) || clean.startsWith("about:", ignoreCase = true) || clean.startsWith("file:", ignoreCase = true)
        if (clean.isNotBlank() && !hasExplicitScheme) {
            val hostPart = clean.substringBefore('/').substringBefore(':').lowercase()
            clean = if (isLocalOrPrivateHost(hostPart)) "http://$clean" else "https://$clean"
        }
        return clean
    }

    fun isSafeBaseUrl(value: String): Boolean {
        val normalized = normalizeUrl(value)
        if (normalized.isBlank()) return false

        val httpUrl = normalized.toHttpUrlOrNull()
        if (httpUrl != null) {
            if (httpUrl.username.isNotEmpty() || httpUrl.password.isNotEmpty()) return false
            val host = httpUrl.host.lowercase()
            if (host.isBlank()) return false
            return httpUrl.scheme.equals("https", ignoreCase = true) ||
                httpUrl.scheme.equals("http", ignoreCase = true)
        }

        val uri = runCatching { URI(normalized) }.getOrNull() ?: return false
        if (uri.userInfo != null || uri.host.isNullOrBlank()) return false
        return uri.scheme.equals("https", ignoreCase = true) ||
            uri.scheme.equals("http", ignoreCase = true)
    }

    /**
     * 是否为允许明文 HTTP 的本地/私有网段主机。
     *
     * 与 URL 归一化、端点校验、network-security-config 共用同一份判定，
     * 避免出现「补了 http:// 又被别处按 https 拒掉」的分裂行为。
     */
    fun isPrivateOrLoopbackHost(host: String): Boolean = PrivateNetworkHosts.isPrivateOrLoopback(host)

    private fun isLocalOrPrivateHost(host: String): Boolean = isPrivateOrLoopbackHost(host)
}
