package top.tianyan.app.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import top.tianyan.app.core.model.CcAgentType
import top.tianyan.app.core.model.CcProviderProfile
import top.tianyan.app.core.model.CcSwitchDaemonStatus
import top.tianyan.app.core.model.CcTokenUsage
import java.io.IOException
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * cc-switch-web 服务端暴露的 Provider（`/api/providers/{app}` 的 value）。
 * 字段与上游 `Provider` 结构对齐，密钥/地址等全部收敛在 `settingsConfig` 内。
 */
@Serializable
internal data class CcServerProvider(
    val id: String = "",
    val name: String = "",
    val settingsConfig: JsonObject = JsonObject(emptyMap()),
    val category: String? = null,
    val notes: String? = null,
)

@Serializable
internal data class CcProxyStatus(
    val running: Boolean = false,
    val port: Int = 19870,
    val uptimeSeconds: Long = 0,
    val totalRequests: Long = 0,
    val successRequests: Long = 0,
    val failedRequests: Long = 0,
    val activeConnections: Long = 0,
    val lastError: String? = null,
)

@Serializable
internal data class CcUsageSummary(
    val totalRequests: Long = 0,
    val totalInputTokens: Long = 0,
    val totalOutputTokens: Long = 0,
    val totalCacheCreationTokens: Long = 0,
    val totalCacheReadTokens: Long = 0,
    val realTotalTokens: Long = 0,
    val totalCost: String = "0",
)

/** 某次刷新时，各 Agent 的 Provider 纳管快照。 */
data class CcProviderSnapshot(
    val providers: List<CcProviderProfile> = emptyList(),
    /** app 标识 -> 当前生效的 Provider id。 */
    val currentProviderByApp: Map<String, String> = emptyMap(),
)

/**
 * cc-switch-web headless 服务端客户端。
 *
 * 服务端整棵 `/api` 路由都套了 HTTP Basic 鉴权，且所有非 GET 请求还要求 `x-csrf-token` 头，
 * 因此本客户端的每个调用都必须由调用方（ViewModel）传入从沙箱 `~/.cc-switch` 读取的凭据。
 */
