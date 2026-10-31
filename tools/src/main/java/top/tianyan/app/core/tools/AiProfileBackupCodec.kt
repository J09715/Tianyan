package top.tianyan.app.core.tools

import kotlinx.coroutines.flow.first
import top.tianyan.app.core.database.AiModelRepository
import top.tianyan.app.core.model.AiModelProfileBundle
import top.tianyan.app.core.model.AiModelProfileExport
import kotlinx.coroutines.CancellationException
import top.tianyan.app.core.database.AiModelEntity
import top.tianyan.app.core.model.AiProfileImportMode

/**
 * AI 模型档案的导入/导出编解码：实体 ↔ 导出 JSON 的唯一映射实现。
 * Settings 与 Onboarding 共用，保证两处容错规则一致。
 */
class AiProfileBackupCodec(
    private val aiModelDao: AiModelRepository,
    private val providerRepository: ModelCredentialStore,
    private val profileWriter: AiProfileWriter,
) {

    private val json = AiProfileTransferFormat.json

    suspend fun exportAll(includeApiKeys: Boolean): String {
        val models = aiModelDao.observeAll().first()
        val bundle = AiModelProfileBundle(
            schemaVersion = AiProfileTransferFormat.SCHEMA_VERSION,
            exportedAt = System.currentTimeMillis(),
            source = "Tianyan",
            profiles = models.map { entityToExport(it, readKeys(it, includeApiKeys), includeApiKeys) },
        )
        return json.encodeToString(bundle).also { AiProfileTransferFormat.parse(it) }
    }

    suspend fun exportSingle(modelId: String, includeApiKeys: Boolean): String? {
        val entity = aiModelDao.findById(modelId) ?: return null
        return json.encodeToString(entityToExport(entity, readKeys(entity, includeApiKeys), includeApiKeys))
            .also { AiProfileTransferFormat.parse(it) }
    }

    /** Valid legacy bundle/single/array formats are still supported; validate every row before writing. */
    fun parseProfiles(rawJson: String): Result<List<AiModelProfileExport>> =
        runCatching { AiProfileTransferFormat.parse(rawJson) }

    suspend fun importProfiles(rawJson: String, mode: AiProfileImportMode = AiProfileImportMode.COPY): Result<Int> {
        val profiles = parseProfiles(rawJson).getOrElse { return Result.failure(it) }
        return try {
            Result.success(profileWriter.importProfiles(profiles, mode))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    private suspend fun readKeys(entity: AiModelEntity, includeApiKeys: Boolean): List<String> {
        check(!includeApiKeys || entity.apiKeyCount == 0 || entity.secretRef.isNotBlank()) { "模型凭据引用缺失，请重新配置后再导出" }
        return if (includeApiKeys && entity.secretRef.isNotBlank()) {
            providerRepository.readModelApiKeys(entity.secretRef).also {
                check(entity.apiKeyCount == 0 || it.isNotEmpty()) { "模型凭据无法读取，请重新配置后再导出" }
            }
        } else {
            emptyList()
        }
    }

    private fun entityToExport(
        entity: AiModelEntity,
        keys: List<String>,
        includeCredentials: Boolean,
    ) = AiModelProfileExport(
        schemaVersion = AiProfileTransferFormat.SCHEMA_VERSION,
        credentialsIncluded = includeCredentials,
        id = entity.id,
        name = entity.name,
        provider = entity.provider,
        model = entity.model,
        baseUrl = entity.baseUrl,
        apiKeys = keys,
        apiKey = keys.firstOrNull(),
        requestsPerMinutePerKey = entity.requestsPerMinutePerKey,
        temperature = entity.temperature,
        maxTokens = entity.maxTokens,
        topP = entity.topP,
        reasoningMode = entity.reasoningMode,
        reasoningEffort = entity.reasoningEffort,
        toolCallMode = entity.toolCallMode,
        contextTokens = entity.contextTokens,
        compactionKeepRecentTokens = entity.compactionKeepRecentTokens,
        compactionReserveTokens = entity.compactionReserveTokens,
        customHeaders = if (includeCredentials) entity.customHeaders else "",
        pureChatMode = entity.pureChatMode,
        visionEnabled = entity.visionEnabled,
        imageGenerationEnabled = entity.imageGenerationEnabled,
        responseApiEnabled = entity.responseApiEnabled,
        promptCachingEnabled = entity.promptCachingEnabled,
        promptCacheTtl1h = entity.promptCacheTtl1h,
    )
}
