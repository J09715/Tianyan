package top.tianyan.app.harness.skill

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.database.AgentSkillRepository
import top.tianyan.app.core.database.HarnessSessionRepository
import top.tianyan.app.core.datastore.AgentPreferences
import top.tianyan.app.core.model.AgentSkill
import top.tianyan.app.harness.ModelConfig
import top.tianyan.app.harness.ToolCallMode
import top.tianyan.app.harness.ApiMessage
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.ProviderClient
import top.tianyan.app.harness.SkillSuggestion
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.harness.UserMessage
import top.tianyan.app.harness.projection.SessionMessageProjector
import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * 技能进化顾问（交互形态借鉴千问/QwenWork 的「对话结束后建议沉淀或修复技能」）。
 *
 * 一轮有效工作（RunResult.Completed 且包含足够工具调用）结束后，用当前会话模型
 * 对刚结束的对话做一次轻量分析：
 * - 工作流可复用且现有技能覆盖不了 → 建议「创建新技能」；
 * - 会话暴露了某个既有技能指导规则的缺陷 → 建议「更新该技能」；
 * - 普通问答 / 一次性任务 → 不打扰（action=none）。
 *
 * 产出以 [SkillSuggestion] 消息进入会话转写，UI 展示可操作卡片；
 * 分析失败、开关关闭、冷却期内一律静默跳过，绝不影响主对话。
 */
