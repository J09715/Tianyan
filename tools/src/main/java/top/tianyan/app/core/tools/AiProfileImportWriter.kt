package top.tianyan.app.core.tools

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import top.tianyan.app.core.database.AiModelEntity
import top.tianyan.app.core.database.AiModelRepository
import top.tianyan.app.core.model.AiModelProfileExport
import top.tianyan.app.core.model.AiProfileImportMode
import java.util.UUID

/** Stage credentials under fresh references, then atomically commit the complete profile batch. */
internal suspend fun importModelProfiles(
    repository: AiModelRepository,
    credentials: ModelCredentialStore,
    profiles: List<AiModelProfileExport>,
    mode: AiProfileImportMode,
): Int {
    AiProfileTransferFormat.validate(profiles)
    val existing = repository.observeAll().first()
    val byId = existing.associateBy { it.id }
    val stagedRefs = mutableListOf<String>()
    val replacedRefs = mutableSetOf<String>()
    val imported = mutableListOf<AiModelEntity>()
    var committed = false
    try {
        for ((index, profile) in profiles.withIndex()) {
            val id = if (mode == AiProfileImportMode.COPY) UUID.randomUUID().toString()
                else profile.id?.trim()?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString()
            val old = byId[id]
            val keys = (profile.apiKeys + listOfNotNull(profile.apiKey))
                .flatMap { it.lines() }.map(String::trim).filter(String::isNotBlank).distinct()
                .ifEmpty { old?.secretRef?.takeIf(String::isNotBlank)?.let { credentials.readModelApiKeys(it) }.orEmpty() }
            check(old == null || old.apiKeyCount == 0 || keys.isNotEmpty()) { "现有模型凭据无法读取，请重新配置后再导入" }
            val secretRef = "model_import_${UUID.randomUUID().toString().replace("-", "")}"
            // Register before writing: a failed store operation may have written its reference already.
            stagedRefs.add(secretRef)
            credentials.setModelApiKeys(secretRef, keys)
            old?.secretRef?.takeIf(String::isNotBlank)?.let(replacedRefs::add)
            val provider = profile.provider.trim().ifBlank { "Custom" }
            val name = profile.name.trim().ifBlank { profile.model.split(',').first().trim().ifBlank { provider } }
            imported += AiModelEntity(
                id = id, name = name, provider = provider, model = profile.model.trim().ifBlank { name },
                baseUrl = profile.baseUrl.trim(), secretRef = secretRef,
                isActive = old?.isActive == true || (existing.none { it.isActive } && index == 0),
                createdAt = old?.createdAt ?: System.currentTimeMillis(),
                temperature = profile.temperature, maxTokens = profile.maxTokens, topP = profile.topP,
                reasoningMode = profile.reasoningMode, reasoningEffort = profile.reasoningEffort,
                toolCallMode = profile.toolCallMode, contextTokens = profile.contextTokens,
                compactionKeepRecentTokens = profile.compactionKeepRecentTokens
                    ?: old?.compactionKeepRecentTokens?.takeIf { profile.schemaVersion == 1 },
                compactionReserveTokens = profile.compactionReserveTokens
                    ?: old?.compactionReserveTokens?.takeIf { profile.schemaVersion == 1 },
                customHeaders = if (profile.credentialsIncluded == false && old != null) old.customHeaders else profile.customHeaders.trim(),
                pureChatMode = profile.pureChatMode, visionEnabled = profile.visionEnabled,
                imageGenerationEnabled = profile.imageGenerationEnabled, responseApiEnabled = profile.responseApiEnabled,
                promptCachingEnabled = profile.promptCachingEnabled, promptCacheTtl1h = profile.promptCacheTtl1h,
                apiKeyCount = keys.size, requestsPerMinutePerKey = profile.requestsPerMinutePerKey,
            )
        }
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            repository.importBatch(existing, imported)
            committed = true
        }
    } catch (failure: Exception) {
        if (!committed) withContext(NonCancellable) { stagedRefs.forEach { runCatching { credentials.removeModelApiKey(it) } } }
        throw failure
    }
    // Never delete a reference still shared by another profile. Cleanup cannot undo a committed import.
    withContext(NonCancellable) {
        val retained = runCatching { repository.observeAll().first().mapTo(mutableSetOf()) { it.secretRef } }.getOrNull()
        if (retained != null) (replacedRefs - retained).forEach { runCatching { credentials.removeModelApiKey(it) } }
    }
    return imported.size
}
