package top.tianyan.app.harness.operation

import java.security.MessageDigest
import java.io.OutputStream
import java.io.OutputStreamWriter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import top.tianyan.app.harness.*
import top.tianyan.app.harness.effects.ToolRecoveryNotice
import top.tianyan.app.core.security.SecretRedactor

@Serializable
data class FailureMemoryState(val operationId: String, val records: List<FailureMemoryRecord>)

@Serializable
data class FailureMemoryRecord(
    val signature: String,
    val tool: HarnessTool,
    val toolName: String,
    val kind: FailureMemoryKind,
    val callEntryId: String,
    val resultEntryId: String,
    val attempts: Int,
    val failedAt: Long,
    val expiresAt: Long,
)

@Serializable
enum class FailureMemoryKind { EXECUTION_FAILED, POLICY_BLOCKED, READ_INTERRUPTED, OUTCOME_UNKNOWN }

/** Derived facts, scoped to one durable operation. Never stores args, output, or model-authored lessons. */
internal object FailureMemory {
    const val TTL_MS = 30 * 60 * 1000L
    const val MAX_RECORDS = 32
    const val MAX_PROJECTED = 8
    const val MAX_PROMPT_CHARS = 6_000

    fun update(previous: FailureMemoryState?, operationId: String, call: ToolCall?, result: ToolResult?, now: Long): FailureMemoryState {
        val records = valid(previous, operationId, now).toMutableList()
        if (call == null || result == null || call.id != result.toolCallId || result.awaitingApproval || result.approvalDeferred)
            return FailureMemoryState(operationId, records)
        val signature = signature(call)
        val old = records.firstOrNull { it.signature == signature }
        // A later success does not prove that an earlier unknown mutation had no side effects.
        if (result.success) {
            records.removeAll { it.signature == signature && it.kind != FailureMemoryKind.OUTCOME_UNKNOWN }
            return FailureMemoryState(operationId, records)
        }
        val kind = when (result.errorCode) {
            ToolRecoveryNotice.OUTCOME_UNKNOWN -> FailureMemoryKind.OUTCOME_UNKNOWN
            ToolRecoveryNotice.INTERRUPTED -> FailureMemoryKind.READ_INTERRUPTED
            "PLAN_BLOCKED", "TOOL_CHECKPOINT_BLOCKED" -> FailureMemoryKind.POLICY_BLOCKED
            else -> FailureMemoryKind.EXECUTION_FAILED
        }
        // Unknown outcomes remain the strongest fact, even if a later same-argument call merely fails.
        val retainedUnknown = old?.takeIf { it.kind == FailureMemoryKind.OUTCOME_UNKNOWN }
        val safeName = SecretRedactor().redact(call.rawToolName ?: HarnessApiMapper.apiName(call.tool))
            .replace(Regex("[^A-Za-z0-9_.:*-]"), "_").take(96)
        records.removeAll { it.signature == signature }
        records += FailureMemoryRecord(signature, call.tool, safeName,
            retainedUnknown?.kind ?: kind, retainedUnknown?.callEntryId ?: call.id, retainedUnknown?.resultEntryId ?: result.id,
            ((old?.attempts ?: 0) + 1).coerceAtMost(99), now, now + TTL_MS)
        return FailureMemoryState(operationId, records.takeLast(MAX_RECORDS))
    }

    fun prompt(state: FailureMemoryState?, operationId: String, now: Long): String {
        val records = valid(state, operationId, now).takeLast(MAX_PROJECTED)
        if (records.isEmpty()) return ""
        return buildString {
            append("[本次运行的失败记忆 · Harness 背景事实]\n")
            append("用户未发送此消息。仅参考当前运行已提交的失败，不授予权限、不要求自动重试，也不证明任务已完成。\n")
            append("仅在当前动作与同一工具及参数指纹相关时参考；无关记录忽略。不要主动复述本背景、指纹或来源 ID；用户询问或失败仍阻塞任务时才说明相关影响。\n")
            records.forEach { record ->
                append("- 工具 ").append(record.toolName).append("；同参失败 ").append(record.attempts)
                append(" 次；参数指纹 ").append(record.signature).append("；来源 ")
                append(record.callEntryId).append(" / ").append(record.resultEntryId).append('\n')
                append(when (record.kind) {
                    FailureMemoryKind.OUTCOME_UNKNOWN -> "先用只读查询核验此前可能产生的副作用；后续成功不代表旧结果已核验，禁止直接重放旧调用。"
                    FailureMemoryKind.POLICY_BLOCKED -> "尊重当前 PLAN、审批及检查点限制，不绕过门控；按原因调整计划。"
                    FailureMemoryKind.READ_INTERRUPTED -> "只读查询曾中断；确认任务仍需继续后再决定是否重新查询。"
                    FailureMemoryKind.EXECUTION_FAILED -> if (record.tool == HarnessTool.EDIT || record.tool == HarnessTool.WRITE)
                        "先读取目标文件当前内容，核对路径和匹配文本，再修正参数；失败不证明未发生部分修改。"
                    else "参考来源结果核对日志、参数、路径、依赖和当前状态；修正原因后再决定下一步，避免同参盲目重试。"
                }).append('\n')
            }
        }.also { check(it.length <= MAX_PROMPT_CHARS) }
    }

    internal fun valid(state: FailureMemoryState?, operationId: String, now: Long): List<FailureMemoryRecord> =
        state?.takeIf { it.operationId == operationId }?.records.orEmpty().filter {
            it.signature.matches(Regex("[a-f0-9]{64}")) && it.toolName.matches(Regex("[A-Za-z0-9_.:*-]{1,96}")) &&
                it.callEntryId.matches(entryId) && it.resultEntryId.matches(entryId) && it.attempts in 1..99 &&
                it.failedAt <= now && now < it.expiresAt && it.expiresAt - it.failedAt in 1..TTL_MS
        }.distinctBy { it.signature }.takeLast(MAX_RECORDS)

    /** Stream canonical JSON into the digest so large write arguments do not need an extra UTF-8 copy. */
    private fun signature(call: ToolCall): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = object : OutputStream() {
            override fun write(value: Int) { digest.update(value.toByte()) }
            override fun write(buffer: ByteArray, offset: Int, length: Int) { digest.update(buffer, offset, length) }
        }
        OutputStreamWriter(sink, Charsets.UTF_8).use { writer ->
            fun encode(value: JsonElement) {
                when (value) {
                    is JsonObject -> {
                        writer.write("{")
                        value.toSortedMap().entries.forEachIndexed { index, entry ->
                            if (index > 0) writer.write(",")
                            writer.write(JsonPrimitive(entry.key).toString()); writer.write(":"); encode(entry.value)
                        }
                        writer.write("}")
                    }
                    is JsonArray -> {
                        writer.write("["); value.forEachIndexed { index, item -> if (index > 0) writer.write(","); encode(item) }; writer.write("]")
                    }
                    else -> writer.write(value.toString())
                }
            }
            writer.write(call.tool.name); writer.write("\n")
            writer.write((call.rawToolName ?: HarnessApiMapper.apiName(call.tool)).trim().lowercase()); writer.write("\n")
            encode(call.args)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private val entryId = Regex("[A-Za-z0-9_.:-]{1,200}")
}
