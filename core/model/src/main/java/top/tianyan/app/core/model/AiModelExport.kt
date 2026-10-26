package top.tianyan.app.core.model

import kotlinx.serialization.Serializable

/**
 * 模型档案单项配置导出/导入数据结构
 */
@Serializable
data class AiModelProfileExport(
    val schemaVersion: Int = 1,
    /** null = legacy export; false = credentials and custom headers intentionally omitted. */
    val credentialsIncluded: Boolean? = null,
    val id: String? = null,
    val name: String = "",
    val provider: String = "",
    val model: String = "",
    val baseUrl: String = "",
    val apiKey: String? = null,
    val apiKeys: List<String> = emptyList(),
    val requestsPerMinutePerKey: Int = 0,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val reasoningMode: String? = null,
    val reasoningEffort: String? = null,
    val toolCallMode: String? = null,
    val contextTokens: Int? = null,
    val compactionKeepRecentTokens: Int? = null,
    val compactionReserveTokens: Int? = null,
    val customHeaders: String = "",
    val pureChatMode: Boolean = false,
    val visionEnabled: Boolean = true,
    val imageGenerationEnabled: Boolean = false,
    val responseApiEnabled: Boolean = false,
    val promptCachingEnabled: Boolean = true,
    val promptCacheTtl1h: Boolean = false,
)

enum class AiProfileImportMode { COPY, UPDATE_MATCHING_IDS }

/**
 * 模型档案批量导出/导入数据包容器
 */
@Serializable
data class AiModelProfileBundle(
    val schemaVersion: Int = 1,
    val exportedAt: Long = 0L,
    val source: String = "Tianyan",
    val profiles: List<AiModelProfileExport> = emptyList(),
)
