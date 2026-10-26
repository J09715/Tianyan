package top.tianyan.app.harness.directory

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 组合工具的嵌套调用记录（借鉴 pi v1.1.0 的 nestedCalls 契约）。
 *
 * 语义：一个工具内部执行的其他工具调用**不产生独立 transcript 条目**——结果只回给
 * 调用方工具，由父结果向模型汇报；但会话在父结果的 metadata 中保留一份**有界审计
 * 记录**（名称、脱敏参数摘要、状态、耗时、脱敏错误；绝不存结果正文）。
 *
 * 与持久化的关系（对应状态归属审计的区分）：
 * - 「不进入模型上下文」≠「无需持久化」——metadata 随 ToolResult 持久化，仅供
 *   审计、压缩文件清单与导出使用；
 * - 子智能体/双智能体/工作流节点**不适用本契约**：它们有独立模型循环与持久化
 *   Lane，保留完整历史以支持恢复、审批追踪与写入证据，父会话只接收摘要与 Lane 引用。
 *
 * 有界化（对齐 pi）：最多保留 [MAX_RECORDS] 条（超出丢弃最旧并置 complete=false）；
 * 参数摘要截断到 [MAX_ARGUMENT_CHARS]，错误截断到 [MAX_ERROR_CHARS]；两者写入前
 * 必须经调用方注入的脱敏函数——即使不存结果正文，参数与错误仍可能含密钥。
 */
@Serializable
data class NestedCallRecord(
    /** 形如 `<parentToolCallId>/<n>`；与父结果的 toolCallId 关联，不进入模型上下文。 */
    val toolCallId: String,
    /** 内层工具的逻辑名（如 `host.virtual_screen_click`、`mcp.<server>.<tool>`）。 */
    val name: String,
    /** [NestedCalls.STATUS_OK] / [NestedCalls.STATUS_ERROR] / [NestedCalls.STATUS_BLOCKED]。 */
    val status: String,
    val durationMs: Long,
    /** 脱敏 + 截断后的参数摘要；无参数为 null。 */
    val argumentsPreview: String? = null,
    /** 脱敏 + 截断后的失败原因；成功为 null。 */
    val error: String? = null,
)

/** metadata 中的整体结构：complete=false 表示发生过截断，记录不再完整。 */
@Serializable
data class NestedCallLog(
    val complete: Boolean = true,
    val calls: List<NestedCallRecord> = emptyList(),
    /** Total attempts, independent of the bounded retained window; zero for legacy logs. */
    val totalCalls: Long = 0,
)

object NestedCalls {

    const val METADATA_KEY = "nested_calls"
    const val STATUS_OK = "ok"
    const val STATUS_ERROR = "error"
    const val STATUS_BLOCKED = "blocked"
    const val MAX_RECORDS = 256
    const val MAX_ARGUMENT_CHARS = 8_192
    const val MAX_ERROR_CHARS = 512

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun read(metadata: Map<String, String>): NestedCallLog? =
        metadata[METADATA_KEY]?.let { raw ->
            runCatching { json.decodeFromString(NestedCallLog.serializer(), raw) }.getOrNull()
        }

    /**
     * 追加一条嵌套调用记录。脱敏由 [redact] 注入（调用方持有 SecretRedactor 与
     * 已知密钥值列表，目录层保持纯净）；失败静默保留旧记录——审计是安全网，不能
     * 反过来破坏工具结果提交。
     */
    fun append(
        metadata: MutableMap<String, String>,
        parentToolCallId: String?,
        name: String,
        status: String,
        durationMs: Long,
        arguments: String? = null,
        error: String? = null,
        redact: (String) -> String = { it },
    ) {
        val current = read(metadata) ?: NestedCallLog()
        val seq = maxOf(current.totalCalls, current.calls.maxOfOrNull {
            it.toolCallId.substringAfterLast('/').toLongOrNull() ?: 0
        } ?: 0) + 1
        val redactedArgs = arguments?.let(redact)
        val redactedError = error?.let(redact)
        val truncated = (redactedArgs?.length ?: 0) > MAX_ARGUMENT_CHARS ||
            (redactedError?.length ?: 0) > MAX_ERROR_CHARS
        val record = NestedCallRecord(
            toolCallId = "${parentToolCallId ?: "nested"}/$seq",
            name = name,
            status = status,
            durationMs = durationMs.coerceAtLeast(0),
            argumentsPreview = redactedArgs?.take(MAX_ARGUMENT_CHARS),
            error = redactedError?.take(MAX_ERROR_CHARS),
        )
        val kept = current.calls + record
        val trimmed = kept.takeLast(MAX_RECORDS)
        val log = current.copy(complete = current.complete && !truncated && kept.size <= MAX_RECORDS,
            calls = trimmed, totalCalls = seq)
        runCatching { metadata[METADATA_KEY] = json.encodeToString(NestedCallLog.serializer(), log) }
    }
}
