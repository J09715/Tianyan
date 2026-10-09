package top.tianyan.app.harness.skill

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.database.AgentSkillRepository
import top.tianyan.app.core.model.AgentSkill
import top.tianyan.app.harness.ApiMessage
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.ProviderClient
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.UserMessage

/**
 * 待确认的技能候选：LLM 从会话中提炼出、尚未入库的技能草稿。
 * 用户在聊天界面「采纳」后才写入 AgentSkillRepository。
 */
@Serializable
data class PendingSkillCandidate(
    val name: String,
    val description: String,
    /** 面向未来会话的完整技能提示词（智能体可直接照做的指导）。 */
    val systemPrompt: String,
    /** 短命令（如 /deploy）；空串表示无触发命令。 */
    val triggerCommand: String = "",
    /** 产生该候选的会话 ID。 */
    val sessionId: String = "",
    val createdAt: Long = 0L,
)

/** LLM 提炼协议：整体判断 + 候选列表。 */
@Serializable
private data class DistillResponse(
    val worthLearning: Boolean = false,
    val reason: String = "",
    val skills: List<DistillSkillItem> = emptyList(),
)

/** LLM 提炼协议：单个技能候选。 */
@Serializable
private data class DistillSkillItem(
    val name: String = "",
    val description: String = "",
    val systemPrompt: String = "",
    val triggerCommand: String? = null,
)

/** 会话级冷却记录：记录上次提炼时的用户消息数，防止频繁重复提炼。 */
private data class DistillRecord(val lastUserMsgCount: Int)

/**
 * 会话 → 技能自动提炼器。
 *
 * 会话正常完成后（HarnessLoop fire-and-forget 触发），把会话中的用户指令、
 * 智能体回复与工具使用轨迹交给当前激活模型，判断是否存在值得沉淀为可复用
 * 技能的操作模式 / 经验 / 工作流；有则生成候选放入 [pendingCandidates]，
 * 由用户在聊天界面确认采纳后入库，绝不静默写入技能库。
 *
 * 设计约束：
 * - 全流程容错，任何失败只记日志、绝不抛出，也不影响会话主流程；
 * - 会话级冷却（用户消息新增 ≥3 条才再次提炼）避免每轮对话都烧一次 LLM；
 * - 候选与已入库技能按 name 忽略大小写去重，且对 name / systemPrompt 长度做门槛过滤。
 */