class SkillEvolutionAdvisor(
    private val providerClient: ProviderClient,
    private val skillRepository: AgentSkillRepository,
    private val settingsDataStore: AgentPreferences,
    private val sessionDao: HarnessSessionRepository,
    private val projector: SessionMessageProjector,
    private val logger: AppLogger,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastSuggestionAt = HashMap<String, Long>()

    /** 只在成功 run 结束后由 HarnessLoop 调用；内部全量容错，绝不向调用方抛异常。 */
    fun maybeSuggest(sessId: String) {
        scope.launch {
            try {
                analyzeAndEmit(sessId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                logger.w("SkillEvolution: analyze failed for $sessId", t)
            }
        }
    }

    private suspend fun analyzeAndEmit(sessId: String) {
        if (!settingsDataStore.skillEvolutionSuggestions.first()) return

        // 必须先取快照再判门槛：早退路径不应占用冷却窗口（否则一次空跑会让真正有价值的
        // 下一轮在 30 分钟内被静默吞掉）。
        val messages = projector.messagesFlow(sessId).value
        val digest = buildConversationDigest(messages) ?: return

        // 工具调用门槛：仅当本轮有足够多的工具调用（= 确实做了步骤化工作）才值得花一次
        // LLM 调用做分析。此前 MIN_TOOL_CALLS 是死常量（定义了从不使用），导致寒暄式的
        // 单轮对话也会触发分析，既烧 token 又产出噪声建议。
        if (countRecentToolCalls(messages) < MIN_TOOL_CALLS) return

        val now = System.currentTimeMillis()
        synchronized(lastSuggestionAt) {
            val last = lastSuggestionAt[sessId] ?: 0L
            if (now - last < COOLDOWN_MS) return
            lastSuggestionAt[sessId] = now
            lastSuggestionAt.pruneIfStale(now)
        }

        val skills = skillRepository.allSkills.first()
        val model = resolveModel(sessId) ?: return

        val analysisModel = model.copy(
            pureChatMode = true,
            toolCallMode = ToolCallMode.DISABLED,
            // 建议是一次性短输出：不透传主对话可能配置的极小 maxTokens
            maxTokens = minOf(model.maxTokens ?: DEFAULT_MAX_TOKENS, DEFAULT_MAX_TOKENS),
        )
        val result = providerClient.chat(
            analysisModel,
            listOf(
                ApiMessage(role = "system", content = ADVISOR_SYSTEM_PROMPT),
                ApiMessage(role = "user", content = buildAdvisorUserPrompt(digest, skills)),
            ),
        )
        val proposal = parseAdvisorResponse(result.content.orEmpty()) ?: return
        val normalized = normalizeProposal(proposal, skills) ?: return
        projector.append(sessId, normalized.toMessage(UUID.randomUUID().toString(), System.currentTimeMillis()))
    }

    private suspend fun resolveModel(sessId: String): ModelConfig? =
        runCatching {
            val binding = sessionDao.findById(sessId)
            providerClient.resolveConfigured(binding?.modelId, binding?.modelVariant)
        }.getOrNull()

    private fun SkillSuggestionDbo.toMessage(id: String, createdAt: Long) = SkillSuggestion(
        id = id,
        createdAt = createdAt,
        action = action,
        skillName = name,
        description = description,
        systemPrompt = systemPrompt,
        triggerCommand = trigger?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("/")) it else "/$it" },
        targetSkillId = target_skill_id?.takeIf { action == "update" },
        reason = reason,
    )

    companion object {
        private const val COOLDOWN_MS = 30L * 60L * 1000L
        private const val MIN_TOOL_CALLS = 3
        private const val DEFAULT_MAX_TOKENS = 2000
        private const val MAX_DIGEST_CHARS = 9000

        /** 统计最近一轮（自最后一条用户消息起）的工具调用次数，用于技能进化门槛判定。 */
        internal fun countRecentToolCalls(messages: List<HarnessMessage>): Int {
            val lastUserIndex = messages.indexOfLast { it is UserMessage }
            if (lastUserIndex < 0) return 0
            return messages.drop(lastUserIndex).count { it is ToolCall }
        }

        /**
         * 清理冷却表的过期条目：lastSuggestionAt 以 sessId 为键且只在单例内增长，
         * 长跑设备上会话数无上限，不清理会缓慢泄漏内存。冷却窗口外的键可直接移除。
         */
        private fun HashMap<String, Long>.pruneIfStale(now: Long) {
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                if (now - iterator.next().value >= COOLDOWN_MS) iterator.remove()
            }
        }

        internal fun buildConversationDigest(messages: List<HarnessMessage>): String? {            val lastUserIndex = messages.indexOfLast { it is UserMessage }
            if (lastUserIndex < 0) return null
            val sb = StringBuilder()
            messages.drop(lastUserIndex).forEach { msg ->
                when (msg) {
                    is UserMessage -> sb.append("[用户请求]\n").append(msg.text.take(2000)).append('\n')
                    is ToolCall -> sb.append("[调用工具] ")
                        .append(msg.tool.name)
                        .append(' ')
                        .append(msg.args.toString().take(200))
                        .append('\n')
                    is ToolResult -> sb.append("[工具结果] success=")
                        .append(msg.success)
                        .append(" 输出摘录：")
                        .append(msg.output.take(300).replace('\n', ' '))
                        .append('\n')
                    is AssistantText -> sb.append("[助手结论]\n").append(msg.text.take(1500)).append('\n')
                    else -> {}
                }
            }
            return sb.toString().takeIf { it.isNotBlank() }?.take(MAX_DIGEST_CHARS)
        }

        internal fun buildAdvisorUserPrompt(digest: String, skills: List<AgentSkill>): String = buildString {
            append("## 刚结束的会话内容\n")
            append(digest)
            append("\n\n## 现有启用技能\n")
            if (skills.isEmpty()) {
                append("（无）\n")
            } else {
                skills.forEach { skill ->
                    append("- ").append(skill.name).append("（id=").append(skill.id).append("）：")
                        .append(skill.description.take(80)).append('\n')
                }
            }
            append("\n请按系统提示的规则只输出一个 JSON 对象。")
        }

        internal val ADVISOR_SYSTEM_PROMPT = """
            你是 AI Agent 系统的技能进化顾问。用户刚结束一轮 agent 会话，请判断是否值得把本次工作沉淀为可复用技能（create）、修复某个既有技能的指导规则（update），或者不产生建议（none）。

            判据：
            - create：会话包含清晰、步骤化、未来可复用的工作流（使用了多次工具调用且有明确流程），且现有技能清单覆盖不了该领域；
            - update：会话实际执行了某个既有技能（或其领域），且暴露出该技能说明的缺陷（步骤缺失、参数错误、输出格式不符），此时给出修订后的完整 system_prompt 并填写 target_skill_id；
            - none：一次性任务、简单问答、无沉淀价值（这是默认答案，宁缺毋滥）。

            输出要求：只输出一个 JSON 对象，不要任何解释或代码围栏：
            {"action":"none|create|update","name":"技能名（≤20字）","description":"一句话适用场景（≤80字）","trigger":"触发命令（可选，≤20字符，仅小写字母数字连字符）","system_prompt":"完整技能指导规则（第二人称中文，包含目标/执行步骤/明确使用的工具/输出格式/约束，create 时 300-800 字）","target_skill_id":"仅 update 时填写","reason":"给出该建议的一句话依据"}
        """.trimIndent()

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** 宽容解析：剥代码围栏、截取首个 JSON 对象；失败返回 null（静默跳过）。 */
        internal fun parseAdvisorResponse(text: String): SkillSuggestionDbo? {
            var cleaned = text.trim()
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.removePrefix("```json").removePrefix("```")
                cleaned = cleaned.removeSuffix("```").trim()
            }
            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return runCatching {
                json.decodeFromString<SkillSuggestionDbo>(cleaned.substring(start, end + 1))
            }.getOrNull()?.let { proposal ->
                // 内容上限护栏：异常模型输出不至于撑爆转写与上下文
                proposal.copy(
                    name = proposal.name.take(20),
                    description = proposal.description.take(80),
                    trigger = proposal.trigger?.take(20),
                    system_prompt = proposal.systemPrompt.take(8000),
                    reason = proposal.reason.take(200),
                )
            }
        }

        /**
         * 依据现有技能归一化提案：update 目标必须真实存在且非内置（内置技能由
         * 代码定义，upsert 覆盖会在下次 seed 时被抹平）；目标失效时降级为 create，
         * 与既有技能同名的 create 视为重复并放弃（返回 null）。
         */
        internal fun normalizeProposal(
            proposal: SkillSuggestionDbo,
            skills: List<AgentSkill>,
        ): SkillSuggestionDbo? {
            if (proposal.systemPrompt.isBlank() || proposal.name.isBlank()) return null
            return when (proposal.action) {
                "update" -> {
                    val target = skills.firstOrNull { it.id == proposal.target_skill_id }
                    when {
                        target == null -> {
                            val byName = skills.firstOrNull { it.name.equals(proposal.name, ignoreCase = true) }
                            if (byName != null && !byName.isBuiltin) {
                                proposal.copy(target_skill_id = byName.id)
                            } else {
                                proposal.copy(action = "create", target_skill_id = null)
                            }
                        }
                        target.isBuiltin -> proposal.copy(action = "create", target_skill_id = null, name = "${proposal.name}-进化")
                        else -> proposal
                    }
                }
                "create" -> {
                    val duplicated = skills.any { it.name.equals(proposal.name, ignoreCase = true) }
                    if (duplicated) null else proposal
                }
                else -> null
            }
        }
    }
}

/** 顾问 JSON 输出的中间表示（字段名与提示词中的 JSON key 一致）。 */
@Serializable
internal data class SkillSuggestionDbo(
    val action: String = "none",
    val name: String = "",
    val description: String = "",
    val trigger: String? = null,
    val system_prompt: String = "",
    val target_skill_id: String? = null,
    val reason: String = "",
) {
    val systemPrompt: String get() = system_prompt
}
