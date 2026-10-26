package top.tianyan.app.core.model.bot

import top.tianyan.app.core.model.ChunkedText
import kotlinx.serialization.Serializable

/** 持久化 PersonaBot：每个 Bot 绑定一个独占会话与一棵 Git 记忆仓库。 */
@Serializable
data class Bot(
    val id: String,
    val name: String,
    val role: String,
    val systemPrompt: String,
    /** 默认图案种子、版本化像素角色配方，或内嵌 PNG data URI；均随 Bot 归档携带。 */
    val avatarSeed: String,
    val modelId: String? = null,
    val providerId: String? = null,
    val sessionId: String,
    val isMainBot: Boolean = false,
    val colorHex: String = "#3D5AFE",
    val createdAt: Long,
    val updatedAt: Long,
    /** 已保存供应商档案内的具体模型；null 使用该档案默认模型。 */
    val modelVariant: String? = null,
    /** 仅用户可开启；关闭时成员工具不能修改模型选择。 */
    val allowModelSelection: Boolean = false,
    /**
     * Bot 级 MCP 能力白名单（服务名列表，大小写不敏感）；null = 不限制（团队共享库全量可用）。
     * 借鉴 DS Bot 能力库：能力按 Bot 挂载、下一条消息生效；MCP 配置只有用户能改，
     * "使用中锁定"由配置入口天然满足。
     */
    val capabilityServerNames: List<String>? = null,
)

/** 群聊普通消息（无 @）由谁回应。 */
@Serializable
enum class GroupReplyMode { ADMIN_FIRST, ALL_MEMBERS }

@Serializable
data class GroupChat(
    val id: String,
    val name: String,
    val adminBotId: String,
    val description: String = "",
    val createdAt: Long,
    val members: List<Bot> = emptyList(),
    /** 群聊聊天背景风格：default / warm / mint / night。 */
    val background: String = "default",
    val replyMode: GroupReplyMode = GroupReplyMode.ADMIN_FIRST,
    val taskBudgetTokens: Long = 0,
    val executionLimits: GroupExecutionLimits = GroupExecutionLimits(),
    val teamGroupId: String? = null,
    val teamVersion: Int? = null,
    /**
     * 群统一审批模式（null = 沿用现状完全访问）。借鉴 DS Bot 的"全员统一沙盒预设"：
     * 群维度成员会话创建与自愈时对齐该模式，覆盖成员个人会话的默认权限；
     * 只有用户可设置，成员工具没有该参数。
     */
    val unifiedApprovalMode: String? = null,
)

/** 可复用的 Bot 成员分组；建群时可按组批量选人。 */
@Serializable
data class BotMemberGroup(
    val id: String,
    val name: String,
    val createdAt: Long,
    val memberIds: List<String> = emptyList(),
    val definition: BotTeamDefinition? = null,
    val publishedVersion: Int = 0,
    val draftRevision: Int = 0,
    val publishedSnapshot: TeamPublishedVersion? = null,
)

/** Bot 会话归属的群聊；用于会话抽屉按群折叠。 */
@Serializable
data class BotSessionGroupRef(
    val sessionId: String,
    val groupId: String,
    val groupName: String,
)

@Serializable
enum class GroupSenderType {
    USER,
    BOT,
    SYSTEM,
}

@Serializable
data class GroupAttachment(
    val name: String,
    val uri: String,
    val mimeType: String = "",
    val sizeBytes: Long = 0,
)

@Serializable
data class GroupReaction(
    val emoji: String,
    val reactorId: String,
    val reactorName: String,
)

/** 群聊气泡里的工具步骤；success 为 null 表示仍在执行。 */
@Serializable
data class GroupToolStep(
    val id: String,
    val name: String,
    val success: Boolean? = null,
    val durationMs: Long? = null,
    /** 工具动作对象：文件名、命令首行或查询词，用于办公室实时投影。 */
    val detail: String? = null,
)

