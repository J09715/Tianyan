package top.tianyan.app.harness

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import top.tianyan.app.core.database.AiModelRepository
import top.tianyan.app.core.datastore.AgentPreferences
import top.tianyan.app.core.tools.ProviderRepository
import top.tianyan.app.harness.ProviderClient.Companion.DEFAULT_BASE_URL
import top.tianyan.app.harness.ProviderClient.Companion.DEFAULT_MODEL
import top.tianyan.app.harness.ProviderClient.Companion.inferProtocol
import top.tianyan.app.core.database.AiModelEntity

/** Resolves catalogs, encrypted credentials and user preferences without sending requests. */
internal class ProviderModelResolver(
    private val providerRepository: ProviderRepository,
    private val modelDao: AiModelRepository,
    private val settingsDataStore: AgentPreferences,
) {
    suspend fun resolveModel(): ModelConfig = withContext(Dispatchers.IO) {
        val active = modelDao.activeModel()
        val baseConfig = active?.toModelConfig(providerRepository)
            ?: ModelConfig(
                name = "默认",
                provider = providerRepository.provider.first(),
                model = providerRepository.model.first().ifBlank { DEFAULT_MODEL },
                baseUrl = providerRepository.baseUrl.first().ifBlank { DEFAULT_BASE_URL },
                apiKey = providerRepository.readApiKey(),
            )
        // 主流模型自动适配：provider 的 /models 通常不返回 context window，
        // 这里在最终模型名（含 variant）确定后统一补全；显式 contextTokens 仍优先。
        baseConfig.withResolvedContextWindow().applyGlobalReasoningDepth().withFirstResponseTimeout()
    }

    /**
     * 同 [resolveModel]，但额外做最小配置校验。[modelId] 非空且存在时优先使用该会话绑定档案，
     * [modelVariant] 用于覆盖档案里的默认模型名，实现同一供应商档案下的会话级模型隔离。
     * 档案中的模型列表是设置页保存的选择，不是供应商可用模型的白名单；会话可绑定发现接口返回的其他模型。
     * 未绑定档案时使用当前激活模型；已绑定但被删除的档案明确报错，不静默换供应商。
     */
    suspend fun resolveConfigured(modelId: String? = null, modelVariant: String? = null): ModelConfig = withContext(Dispatchers.IO) {
        val requested = modelId?.takeIf { it.isNotBlank() }?.let { modelDao.findById(it) }
        require(modelId.isNullOrBlank() || requested != null) { "绑定的供应商档案已不存在，请重新选择模型" }
        val concrete = modelVariant?.trim()?.takeIf(String::isNotBlank)?.let { variant ->
            requireNotNull(requested) { "指定具体模型时必须绑定供应商档案，请重新选择模型" }
            requested.model.split(',').map(String::trim).firstOrNull { it.equals(variant, true) } ?: variant
        }
        val active = requested ?: modelDao.activeModel()
        val providerKey = providerRepository.readApiKey().orEmpty()
        if (active == null && providerKey.isBlank()) {
            throw IllegalStateException("未配置模型或 API Key，请先在「设置 → 模型」中添加并激活一个模型")
        }
        val baseConfig = if (active != null) {
            active.toModelConfig(providerRepository)
        } else {
            val provider = providerRepository.provider.first()
            val baseUrl = providerRepository.baseUrl.first().ifBlank { DEFAULT_BASE_URL }
            ModelConfig(
                name = "默认",
                provider = provider,
                model = providerRepository.model.first().ifBlank { DEFAULT_MODEL },
                baseUrl = baseUrl,
                apiKey = providerKey.ifBlank { null },
                protocol = inferProtocol(baseUrl, provider),
            )
        }
        val sessionConfig = if (requested != null && concrete != null) {
            baseConfig.copy(model = concrete)
        } else {
            baseConfig
        }
        // 同 resolveModel：请求路径零 MCP 发现；按最终 variant 自动适配 context window。
        sessionConfig.withResolvedContextWindow().applyGlobalReasoningDepth().withFirstResponseTimeout()
    }

    /**
     * Resolve an explicit agent model selection without silently falling back to the active model.
     * The selection may be a saved profile id/name or one concrete model configured in a profile.
     */
    suspend fun resolveRequestedModel(
        selection: String?,
        inheritedProfileId: String? = null,
        inheritedVariant: String? = null,
    ): ModelConfig = withContext(Dispatchers.IO) {
        val requested = selection?.trim()?.takeIf { it.isNotBlank() && !it.equals("inherit", ignoreCase = true) }
            ?: return@withContext resolveConfigured(inheritedProfileId, inheritedVariant)
        val profiles = modelDao.observeAll().first()
        val target = selectRequestedModelTarget(profiles, requested) ?: throw IllegalArgumentException(
            "未找到模型选择“$requested”。请传入已保存的模型档案 ID/名称，或档案中已配置的具体模型名。",
        )
        resolveConfigured(target.profileId, target.variant)
    }

    /** Resolve a persisted role binding strictly, including its concrete model variant. */
    suspend fun resolveSavedModelProfile(profileId: String, variant: String?): ModelConfig = withContext(Dispatchers.IO) {
        val profile = modelDao.findById(profileId) ?: throw IllegalArgumentException(
            "子智能体绑定的模型档案“$profileId”已不存在，请在子智能体角色设置中重新选择。",
        )
        val requestedVariant = variant?.trim()?.takeIf { it.isNotBlank() }
        val canonicalVariant = requestedVariant?.let { requested ->
            profile.model.split(',').map { it.trim() }.firstOrNull { it.equals(requested, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "模型档案“${profile.name}”中已不存在模型“$requested”，请在子智能体角色设置中重新选择。",
                )
        }
        resolveConfigured(profile.id, canonicalVariant)
    }

    /**
     * 把「全局推理深度」设置应用到未显式配置的模型上。**只对该厂商实际支持的能力生效**：
     * - 先探测厂商能力（能否关闭 / 能否调强度），不支持的选项直接忽略，避免设置"改了却没反应"；
     * - 模型已显式关闭推理 -> 保持不动（用户意图优先）；
     * - 全局 disabled：仅当厂商 [ReasoningCapabilities.supportsDisable] 且模型 AUTO 时关闭推理；
     * - 全局 low/medium/high：仅当厂商 [ReasoningCapabilities.supportsEffort] 时按深度设置强度
     *   （模型 AUTO 则同时开启推理）；厂商不支持强度（如豆包）则保持 AUTO 跟随服务端默认；
     * - 全局 auto 或未知值 -> 不动。
     */
    private suspend fun ModelConfig.applyGlobalReasoningDepth(): ModelConfig {
        if (reasoningMode == ReasoningMode.DISABLED) return this
        val depth = settingsDataStore.defaultReasoningDepth.first()
        val caps = ReasoningAdapter.capabilities(this)
        return when (depth) {
            "disabled" ->
                if (reasoningMode == ReasoningMode.AUTO && caps.supportsDisable) {
                    copy(reasoningMode = ReasoningMode.DISABLED)
                } else {
                    this
                }
            "low", "medium", "high", "extreme", "max" -> {
                if (!caps.supportsEffort) return this // 不支持强度 -> 跟随服务端默认
                val effort = when (depth) {
                    "low" -> ReasoningEffort.LOW
                    "medium" -> ReasoningEffort.MEDIUM
                    "high" -> ReasoningEffort.HIGH
                    else -> ReasoningEffort.MAX
                }
                if (reasoningMode == ReasoningMode.AUTO) {
                    copy(reasoningMode = ReasoningMode.ENABLED, reasoningEffort = effort)
                } else {
                    copy(reasoningEffort = reasoningEffort ?: effort)
                }
            }
            else -> this
        }
    }

    /** 全局「等待模型首个响应」写入本次请求。未配置时保持默认 90 秒。 */
    private suspend fun ModelConfig.withFirstResponseTimeout(): ModelConfig {
        val seconds = settingsDataStore.firstResponseTimeoutSeconds.first()
        return copy(firstResponseTimeoutMs = top.tianyan.app.core.model.FirstResponseTimeoutPolicy.timeoutMs(seconds))
    }

    /**
     * Fallback metadata for mainstream models whose provider /models response does not
     * expose the context window. Explicit profile values are never overwritten.
     */
    private fun ModelConfig.withResolvedContextWindow(): ModelConfig {
        if (contextTokens != null) return this
        val inferred = ModelContextWindows.resolve(model, provider) ?: return this
        return copy(contextTokens = inferred)
    }


    /** Room 实体 → 运行配置：推理参数原样透传，协议按 Base URL / 厂商名自动推断。 */
    private suspend fun AiModelEntity.toModelConfig(
        providerRepository: ProviderRepository,
    ): ModelConfig {
        val baseUrl = this.baseUrl.ifBlank { DEFAULT_BASE_URL }
        val resolvedProtocol = inferProtocol(baseUrl, provider)
        val modelKeys = providerRepository.readModelApiKeys(secretRef)
        val fallbackKey = if (provider.startsWith("plugin.")) null else providerRepository.readApiKey().orEmpty().ifBlank { null }
        val effectiveKeys = modelKeys.ifEmpty { listOfNotNull(fallbackKey) }
        return ModelConfig(
            name = name,
            provider = provider,
            model = model.split(",").firstOrNull()?.trim().takeUnless { it.isNullOrBlank() } ?: model.trim(),
            baseUrl = baseUrl,
            apiKey = effectiveKeys.firstOrNull(),
            apiKeys = effectiveKeys,
            requestsPerMinutePerKey = requestsPerMinutePerKey.coerceAtLeast(0),
            protocol = resolvedProtocol,
            temperature = temperature,
            maxTokens = maxTokens,
            topP = topP,
            reasoningMode = when (reasoningMode?.lowercase()) {
                "disabled" -> ReasoningMode.DISABLED
                "enabled" -> ReasoningMode.ENABLED
                else -> ReasoningMode.AUTO
            },
            reasoningEffort = when (reasoningEffort?.lowercase()) {
                "low" -> ReasoningEffort.LOW
                "medium" -> ReasoningEffort.MEDIUM
                "high" -> ReasoningEffort.HIGH
                "extreme", "max" -> ReasoningEffort.MAX
                else -> null
            },
            toolCallMode = when (toolCallMode?.lowercase()) {
                "json" -> ToolCallMode.JSON_TEXT
                "disabled" -> ToolCallMode.DISABLED
                else -> ToolCallMode.NATIVE
            },
            contextTokens = contextTokens,
            compactionKeepRecentTokens = compactionKeepRecentTokens,
            compactionReserveTokens = compactionReserveTokens,
            customHeaders = customHeaders,
            pureChatMode = pureChatMode,
            visionEnabled = visionEnabled,
            responseApiEnabled = responseApiEnabled,
            promptCachingEnabled = promptCachingEnabled && resolvedProtocol == ApiProtocol.ANTHROPIC,
            promptCacheTtl1h = promptCacheTtl1h,
        )
    }

}

internal data class RequestedModelTarget(
    val profileId: String,
    val variant: String? = null,
)

internal fun selectRequestedModelTarget(
    profiles: List<AiModelEntity>,
    selection: String,
): RequestedModelTarget? {
    val requested = selection.trim()
    if (requested.isBlank()) return null
    profiles.firstOrNull {
        it.id.equals(requested, ignoreCase = true) || it.name.equals(requested, ignoreCase = true)
    }?.let { return RequestedModelTarget(profileId = it.id) }
    profiles.forEach { entity ->
        val canonicalVariant = entity.model.split(',')
            .map { it.trim() }
            .firstOrNull { it.equals(requested, ignoreCase = true) }
        if (canonicalVariant != null) {
            return RequestedModelTarget(profileId = entity.id, variant = canonicalVariant)
        }
    }
    return null
}