class CcSwitchClient(
    httpClientProvider: HttpClientProvider,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    private val client: OkHttpClient = httpClientProvider.create().newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** `/api/proxy/status` + 当日 `/api/usage/summary` 组合为界面所需的守护进程状态。 */
    suspend fun getDaemonStatus(
        username: String,
        password: String,
        port: Int = 19870,
    ): Result<CcSwitchDaemonStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val proxy: CcProxyStatus = getJson("/api/proxy/status", username, password, port, CcProxyStatus.serializer())
            val now = System.currentTimeMillis()
            val usagePath = "/api/usage/summary?startDate=${startOfTodayMillis()}&endDate=$now"
            val usage: CcUsageSummary = getJson(usagePath, username, password, port, CcUsageSummary.serializer())
            CcSwitchDaemonStatus(
                running = proxy.running,
                port = if (proxy.port != 0) proxy.port else port,
                proxyEnabled = proxy.running,
                uptimeSeconds = proxy.uptimeSeconds,
                todayTokens = usage.toTokenUsage(),
            )
        }
    }

    /** 逐个 app 拉取 `/api/providers/{app}` 与 `/api/providers/{app}/current`，汇总为统一快照。 */
    suspend fun getProviderSnapshot(
        username: String,
        password: String,
        port: Int = 19870,
    ): Result<CcProviderSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            val collected = mutableListOf<CcProviderProfile>()
            val currentByApp = mutableMapOf<String, String>()
            val mapSerializer = MapSerializer(String.serializer(), CcServerProvider.serializer())
            CcAgentType.serverManaged.forEach { type ->
                val appId = requireNotNull(type.serverAppId)
                val providers = getJson("/api/providers/$appId", username, password, port, mapSerializer)
                providers.values.forEach { server ->
                    collected += server.toProfile(appId)
                }
                val currentId = decodeString(getRaw("/api/providers/$appId/current", username, password, port))
                if (!currentId.isNullOrBlank() && providers.containsKey(currentId)) {
                    currentByApp[appId] = currentId
                }
            }
            CcProviderSnapshot(
                providers = collected.distinctBy { it.id },
                currentProviderByApp = currentByApp,
            )
        }
    }

    /** `POST /api/providers/{app}/{id}/switch`：把某个 app 的当前模型源切到指定 Provider。 */
    suspend fun switchProvider(
        appId: String,
        providerId: String,
        username: String,
        password: String,
        csrfToken: String,
        port: Int = 19870,
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val url = HttpUrl.Builder()
                .scheme("http")
                .host("127.0.0.1")
                .port(port)
                .addPathSegment("api")
                .addPathSegment("providers")
                .addPathSegment(appId)
                .addPathSegment(providerId)
                .addPathSegment("switch")
                .build()
            val request = Request.Builder()
                .url(url)
                .post("{}".toRequestBody(jsonMediaType))
                .header("Authorization", Credentials.basic(username, password))
                .header("x-csrf-token", csrfToken)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code}: ${response.message}")
                }
                true
            }
        }
    }

    /** `POST /api/providers/{app}`：新增/覆盖一个 Provider，写入体须符合服务端按 app 的结构校验。 */
    suspend fun saveProvider(
        appId: String,
        profile: CcProviderProfile,
        username: String,
        password: String,
        csrfToken: String,
        port: Int = 19870,
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = buildJsonObject {
                put("id", profile.id)
                put("name", profile.name)
                put("settingsConfig", buildSettingsConfig(appId, profile))
            }.toString()
            val url = HttpUrl.Builder()
                .scheme("http")
                .host("127.0.0.1")
                .port(port)
                .addPathSegment("api")
                .addPathSegment("providers")
                .addPathSegment(appId)
                .build()
            val request = Request.Builder()
                .url(url)
                .post(payload.toRequestBody(jsonMediaType))
                .header("Authorization", Credentials.basic(username, password))
                .header("x-csrf-token", csrfToken)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code}: ${response.message}")
                }
                true
            }
        }
    }

    private fun <T> getJson(
        path: String,
        username: String,
        password: String,
        port: Int,
        deserializer: KSerializer<T>,
    ): T = json.decodeFromString(deserializer, getRaw(path, username, password, port))

    private fun decodeString(body: String): String? =
        runCatching { json.decodeFromString(String.serializer(), body) }.getOrNull()

    private fun getRaw(path: String, username: String, password: String, port: Int): String {
        val request = Request.Builder()
            .url("http://127.0.0.1:$port$path")
            .header("Authorization", Credentials.basic(username, password))
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${response.message}")
            }
            return response.body.string()
        }
    }

    private fun CcUsageSummary.toTokenUsage(): CcTokenUsage = CcTokenUsage(
        promptTokens = totalInputTokens,
        completionTokens = totalOutputTokens,
        totalTokens = if (realTotalTokens > 0) realTotalTokens else totalInputTokens + totalOutputTokens,
        requestCount = totalRequests,
    )

    private fun CcServerProvider.toProfile(appId: String): CcProviderProfile = CcProviderProfile(
        id = id.ifBlank { name },
        name = name,
        protocol = if (appId.startsWith("claude")) "ANTHROPIC" else "OPENAI",
        selectedModel = findModelHint(settingsConfig).orEmpty(),
        isCustom = true,
    )

    /** 上游把模型名放在 app 各自的 settingsConfig 里，这里递归取第一个含 "model" 的字符串值。 */
    private fun findModelHint(element: JsonElement?): String? {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                if (key.contains("model", ignoreCase = true) && value is JsonPrimitive && value.isString) {
                    return value.content
                }
                findModelHint(value)?.let { return it }
            }
            is JsonArray -> element.forEach { findModelHint(it)?.let { found -> return found } }
            else -> Unit
        }
        return null
    }

    /** 按服务端各个 app 的 settingsConfig 形状构造写入体（上游把密钥/地址收敛在 settingsConfig 内）。 */
    private fun buildSettingsConfig(appId: String, profile: CcProviderProfile): JsonObject {
        val model = profile.selectedModel
        return when (appId) {
            "claude", "claude-desktop" -> buildJsonObject {
                putJsonObject("env") {
                    put("ANTHROPIC_BASE_URL", profile.baseUrl)
                    put("ANTHROPIC_AUTH_TOKEN", profile.apiKey)
                    if (model.isNotBlank()) put("ANTHROPIC_MODEL", model)
                }
            }
            "gemini" -> buildJsonObject {
                putJsonObject("env") {
                    put("GOOGLE_GEMINI_BASE_URL", profile.baseUrl)
                    put("GEMINI_API_KEY", profile.apiKey)
                    if (model.isNotBlank()) put("GEMINI_MODEL", model)
                }
            }
            "opencode" -> buildJsonObject {
                put("npm", "@ai-sdk/openai-compatible")
                put("name", profile.name)
                putJsonObject("options") {
                    put("baseURL", profile.baseUrl)
                    put("apiKey", profile.apiKey)
                }
            }
            "codex" -> buildJsonObject {
                putJsonObject("auth") { put("OPENAI_API_KEY", profile.apiKey) }
                put("config", codexToml(profile))
            }
            "openclaw" -> buildJsonObject {
                put("baseUrl", profile.baseUrl)
                put("apiKey", profile.apiKey)
                put("api", "openai-completions")
                putJsonArray("models") {
                    if (model.isNotBlank()) addJsonObject {
                        put("id", model)
                        put("name", model)
                    }
                }
            }
            else -> buildJsonObject {
                put("baseUrl", profile.baseUrl)
                put("apiKey", profile.apiKey)
                if (model.isNotBlank()) put("model", model)
            }
        }
    }

    /** Codex 的地址/模型收敛在 TOML config 字符串里。 */
    private fun codexToml(profile: CcProviderProfile): String = buildString {
        appendLine("model_provider = \"tianyan\"")
        if (profile.selectedModel.isNotBlank()) appendLine("model = \"${profile.selectedModel}\"")
        appendLine()
        appendLine("[model_providers.tianyan]")
        appendLine("name = \"tianyan\"")
        appendLine("base_url = \"${profile.baseUrl}\"")
        appendLine("env_key = \"OPENAI_API_KEY\"")
        appendLine("wire_api = \"responses\"")
    }

    private fun startOfTodayMillis(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