@Serializable
data class GroupMessage(
    val id: String,
    val groupId: String,
    val senderId: String,
    val senderName: String,
    val senderType: GroupSenderType,
    @kotlinx.serialization.SerialName("content") val contentText: ChunkedText,
    val timestamp: Long,
    val isStreaming: Boolean = false,
    val delegatedTaskId: String? = null,
    val attachments: List<GroupAttachment> = emptyList(),
    val reactions: List<GroupReaction> = emptyList(),
    val steps: List<GroupToolStep> = emptyList(),
    val reasoning: String = "",
    /** 群内单调递增序号；用于轮次增量历史与稳定排序。 */
    val seq: Long = 0,
) {
    /** Full text is materialized lazily only for persistence and legacy consumers. */
    val content: String get() = contentText.toString()

    constructor(
        id: String,
        groupId: String,
        senderId: String,
        senderName: String,
        senderType: GroupSenderType,
        content: String,
        timestamp: Long,
        isStreaming: Boolean = false,
        delegatedTaskId: String? = null,
        attachments: List<GroupAttachment> = emptyList(),
        reactions: List<GroupReaction> = emptyList(),
        steps: List<GroupToolStep> = emptyList(),
        reasoning: String = "",
        seq: Long = 0,
    ) : this(
        id = id,
        groupId = groupId,
        senderId = senderId,
        senderName = senderName,
        senderType = senderType,
        contentText = ChunkedText.of(content),
        timestamp = timestamp,
        isStreaming = isStreaming,
        delegatedTaskId = delegatedTaskId,
        attachments = attachments,
        reactions = reactions,
        steps = steps,
        reasoning = reasoning,
        seq = seq,
    )

    fun copy(
        id: String = this.id,
        groupId: String = this.groupId,
        senderId: String = this.senderId,
        senderName: String = this.senderName,
        senderType: GroupSenderType = this.senderType,
        content: String,
        timestamp: Long = this.timestamp,
        isStreaming: Boolean = this.isStreaming,
        delegatedTaskId: String? = this.delegatedTaskId,
        attachments: List<GroupAttachment> = this.attachments,
        reactions: List<GroupReaction> = this.reactions,
        steps: List<GroupToolStep> = this.steps,
        reasoning: String = this.reasoning,
        seq: Long = this.seq,
    ): GroupMessage = copy(
        id = id,
        groupId = groupId,
        senderId = senderId,
        senderName = senderName,
        senderType = senderType,
        contentText = ChunkedText.of(content),
        timestamp = timestamp,
        isStreaming = isStreaming,
        delegatedTaskId = delegatedTaskId,
        attachments = attachments,
        reactions = reactions,
        steps = steps,
        reasoning = reasoning,
        seq = seq,
    )
}

@Serializable
data class MemoryCommit(
    val hash: String,
    val shortHash: String,
    val message: String,
    val author: String,
    val timestamp: Long,
    val filesChanged: List<String> = emptyList(),
)

@Serializable
data class BotMemoryFile(
    val path: String,
    val sizeBytes: Long,
)

/** Bot 作者登记的标准导出清单。 */
@Serializable
data class BotManifest(
    val version: Int = 1,
    val name: String,
    val role: String,
    val systemPrompt: String,
    val modelId: String? = null,
    val avatarSeed: String? = null,
    val colorHex: String = "#3D5AFE",
    val modelVariant: String? = null,
    val allowModelSelection: Boolean = false,
)

/** 离线精选市场条目；本期不依赖远端服务。 */
@Serializable
data class MarketBotItem(
    val id: String,
    val name: String,
    val role: String,
    val description: String,
    val systemPrompt: String,
    val author: String,
    val stars: Int = 0,
    val downloadCount: Int = 0,
    val avatarSeed: String? = null,
    val colorHex: String = "#3D5AFE",
    /** 子智能体角色来源的部门；内置精选为 null。 */
    val departmentId: String? = null,
    val departmentName: String? = null,
)

/** Bot 自主登记的定时任务。nextRunAt 由 Cron 解析结果维护。 */
@Serializable
data class BotSchedule(
    val id: String,
    val botId: String,
    val groupId: String? = null,
    val cron: String,
    val instruction: String,
    val enabled: Boolean = true,
    val nextRunAt: Long,
    val lastRunAt: Long? = null,
    val createdAt: Long,
)
