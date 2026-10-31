package top.tianyan.app.harness.approval

import java.util.UUID
import top.tianyan.app.harness.ToolResult

/** PLAN 只读模式拦截的工具结果（从 ToolExecutor 抽出，拦截文案单一出处）。 */
fun planBlockedResult(now: Long, toolCallId: String, displayName: String, reason: String): ToolResult =
    ToolResult(
        id = UUID.randomUUID().toString(),
        createdAt = now,
        toolCallId = toolCallId,
        success = false,
        errorCode = "PLAN_BLOCKED",
        output = buildString {
            append("⛔ 只读规划模式（PLAN）已拦截本次调用：").append(displayName).append('\n')
            append("原因：").append(reason).append('\n')
            append("本次调用未执行，也未进入审批队列。\n")
            append("请不要重试同一调用，也不要声称该操作已完成：")
            append("把该动作写进规划结论的待办中，由用户切换到 BUILD（构建）模式后再执行。")
        },
    )
