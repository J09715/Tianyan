package top.tianyan.app.harness

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.tianyan.app.harness.effects.ToolRecoveryNotice

/** Recovery suggestions are evidence for the model, never permission or an automatic replay policy. */
@Serializable
data class ToolRecoveryAdvice(
    /** There is a suggested inspection/recovery path; this does not mean the failed operation is safe to retry. */
    val recoverable: Boolean,
    val retrySameArguments: Boolean,
    val nextActions: List<ToolRecoveryAction>,
)

@Serializable
data class ToolRecoveryAction(val kind: String, val instruction: String, val tool: String? = null)

internal fun ToolResult.withRecoveryAdvice(tool: HarnessTool? = null): ToolResult {
    if (success || recovery != null) return this
    val actions = when {
        errorCode == "PLAN_BLOCKED" -> listOf(ToolRecoveryAction("respect_plan", "当前仅允许规划；把变更列入待办，由用户决定是否切换执行模式。"))
        errorCode == "TOOL_CHECKPOINT_BLOCKED" -> listOf(ToolRecoveryAction("respect_checkpoint", "本次调用被受信任检查点阻止；按原因调整任务，不绕过检查点。"))
        errorCode == ToolRecoveryNotice.OUTCOME_UNKNOWN -> listOf(
            ToolRecoveryAction("verify_state", "先用只读查询核验实际副作用；无法核验时说明不确定性并取得用户确认。禁止直接重放。"),
        )
        awaitingApproval -> listOf(ToolRecoveryAction("wait_approval", "等待当前审批或提问结果，不创建重复请求。"))
        approvalDeferred -> listOf(ToolRecoveryAction("return_to_parent", "把未执行的操作交给主会话发起并等待批准。"))
        errorCode == ToolRecoveryNotice.INTERRUPTED -> listOf(
            ToolRecoveryAction("resume_read", "只读查询已中断；仅在任务仍需继续且未被用户停止时重新查询。"),
        )
        tool == HarnessTool.EDIT || tool == HarnessTool.WRITE -> listOf(
            ToolRecoveryAction("inspect_current_file", "先读取目标文件的当前内容，核对路径、匹配文本及是否已有部分修改。", "read"),
            ToolRecoveryAction("correct_arguments", "根据观察修正参数；不要把失败解释为没有发生修改。"),
        )
        tool == HarnessTool.BASE -> listOf(
            ToolRecoveryAction("inspect_failure", "读取日志并核验命令可能产生的部分副作用，区分路径、依赖、语法和超时问题。"),
            ToolRecoveryAction("change_strategy", "修正原因后再决定下一步；命令退出失败不证明此前操作均未生效。"),
        )
        else -> listOf(ToolRecoveryAction("inspect_failure", "按错误信息核对参数、能力和当前状态，调整策略后再继续。"))
    }
    val blocked = errorCode == "PLAN_BLOCKED" || errorCode == "TOOL_CHECKPOINT_BLOCKED"
    return copy(recovery = ToolRecoveryAdvice(recoverable = !blocked, retrySameArguments = false, nextActions = actions))
}

/** Append only at model projection: UI output stays concise and the durable advice survives truncation. */
internal fun ToolResult.modelOutput(): String = recovery?.let {
    output + "\n\n[工具恢复建议]\n" + Json.encodeToString(ToolRecoveryAdvice.serializer(), it)
} ?: output
