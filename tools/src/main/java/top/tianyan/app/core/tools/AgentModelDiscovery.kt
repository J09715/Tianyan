package top.tianyan.app.core.tools

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class AgentModelDiscovery @Inject constructor(
    private val http: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun discover(
        provider: AgentProviderDefinition,
        baseUrl: String,
        apiKey: String?,
    ): List<String> = withContext(Dispatchers.IO) {
        val endpoints = AgentModelEndpoints.forProvider(provider, baseUrl)
        val failures = mutableListOf<String>()
        for (url in endpoints) {
            val result = runCatching { discoverFromEndpoint(url, provider, apiKey) }
            result.onSuccess { ids ->
                return@withContext ids.distinct().sorted()
            }
            result.exceptionOrNull()?.let { failure ->
                failures += (failure.message ?: failure::class.simpleName ?: "unknown error")
            }
        }
        throw ModelDiscoveryResponseException(
            "获取模型失败，已依次尝试以下端点：\n" +
                failures.joinToString("\n") { "  · $it" } +
                "\n请检查 Base URL 协议前缀（http:// 或 https://）、API Key 与设备网络连通性。",
        )
    }

    private fun discoverFromEndpoint(
        endpoint: String,
        provider: AgentProviderDefinition,
        apiKey: String?,
    ): List<String> {
        val targetUrl = ProviderEndpointPolicy.normalizeUrl(endpoint)
        require(targetUrl.isNotBlank() && ProviderEndpointPolicy.isSafeBaseUrl(targetUrl)) {
            "模型发现地址不安全或为空"
        }
        val isAnthropic = provider.protocol == ProviderProtocol.ANTHROPIC ||
            targetUrl.contains("api.anthropic.com") ||
            provider.name.contains("anthropic", ignoreCase = true) ||
            provider.name.contains("claude", ignoreCase = true)
        val isOllama = endpoint.trimEnd('/').endsWith("/api/tags", ignoreCase = true)
        val request = Request.Builder().url(targetUrl)
            .apply {
                when {
                    isAnthropic -> {
                        if (!apiKey.isNullOrBlank()) header("x-api-key", apiKey)
                        header("anthropic-version", "2023-06-01")
                    }
                    else -> if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey")
                }
            }
            .get()
            .build()

        val response = http.newCall(request).execute()
        val code = response.code
        val mediaType = response.body.contentType()
        val body = response.use { it.body.string() }
        if (!response.isSuccessful) {
            throw ModelDiscoveryResponseException(
                "$targetUrl → HTTP $code：${body.take(200)}",
            )
        }
        if (looksLikeHtml(body)) {
            throw ModelDiscoveryResponseException(
                "$targetUrl → 返回了网页而不是 JSON（HTTP $code，Content-Type: ${mediaType ?: "未知"}）",
            )
        }
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }
            .getOrElse { cause ->
                throw ModelDiscoveryResponseException("$targetUrl → 模型接口返回的 JSON 无法解析", cause)
            }
            ?: throw ModelDiscoveryResponseException("$targetUrl → JSON 顶层不是对象")
        val ids = if (isOllama) {
            extractIdsFromOllamaTags(root)
        } else {
            parseOpenAiStyle(root).getOrElse { cause ->
                throw ModelDiscoveryResponseException("$targetUrl → 响应 JSON 结构不符合预期", cause)
            }
        }
        if (ids.isEmpty()) throw ModelDiscoveryResponseException("$targetUrl → 端点未返回可用的 Agent 模型")
        return ids
    }

    private fun parseOpenAiStyle(root: JsonObject): Result<List<String>> {
        val data = root["data"]?.takeIf { it is JsonArray }?.jsonArray
        val models = root["models"]?.takeIf { it is JsonArray }?.jsonArray
        val id = root["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val raw: List<String>? = when {
            data != null -> data.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
            models != null -> models.mapNotNull { item ->
                val obj = item.jsonObject
                obj["name"]?.jsonPrimitive?.content ?: obj["model"]?.jsonPrimitive?.content
            }
            id != null -> listOf(id)
            else -> null
        }
        if (raw == null) return Result.failure(IllegalStateException("无 data / models / id 字段"))
        return Result.success(raw.filter(::isAgentModel))
    }

    private fun extractIdsFromOllamaTags(root: JsonObject): List<String> =
        root["models"]?.jsonArray?.mapNotNull { item ->
            val obj = item.jsonObject
            obj["name"]?.jsonPrimitive?.content ?: obj["model"]?.jsonPrimitive?.content
        }.orEmpty().filter { it.isNotBlank() }

    private fun looksLikeHtml(body: String): Boolean {
        val prefix = body.trimStart().take(32).lowercase()
        return prefix.startsWith("<!doctype html") || prefix.startsWith("<html")
    }

    private fun isAgentModel(id: String): Boolean {
        val value = id.lowercase()
        return MEDIA_OR_NON_CHAT.none { token -> value.contains(token) }
    }

    private companion object {
        val MEDIA_OR_NON_CHAT = listOf(
            "embedding", "embed-", "rerank", "whisper", "tts", "speech", "audio",
            "image", "imagen", "dall-e", "flux", "stable-diffusion", "recraft",
            "video", "veo", "sora", "moderation", "guard", "classifier",
        )
    }
}

class ModelDiscoveryResponseException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
