package top.tianyan.app.harness

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Harness 基础工具，与 LLM tool-calling 协议对齐。 */
@Serializable
enum class HarnessTool {
    @SerialName("read") READ,
    @SerialName("write") WRITE,
    @SerialName("edit") EDIT,
    @SerialName("verify") VERIFY,
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
    @SerialName("redteam") REDTEAM,
    @SerialName("ask_user_question") ASK_USER,
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

@Serializable
@SerialName("user")
data class UserMessage(
    override val id: String,
    override val createdAt: Long,
    val text: String,
    val imageUrls: List<String> = emptyList(),
    /**
     * 本条消息发送时附带的「动态上下文尾部」快照（记忆检索结果、规则路由、
     * 技能、计划看板等），**持久化**。
     *
     * 为什么必须持久化而不是每轮现算：provider 的前缀缓存按精确前缀匹配计费，
     * 第 N+1 轮请求的前 |请求 N| 字节必须与请求 N 完全一致。若尾部在渲染期
     * 临时拼接，下一轮那条消息变成历史消息、尾部消失，字节就变了 ——
     * 断裂点只是从 system 位置 0 搬到第一条 user 消息，缓存依然全量失效。
     *
     * 把它作为消息的一部分存下来，渲染就成了「持久化字段的纯函数」：
     * 字段不变 → 字节不变 → 前缀完整。这与 DSH 用 frozenUserMerged
     * 为每条 user 消息保留 trailer 快照的做法同源。
     *
     * 默认空串保证旧数据可读（反序列化时缺失即空），历史消息渲染结果不变。
     */
    val contextTail: String = "",
) : HarnessMessage

@Serializable
@SerialName("assistant")
data class AssistantText(
    override val id: String,
    override val createdAt: Long,
    val text: String,
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
) : HarnessMessage

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
     * 工具产物中的图片附件引用列表（如 mcp__browser__screenshot 落盘的 PNG）。
     * 持久化兼容：旧数据无此字段；序列化与 Room payload 默认空数组。
     */
    val imageAttachments: List<top.tianyan.app.core.model.ToolImageRef> = emptyList(),
) : HarnessMessage
