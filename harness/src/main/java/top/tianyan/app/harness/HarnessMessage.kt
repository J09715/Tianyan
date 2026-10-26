package top.tianyan.app.harness

import top.tianyan.app.core.model.ChunkedText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import top.tianyan.app.core.model.ToolImageRef

/** Harness 基础工具，与 LLM tool-calling 协议对齐。 */
@Serializable
enum class HarnessTool {
    @SerialName("read") READ,
    @SerialName("write") WRITE,
    @SerialName("edit") EDIT,
    @SerialName("base") BASE,
    @SerialName("process") PROCESS,
    @SerialName("host") HOST,
    @SerialName("download") DOWNLOAD,
    @SerialName("memory") MEMORY,
    @SerialName("plan") PLAN,
    @SerialName("scratchpad") SCRATCHPAD,
    @SerialName("history_search") HISTORY_SEARCH,
    @SerialName("history_read") HISTORY_READ,
    @SerialName("build_script") BUILD_SCRIPT,
    @SerialName("invoke_subagent") SUBAGENT,
    @SerialName("mcp") MCP,
    @SerialName("load_rule") LOAD_RULE,
    @SerialName("load_skill") LOAD_SKILL,
    @SerialName("compress") COMPRESS,
    @SerialName("ask_user") ASK_USER,
    @SerialName("render_surface") RENDER_SURFACE,
}

/**
 * Harness 会话消息。序列化格式即持久化格式（Room 存 payloadJson），
 * 同时由 HarnessLoop 转换为 LLM 请求/响应格式。
 */
@Serializable
sealed interface HarnessMessage {
    val id: String
    val createdAt: Long
}

@Serializable
@SerialName("skill_suggestion")
data class SkillSuggestion(
    override val id: String,
    override val createdAt: Long,
    /** create = 沉淀为新技能；update = 修复既有技能的指导规则 */
    val action: String,
    val skillName: String,
    val description: String,
    val systemPrompt: String,
    val triggerCommand: String? = null,
    /** action=update 时的目标技能 id */
    val targetSkillId: String? = null,
    val reason: String = "",
    /** pending = 待处理；applied / dismissed 由 UI 内存态过滤（转写里保留原样） */
    val status: String = "pending",
) : HarnessMessage

/** UI-only capability activation event. It is persisted for the transcript but never sent to the model. */
@Serializable
@SerialName("capability_event")
data class CapabilityEvent(
    override val id: String,
    override val createdAt: Long,
    val kind: Kind,
    val name: String,
    val details: String = "",
) : HarnessMessage {
    @Serializable
    enum class Kind { SKILL, MCP }
}

/** UI-only model switch record. Persisted in the transcript, never sent to the provider. */
@Serializable
@SerialName("model_switch")
data class ModelSwitchEvent(
    override val id: String,
    override val createdAt: Long,
    val fromLabel: String = "",
    val toLabel: String,
    val fromContextTokens: Int? = null,
    val toContextTokens: Int,
    val compacted: Boolean = false,
    val foldedMessageCount: Int = 0,
    val compactionPending: Boolean = false,
) : HarnessMessage

/**
 * UI-only 轮文件改动卡片（借鉴 DSH deliverables：轮结束的快照对比自动得出改动文件）。
 *
 * 数据源是 CheckpointStore 的轮快照（write/edit 触碰文件在轮初与最后写入后的内容对），
 * 不含 shell 命令改动的文件（它们不经写工具，无快照）；id 确定性派生自会话与轮号，
 * 重复发布（完成即时 + 下一轮开头补发）由存储层同 id 同 payload 幂等复用去重。
 */
@Serializable
@SerialName("turn_changes")
data class TurnChangesEvent(
    override val id: String,
    override val createdAt: Long,
    /** 0 起始的用户轮次号（与 rewind checkpoint 的轮号同源）。 */
    val turn: Int,
    /** 轮初不存在、本轮新建的文件（工作区相对路径）。 */
    val created: List<String> = emptyList(),
    /** 轮初存在且内容发生变化的文件。 */
    val modified: List<String> = emptyList(),
    /** 被写工具触碰但无改动后凭据（写入失败/超大文件跳过捕获），改动未知。 */
    val touched: List<String> = emptyList(),
) : HarnessMessage

@Serializable
@SerialName("user")
data class UserMessage(
    override val id: String,
    override val createdAt: Long,
    val text: String,
    val imageUrls: List<String> = emptyList(),
) : HarnessMessage

