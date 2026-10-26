package top.tianyan.app.core.tools

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import top.tianyan.app.core.model.AiModelProfileBundle
import top.tianyan.app.core.model.AiModelProfileExport
import java.net.URI
import java.io.InputStream
import java.io.ByteArrayOutputStream

/** Versioned, bounded parser shared by settings and onboarding. Errors never echo source credentials. */
object AiProfileTransferFormat {
    const val SCHEMA_VERSION = 2
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_PROFILES = 500
    val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun read(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(bytes.size() + count <= MAX_BYTES) { "配置文件不能超过 2 MiB" }
            bytes.write(buffer, 0, count)
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    fun parse(raw: String): List<AiModelProfileExport> {
        require(raw.isNotBlank()) { "导入内容为空" }
        require(raw.length <= MAX_BYTES && raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "配置文件不能超过 2 MiB" }
        val profiles = try {
            when (val root = json.parseToJsonElement(raw)) {
                is JsonArray -> json.decodeFromJsonElement<List<AiModelProfileExport>>(root)
                is JsonObject -> if ("profiles" in root) {
                    val bundle = json.decodeFromJsonElement<AiModelProfileBundle>(root)
                    requireVersion(bundle.schemaVersion)
                    json.decodeFromJsonElement<List<AiModelProfileExport>>(root.getValue("profiles")).map {
                        requireVersion(it.schemaVersion)
                        require(it.schemaVersion <= bundle.schemaVersion) { "配置包与档案版本不一致" }
                        it.copy(schemaVersion = bundle.schemaVersion)
                    }
                } else listOf(json.decodeFromJsonElement<AiModelProfileExport>(root))
                else -> throw IllegalArgumentException("JSON 必须是模型档案、档案数组或配置包")
            }
        } catch (_: SerializationException) {
            throw IllegalArgumentException("JSON 结构或字段类型无效，请检查配置文件")
        }
        validate(profiles)
        return profiles
    }

    fun validate(profiles: List<AiModelProfileExport>) {
        require(profiles.isNotEmpty()) { "配置包中没有模型档案" }
        require(profiles.size <= MAX_PROFILES) { "一次最多导入 500 个档案" }
        val ids = profiles.mapNotNull { it.id?.trim()?.takeIf(String::isNotEmpty) }
        require(ids.distinct().size == ids.size) { "配置包中存在重复档案 ID" }
        profiles.forEachIndexed { index, profile ->
            requireVersion(profile.schemaVersion)
            val prefix = "第 ${index + 1} 个档案："
            require(profile.model.isNotBlank() || profile.name.isNotBlank()) { prefix + "缺少模型名称或 ID" }
            require(profile.name.length <= 512 && profile.model.length <= 16_384 && profile.customHeaders.length <= 16_384) { prefix + "配置字段过长" }
            require(profile.credentialsIncluded != false || (profile.apiKey.isNullOrBlank() && profile.apiKeys.all(String::isBlank) && profile.customHeaders.isBlank())) {
                prefix + "凭据声明与内容不一致"
            }
            validateEndpoint(profile.baseUrl, prefix)
            require(profile.temperature?.let { it.isFinite() && it in 0f..2f } != false) { prefix + "温度应为 0–2" }
            require(profile.topP?.let { it.isFinite() && it in 0f..1f } != false) { prefix + "topP 应为 0–1" }
            require(profile.requestsPerMinutePerKey >= 0) { prefix + "请求速率不能为负数" }
            require(listOf(profile.maxTokens, profile.contextTokens, profile.compactionKeepRecentTokens, profile.compactionReserveTokens).all { it == null || it > 0 }) {
                prefix + "Token 预算必须为正数"
            }
        }
    }

    private fun requireVersion(version: Int) {
        require(version in 1..SCHEMA_VERSION) { "不支持此配置版本，请使用兼容版本的天衍导出" }
    }

    private fun validateEndpoint(raw: String, prefix: String) {
        if (raw.isBlank()) return // Legacy profiles can use their provider's default endpoint.
        val uri = runCatching { URI(raw.trim()) }.getOrNull()
        val host = uri?.host?.lowercase().orEmpty().removeSurrounding("[", "]")
        val octets = host.split('.')
        val loopback = host == "localhost" || host == "::1" ||
            (octets.size == 4 && octets.first() == "127" && octets.all { it.toIntOrNull() in 0..255 })
        require(uri != null && host.isNotBlank() && (uri.scheme.equals("https", true) || (uri.scheme.equals("http", true) && loopback))) {
            prefix + "端点需使用 HTTPS，本地回环地址可使用 HTTP"
        }
    }
}