@Singleton
class SkillDistillationManager @Inject constructor(
    private val providerClient: ProviderClient,
    private val agentSkillRepository: AgentSkillRepository,
    private val json: Json,
    private val logger: AppLogger,
) {
    /** 各会话待确认的技能候选（key = sessionId）；仅内存暂存，重启即清空。 */
    private val _pendingCandidates = MutableStateFlow<Map<String, List<PendingSkillCandidate>>>(emptyMap())
    val pendingCandidates: StateFlow<Map<String, List<PendingSkillCandidate>>> = _pendingCandidates.asStateFlow()

    /** 会话级冷却记录：sessionId → 上次提炼时的用户消息数。 */
    private val distillRecords = ConcurrentHashMap<String, DistillRecord>()

    /**
     * 从会话消息提炼技能候选。开关判断由调用方（HarnessLoop）负责；
     * 本方法自身做规模与冷却门槛，结果写入 [pendingCandidates]。
     * 任何异常都被吞掉并记 warn 日志，绝不向上抛出。
     */
    suspend fun distill(sessionId: String, messages: List<HarnessMessage>) {
        runCatching {
            val userCount = messages.count { it is UserMessage }
            // 规模门槛：至少 2 条用户消息才值得分析
            if (userCount < MIN_USER_MESSAGES) return
            // 冷却门槛：与上次提炼相比新增用户消息不足 3 条则直接返回
            val lastCount = distillRecords[sessionId]?.lastUserMsgCount
            if (lastCount != null && userCount - lastCount < USER_DELTA_FOR_REDISTILL) return

            val material = buildMaterial(messages)
            if (material.isBlank()) return

            val model = providerClient.resolveModel()
            val apiMessages = listOf(
                ApiMessage(role = "system", content = DISTILL_SYSTEM_PROMPT),
                ApiMessage(role = "user", content = material),
            )
            val result = providerClient.chat(model, apiMessages)
            val content = result.content?.trim().orEmpty()
            if (content.isBlank()) {
                distillRecords[sessionId] = DistillRecord(userCount)
                return
            }

            // 解析容错：剥除可能的 ```json 围栏并截取 JSON 主体后再反序列化
            val response = withContext(Dispatchers.Default) {
                distillJson.decodeFromString<DistillResponse>(extractJsonBody(content))
            }
            // 无论是否值得学习都更新冷却记录，避免同一批消息反复触发
            distillRecords[sessionId] = DistillRecord(userCount)
            if (!response.worthLearning) {
                logger.i("技能提炼：会话 $sessionId 无值得沉淀的模式（${response.reason}）")
                return
            }

            // 与已入库技能 + 当前待确认候选按 name 忽略大小写去重，并做长度门槛过滤
            val existingNames = agentSkillRepository.allSkills.first()
                .map { it.name.lowercase() }
                .toMutableSet()
            val existingPending = _pendingCandidates.value[sessionId].orEmpty()
            existingPending.forEach { existingNames += it.name.lowercase() }
            val candidates = response.skills
                .filter { it.name.isNotBlank() && it.systemPrompt.isNotBlank() }
                .filter { it.name.length <= MAX_NAME_CHARS && it.systemPrompt.length >= MIN_PROMPT_CHARS }
                .filter { it.name.lowercase() !in existingNames }
                .take(MAX_CANDIDATES)
                .map { item ->
                    existingNames += item.name.lowercase()
                    PendingSkillCandidate(
                        name = item.name.trim(),
                        description = item.description.trim(),
                        systemPrompt = item.systemPrompt.trim(),
                        triggerCommand = item.triggerCommand.orEmpty().trim(),
                        sessionId = sessionId,
                        createdAt = System.currentTimeMillis(),
                    )
                }
            if (candidates.isEmpty()) return
            logger.i("技能提炼：会话 $sessionId 产生 ${candidates.size} 个候选（${candidates.joinToString { it.name }}）")
            _pendingCandidates.update { current ->
                current + (sessionId to (current[sessionId].orEmpty() + candidates))
            }
        }.onFailure { throwable ->
            logger.w("技能提炼失败（会话 $sessionId）：${throwable.message}", throwable)
        }
    }

    /** 忽略候选：仅从待确认列表移除，不写库。 */
    fun dismissCandidate(sessionId: String, candidate: PendingSkillCandidate) {
        removeCandidate(sessionId, candidate)
    }

    /** 采纳候选：构造自定义技能入库后从待确认列表移除。 */
    suspend fun acceptCandidate(sessionId: String, candidate: PendingSkillCandidate) {
        runCatching {
            val skill = AgentSkill(
                id = "custom_" + UUID.randomUUID().toString().take(8),
                name = candidate.name.trim(),
                description = candidate.description.trim(),
                systemPrompt = candidate.systemPrompt.trim(),
                triggerCommand = normalizeTriggerCommand(candidate.triggerCommand),
                iconName = LEARNED_SKILL_ICON,
                isEnabled = true,
                isBuiltin = false,
                category = "学习",
            )
            agentSkillRepository.addCustom(skill)
            logger.i("技能提炼：候选「${skill.name}」已被采纳入库（${skill.id}）")
        }.onFailure { throwable ->
            logger.w("采纳技能候选失败（会话 $sessionId）：${throwable.message}", throwable)
        }
        // 无论入库成功与否都撤下候选卡片，避免用户反复点击
        removeCandidate(sessionId, candidate)
    }

    /** 从待确认列表移除指定候选（按 name 忽略大小写匹配，列表内 name 已保证唯一）。 */
    private fun removeCandidate(sessionId: String, candidate: PendingSkillCandidate) {
        _pendingCandidates.update { current ->
            val list = current[sessionId] ?: return@update current
            val remaining = list.filterNot { it.name.equals(candidate.name, ignoreCase = true) }
            if (remaining.isEmpty()) current - sessionId else current + (sessionId to remaining)
        }
    }

    /** 触发命令规范化：空值返回 null；非 / 开头的补 /。 */
    private fun normalizeTriggerCommand(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    }

    /**
     * 组装提炼素材：用户与智能体消息各取最近若干条（每条截断），
     * 附上工具使用轨迹；总量硬性控制在 12K 字符内。
     */
    private fun buildMaterial(messages: List<HarnessMessage>): String {
        val userTexts = messages.filterIsInstance<UserMessage>()
            .takeLast(RECENT_MESSAGES_PER_ROLE)
            .map { it.text.trim().take(MAX_SNIPPET_CHARS) }
            .filter { it.isNotEmpty() }
        val assistantTexts = messages.filterIsInstance<AssistantText>()
            .takeLast(RECENT_MESSAGES_PER_ROLE)
            .map { it.text.trim().take(MAX_SNIPPET_CHARS) }
            .filter { it.isNotEmpty() }
        val toolNames = messages.filterIsInstance<ToolCall>()
            .map { it.rawToolName ?: it.tool.name.lowercase() }
            .distinct()
        if (userTexts.isEmpty()) return ""

        val builder = StringBuilder()
        builder.append("【用户消息（最近，已截断）】\n")
        userTexts.forEachIndexed { index, text -> builder.append("用户").append(index + 1).append("：").append(text).append('\n') }
        builder.append("\n【智能体回复（最近，已截断）】\n")
        assistantTexts.forEachIndexed { index, text -> builder.append("回复").append(index + 1).append("：").append(text).append('\n') }
        if (toolNames.isNotEmpty()) {
            builder.append("\n【会话中使用过的工具】\n").append(toolNames.joinToString("、"))
        }
        return builder.toString().take(MAX_MATERIAL_CHARS)
    }

    /** 剥除模型可能输出的 ```json 代码围栏，并截取首个 { 到最后一个 } 的 JSON 主体。 */
    private fun extractJsonBody(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.lineSequence()
                .drop(1) // 去掉首行 ```json 围栏
                .takeWhile { it.trim() != "```" } // 去掉结尾围栏
                .joinToString("\n")
                .trim()
        }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw IllegalStateException("模型输出中未找到 JSON 主体")
        return text.substring(start, end + 1)
    }

    companion object {
        /** 每类角色最多取最近的消息条数。 */
        private const val RECENT_MESSAGES_PER_ROLE = 8
        /** 单条消息素材截断长度（字符）。 */
        private const val MAX_SNIPPET_CHARS = 600
        /** 提炼素材总字符上限。 */
        private const val MAX_MATERIAL_CHARS = 12_000
        /** 触发提炼的最少用户消息数。 */
        private const val MIN_USER_MESSAGES = 2
        /** 与上次提炼相比需新增的用户消息数。 */
        private const val USER_DELTA_FOR_REDISTILL = 3
        /** 单次最多接受的候选数。 */
        private const val MAX_CANDIDATES = 2
        /** 候选 name 最大长度（字符）。 */
        private const val MAX_NAME_CHARS = 24
        /** 候选 systemPrompt 最小长度（字符），过滤空话套话。 */
        private const val MIN_PROMPT_CHARS = 40
        /** 学习类技能使用的图标名（对应 RuntimeIconName.Brain，真实存在于图标体系）。 */
        private const val LEARNED_SKILL_ICON = "Brain"

        /**
         * 提炼协议专用 Json 实例：局部创建、宽松解析，
         * 避免污染注入的全局 json 配置。
         */
        private val distillJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /** 提炼器 system 指令：判断 + 严格 JSON 输出协议。 */
        private val DISTILL_SYSTEM_PROMPT = """
            你是「会话经验提炼器」。分析用户与智能体的对话记录，判断其中是否存在值得沉淀为可复用技能的操作模式、经验或工作流。
            严格输出 JSON（禁止 markdown 代码块包裹，不要输出任何 JSON 以外的文字），格式如下：
            {"worthLearning": boolean, "reason": "判断理由", "skills": [{"name": "简短中文名", "description": "一句话描述", "systemPrompt": "完整技能提示词，面向未来会话的智能体可执行的指导", "triggerCommand": "/短命令或空"}]}
            要求：
            1. systemPrompt 必须包含具体可执行的步骤、判断标准与注意事项，禁止空话套话；
            2. 最多提炼 2 个候选技能，宁缺毋滥；
            3. 如果只是闲聊、一次性任务或没有复用价值的内容，worthLearning 设为 false 且 skills 为空数组；
            4. name 用简短中文名（不超过 12 个字）；triggerCommand 用小写英文短词（如 /deploy），无合适命令则留空。
        """.trimIndent()
    }
}