@Serializable
@SerialName("assistant")
data class AssistantText(
    override val id: String,
    override val createdAt: Long,
    @kotlinx.serialization.SerialName("text") val textContent: ChunkedText,
    /** 推理模型（如 DeepSeek-R1）返回的 reasoning_content，多轮时需原样传回 API。 */
    val reasoning: String? = null,
    /**
     * 本轮执行总耗时（从用户发送到该条最终回复产生，毫秒）。
     * 仅在作为本轮收尾消息（最终回复 / 中断提示）时记录；旧数据无此字段，默认 null。
     */
    val totalMs: Long? = null,
    val modelId: String? = null,
    val providerId: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val cachedTokens: Int? = null,
    /**
     * 模型思考（reasoning）流的确切耗时（毫秒，从首个 reasoning 增量到 reasoning 结束）。
     * 用于前端展示「已深度思考 N.N 秒」；旧数据无此字段，默认 null。
     */
    val reasoningMs: Long? = null,
    val responsesTurn: ResponsesTurn? = null,
) : HarnessMessage {
    /** Full text is materialized lazily only for persistence and legacy consumers. */
    val text: String get() = textContent.toString()

    constructor(
        id: String,
        createdAt: Long,
        text: String,
        reasoning: String? = null,
        totalMs: Long? = null,
        modelId: String? = null,
        providerId: String? = null,
        promptTokens: Int? = null,
        completionTokens: Int? = null,
        cachedTokens: Int? = null,
        reasoningMs: Long? = null,
        responsesTurn: ResponsesTurn? = null,
    ) : this(
        id = id,
        createdAt = createdAt,
        textContent = ChunkedText.of(text),
        reasoning = reasoning,
        totalMs = totalMs,
        modelId = modelId,
        providerId = providerId,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        cachedTokens = cachedTokens,
        reasoningMs = reasoningMs,
        responsesTurn = responsesTurn,
    )

    fun copy(
        id: String = this.id,
        createdAt: Long = this.createdAt,
        text: String,
        reasoning: String? = this.reasoning,
        totalMs: Long? = this.totalMs,
        modelId: String? = this.modelId,
        providerId: String? = this.providerId,
        promptTokens: Int? = this.promptTokens,
        completionTokens: Int? = this.completionTokens,
        cachedTokens: Int? = this.cachedTokens,
        reasoningMs: Long? = this.reasoningMs,
        responsesTurn: ResponsesTurn? = this.responsesTurn,
    ): AssistantText = copy(
        id = id,
        createdAt = createdAt,
        textContent = ChunkedText.of(text),
        reasoning = reasoning,
        totalMs = totalMs,
        modelId = modelId,
        providerId = providerId,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        cachedTokens = cachedTokens,
        reasoningMs = reasoningMs,
        responsesTurn = responsesTurn,
    )
}

@Serializable
@SerialName("tool_call")
data class ToolCall(
    override val id: String,
    override val createdAt: Long,
    val tool: HarnessTool,
    val args: JsonObject,
    /** 触发本次调用的 assistant 轮次的推理内容，多轮时需原样传回 API。 */
    val reasoning: String? = null,
    /** 原始工具名称（用于 MCP 动态工具或子智能体识别） */
    val rawToolName: String? = null,
) : HarnessMessage

@Serializable
@SerialName("tool_result")
data class ToolResult(
    override val id: String,
    override val createdAt: Long,
    val toolCallId: String,
    val success: Boolean,
    val output: String,
    /** 该工具调用实际执行耗时（毫秒，不含排队与 LLM 时间）。旧数据无此字段，默认 null。 */
    val durationMs: Long? = null,
    /** Host approval gate paused this tool call; the model loop must wait for the user. */
    val awaitingApproval: Boolean = false,
    val approvalRequestId: String? = null,
    /**
     * 该调用需要审批，但执行环境无法承载审批暂停（子智能体后台 Lane），因此未执行。
     * 与 [awaitingApproval] 的区别：这里没有待审批请求可供用户批准，必须由主智能体重新发起。
     */
    val approvalDeferred: Boolean = false,
    /**
     * 工具产物中的图片附件引用列表（如 mcp__browser__screenshot 落盘的 PNG）。
     * 持久化兼容：旧数据无此字段；序列化与 Room payload 默认空数组。
     */
    val imageAttachments: List<ToolImageRef> = emptyList(),
    /**
     * 工具结果的结构化元数据（不进入模型上下文）。当前用于 edit 工具：
     * metadata["diff"] 携带 Unified Diff，供前端 DiffView 渲染对比视图。
     * 持久化兼容：旧数据无此字段；序列化与 Room payload 默认空表。
     */
    val metadata: Map<String, String> = emptyMap(),
    /**
     * 沙箱图片多模态直通载荷：`data:image/png;base64,...`。
     * read 工具识别到图片后缀时写入，由 ApiMessageProjector 在 visionEnabled 时
     * 追加为图片消息发给多模态模型；非视觉模型会被忽略。旧数据默认 null。
     */
    val imageDataUrl: String? = null,
    /** Stable recovery error code. Old results have no code; success remains false for unknown outcomes. */
    val errorCode: String? = null,
    /** Machine-readable suggestions; neither grants authority nor triggers replay. Old history defaults to null. */
    val recovery: ToolRecoveryAdvice? = null,
) : HarnessMessage
