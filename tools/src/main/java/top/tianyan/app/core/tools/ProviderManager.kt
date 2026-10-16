package top.tianyan.app.core.tools

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import top.tianyan.app.core.database.AiModelRepository

@Singleton
open class ProviderManager {
    private val providerRepository: ProviderRepository?
    private val aiModelRepository: AiModelRepository?
    private val providerCatalog: AgentProviderCatalog?

    @Inject
    constructor(
        providerRepository: ProviderRepository,
        aiModelRepository: AiModelRepository,
        providerCatalog: AgentProviderCatalog,
    ) {
        this.providerRepository = providerRepository
        this.aiModelRepository = aiModelRepository
        this.providerCatalog = providerCatalog
    }

    constructor() {
        this.providerRepository = null
        this.aiModelRepository = null
        this.providerCatalog = null
    }

    open suspend fun environment(): Map<String, String> {
        val repo = providerRepository ?: return emptyMap()
        val provider = repo.provider.first().trim().lowercase()
        val apiKey = repo.readApiKey()
        val baseUrl = ProviderEndpointPolicy.normalizeUrl(repo.baseUrl.first())
        val model = repo.model.first().trim()
        val environment = linkedMapOf<String, String>()
        val variable = keyVariableFor(provider)
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

    /**
     * 按引擎声明的 provider 注入凭据，与 [environment] 的区别是：不看「当前选中 provider」，
     * 而是按 credentialProvider（agent_providers.json 里的 id）定位对应模型档案的 key，
     * 让 DeepSeek Harness、Kimi Code 等引擎各自拿各自的 key，互不覆盖。
     * credentialProvider 为空（如 OpenCode 多模型引擎）时回落到 [environment]。
     */
    suspend fun environmentFor(credentialProvider: String): Map<String, String> {
        val repo = providerRepository ?: return emptyMap()
        val providerId = credentialProvider.trim()
        if (providerId.isBlank()) return environment()

        val apiKey = readProviderApiKey(providerId) ?: repo.readApiKey()
        val variable = keyVariableFor(providerId.lowercase())
        val result = linkedMapOf<String, String>()
        if (!apiKey.isNullOrBlank() && variable != null) result[variable] = apiKey
        return result
    }

    /** 从 provider 匹配的模型档案读 key（档案 provider 字段存 name 或 id 均可命中）。 */
    private suspend fun readProviderApiKey(providerId: String): String? {
        val repo = providerRepository ?: return null
        val dao = aiModelRepository ?: return null
        val catalog = providerCatalog ?: return null
        val providerName = catalog.providers.firstOrNull { it.id == providerId }?.name ?: return null
        val profile = dao.observeAll().first().firstOrNull { entity ->
            entity.provider.equals(providerName, ignoreCase = true) ||
                entity.provider.equals(providerId, ignoreCase = true)
        } ?: return null
        val secretRef = profile.secretRef.takeIf { it.isNotBlank() } ?: return null
        return repo.readModelApiKey(secretRef)?.takeIf { it.isNotBlank() }
    }

    /** provider id → 引擎进程认的环境变量名。Kimi Code 是 Claude Code fork，认 ANTHROPIC_API_KEY。 */
    private fun keyVariableFor(provider: String): String? = when (provider) {
        "openai" -> "OPENAI_API_KEY"
        "anthropic", "claude" -> "ANTHROPIC_API_KEY"
        "google", "gemini" -> "GEMINI_API_KEY"
        "deepseek" -> "DEEPSEEK_API_KEY"
        "moonshot", "kimi" -> "ANTHROPIC_API_KEY"
        "openrouter" -> "OPENROUTER_API_KEY"
        else -> null
    }

    private fun providerEnvironmentName(provider: String, suffix: String): String? = when (provider) {
        "openai" -> "OPENAI_$suffix"
        "anthropic", "claude" -> "ANTHROPIC_$suffix"
        "google", "gemini" -> "GEMINI_$suffix"
        "deepseek" -> "DEEPSEEK_$suffix"
        "moonshot", "kimi" -> "ANTHROPIC_$suffix"
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

        val uri = runCatching { java.net.URI(normalized) }.getOrNull() ?: return false
        if (uri.userInfo != null || uri.host.isNullOrBlank()) return false
        return uri.scheme.equals("https", ignoreCase = true) ||
            uri.scheme.equals("http", ignoreCase = true)
    }

    private fun isLocalOrPrivateHost(host: String): Boolean {
        val h = host.trim().lowercase().removePrefix("[").removeSuffix("]")
        if (h in LOOPBACK_HOSTS) return true
        if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".internal") || h.endsWith(".home.arpa")) return true
        val ipv4Parts = h.split('.').mapNotNull { it.toIntOrNull() }
        if (ipv4Parts.size == 4 && ipv4Parts.all { it in 0..255 }) {
            val (a, b, _, _) = ipv4Parts
            if (a == 127) return true
            if (a == 10) return true
            if (a == 192 && b == 168) return true
            if (a == 172 && b in 16..31) return true
            if (a == 100 && b in 64..127) return true
            if (a == 169 && b == 254) return true
        }
        return false
    }

    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")
}
