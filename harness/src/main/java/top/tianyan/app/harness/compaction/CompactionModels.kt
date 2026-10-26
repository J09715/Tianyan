package top.tianyan.app.harness.compaction

import kotlinx.serialization.Serializable
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.ToolCallMode

@Serializable
data class CompactionPayload(
    val sourceLeafId: String?,
    val summary: String,
    /**
     * 旧版存储保留消息的 JSON 字符串（存在内嵌转义与 StringBuilder 扩容 OOM 风险）；
     * 新版优先使用结构化 [retainedMessages]，本字段默认 null 以保持向后兼容。
     */
    val retainedMessagesJson: String? = null,
    val compactedMessageCount: Int,
    /** Cumulative folded count, added later for O(1) snapshots; null on legacy payloads. */
    val cumulativeCompactedMessageCount: Int? = null,
    val retainedMessageCount: Int,
    val estimatedTokensBefore: Int,
    val createdAt: Long,
    /**
     * 压缩重读快照时分支上的最大 entry sequence（自愈水位线）：重读之后、压缩 entry
     * 落库之前被并发写入的消息（如 acceptRun 直写，不经 laneLock）不在保留消息里，
     * 却因 sequence 小于压缩 entry 而会被投影的 afterMessages 过滤——project() 按
     * (watermark, entry sequence) 区间把这些消息补回，否则从 provider 投影中永久丢失。
     * null = 旧格式 payload，无自愈。
     */
    val sourceWatermarkSequence: Long? = null,
    /**
     * 结构化保留消息列表：直接流式序列化为 JSON 数组，消除嵌套字符串二次转义
     * 与反序列化时十几兆连续 char[] 缓冲区的暴增，根治移动端 Dalvik OOM。
     */
    val retainedMessages: List<HarnessMessage>? = null,
)

data class CompactedContext(
    val summary: String? = null,
    val messages: List<HarnessMessage> = emptyList(),
    /**
     * 活跃分支上、最近一次压缩之后的分支摘要（对齐 pi 的 BranchSummaryEntry）。
     * 按树序排列；在 compact() 时会被折叠进新的压缩摘要，不会重复注入。
     */
    val branchSummaries: List<String> = emptyList(),
    /**
     * 用户轮记忆召回后缀（userMessageId → 后缀块文本，低权威背景资料）。
     * 每用户轮首次组装时计算并持久化（recall_context entry），此后恒定不变——
     * 追加在 user 轮上而非 system prompt，保证历史轮字节稳定（prefix cache 前提）。
     */
    val recallBlocks: Map<String, String> = emptyMap(),
    /**
     * project() 本次读取的分支最大 entry sequence（压缩水位线来源）。compact 锁内以它
     * 标记"快照覆盖到哪"：重读之后、压缩 entry 落库之前被直写落库的 entry sequence 必然
     * 大于它，由 project() 的自愈区间补回。必须与快照出自同一次 branch() 读——分开二次
     * 读会抬高水位线，反而把两次读之间落库的消息排除出自愈区间。
     */
    val sourceMaxSequence: Long = 0L,
) {
    /** 注入 provider 请求的完整摘要层（压缩摘要 + 分支摘要）。 */
    val summaryLayer: String
        get() = (listOfNotNull(summary) + branchSummaries)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
}

/**
 * 压缩摘要请求的上下文（cache-replay 形状的原料，见 CompactionSummarizer）。
 * 摘要请求重放主对话的真实 system prompt、摘要层与原始消息前缀，使输入命中
 * provider 侧 KV 缓存；只在末尾追加一条压缩指令。
 */
data class SummaryRequestContext(
    /** 主对话使用的完整 system prompt（与触发压缩的请求字节一致）。 */
    val systemPrompt: String,
    /** 摘要层（压缩摘要 + 分支摘要），主对话中作为第二条 system 消息注入。 */
    val summaryLayer: String,
    val toolCallMode: ToolCallMode,
    val visionEnabled: Boolean,
    /** 用户轮召回后缀（userMessageId → 文本），重放时与主对话同口径追加。 */
    val recallBlocks: Map<String, String> = emptyMap(),
    /**
     * 被折叠区域的 provider 可见形态（截断后的消息前缀，与主对话实际发送的字节一致）。
     * 对齐 Reasonix「摘要只使用有界 Content、绝不提升 RawContent」的原则；
     * 为空表示无法字节对齐（重放不可行），摘要器回退独立叙事请求。
     */
    val replayPrefix: List<HarnessMessage> = emptyList(),
)

/**
 * 最近一次压缩的只读快照，供 UI 展示折叠透明度信息：
 * 被折叠进摘要的早期消息条数与摘要文本预览。
 */
data class CompactionSnapshot(
    val summary: String,
    /** 累计被折叠进摘要的早期消息条数（多次压缩会累加）。 */
    val foldedMessageCount: Int,
    val createdAt: Long,
)

/** 分支摘要树节点负载：切换分支时对被放弃分支生成、注入新位置的上下文（对齐 pi BranchSummaryEntry）。 */
@Serializable
data class BranchSummaryPayload(
    val summary: String,
    /** 切换前的旧叶子（被放弃分支的末端）。 */
    val fromLeafId: String?,
    /** 被摘要的消息条数。 */
    val summarizedMessageCount: Int,
    val createdAt: Long,
)
