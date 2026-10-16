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

    @Inject
    constructor(
        providerRepository: ProviderRepository,
        aiModelRepository: AiModelRepository,
    ) {
        this.providerRepository = providerRepository
        this.aiModelRepository = aiModelRepository
    }

    constructor() {
        this.providerRepository = null
        this.aiModelRepository = null
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
     * 引擎进程启动环境：统一用**当前活跃模型档案**（用户在天衍「设置 → 模型」里
     * 配置并激活的那份）作为凭据来源，按引擎协议翻译成对应环境变量。
     *
     * 为什么不用「按引擎 id 反查 provider 名再匹配档案」的方案：天衍的模型档案是唯一
     * 事实来源，用户常把一个 DeepSeek / 自定义中转档案同时用于 Claude Code、Codex、DSH
     * 多个引擎；按引擎 id 反查 provider 名去匹配档案，一旦档案 provider 是显示名
     * （"Anthropic Claude"）或自定义项就匹配落空，最终连 key 都没注入——这正是上游
     * v0.18.32 修完仍报 `Authentication required` / `no API key` 的原因。
     *
     * 活跃档案拿不到 key 时，回落到全局 provider key（老式「当前 provider」配置），
     * 与 [ProviderClient] 的 [top.tianyan.app.harness.ModelConfig] 解析保持一致。
     */
    suspend fun engineLaunchEnvironment(engineId: String): Map<String, String> {
        val repo = providerRepository ?: return emptyMap()
        val credentials = activeModelCredentials(repo) ?: fallbackCredentials(repo) ?: return emptyMap()
        return EngineLaunchEnvironment.build(engineId, credentials)
    }

    /** 活跃模型档案 → 凭据三要素；无活跃档案或 key 为空时返回 null。 */
    private suspend fun activeModelCredentials(
        repo: ProviderRepository,
    ): EngineLaunchEnvironment.Credentials? {
        val dao = aiModelRepository ?: return null
        val active = dao.activeModel() ?: return null
        val keys = repo.readModelApiKeys(active.secretRef)
        val apiKey = keys.firstOrNull() ?: repo.readApiKey().orEmpty()
        if (apiKey.isBlank()) return null
        return EngineLaunchEnvironment.Credentials(
            apiKey = apiKey,
            baseUrl = active.baseUrl.ifBlank { repo.baseUrl.first() },
            model = active.model.split(',').firstOrNull()?.trim().orEmpty(),
        )
    }

    /** 全局「当前 provider」兜底（未建立模型档案时的老配置路径）。 */
    private suspend fun fallbackCredentials(
        repo: ProviderRepository,
    ): EngineLaunchEnvironment.Credentials? {
        val apiKey = repo.readApiKey().orEmpty()
        if (apiKey.isBlank()) return null
        return EngineLaunchEnvironment.Credentials(
            apiKey = apiKey,
            baseUrl = repo.baseUrl.first(),
            model = repo.model.first(),
        )
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
