package top.tianyan.app.harness.effects

import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.harness.operation.ReplayPolicy

/**
 * 为所有尚无 [ToolResult] 的悬空 [ToolCall] 生成修复计划（策略与执行分离，
 * 便于单元测试）：
 *
 * - 用户主动停止（interrupted=true）：一律写中断占位，绝不重放。
 * - 新运行开始时的历史修复（interrupted=false；能走到这里的悬空调用只可能来自
 *   进程死亡残留——用户取消的运行在取消处理器里已用 interrupted=true 修复）：
 *   SAFE 工具（read / history 查询类只读操作）计划为重放执行，无缝续接上一轮；
 *   NEVER 工具（写操作 / 外部副作用）写结果未知占位，要求先核验实际状态，
 *   防止崩溃前已完成的副作用被模型直接重新发起。
 */
object DanglingToolCallPlanner {

    sealed interface RepairAction {
        val call: ToolCall
    }

    /** 可自动重放的 SAFE 只读工具（仅进程死亡残留场景），由外层负责执行与落盘。 */
    data class Replay(override val call: ToolCall) : RepairAction

    /** 写占位结果说明原因，不再次执行。 */
    data class Stubbed(override val call: ToolCall, val note: String, val errorCode: String) : RepairAction {
        fun result(id: String, createdAt: Long): ToolResult =
            ToolRecoveryNotice(errorCode, note).result(id, createdAt, call.id)
    }

    fun plan(messages: List<HarnessMessage>, interrupted: Boolean): List<RepairAction> {
        val answeredIds = messages.filterIsInstance<ToolResult>().mapTo(mutableSetOf()) { it.toolCallId }
        return messages.filterIsInstance<ToolCall>()
            .filter { it.id !in answeredIds }
            .map { call ->
                val safe = ToolReplayPolicy.forTool(call.tool, call.rawToolName) == ReplayPolicy.SAFE
                val replayable = !interrupted && safe
                if (replayable) {
                    Replay(call)
                } else {
                    val notice = if (safe) ToolRecoveryNotice.cancelled()
                        else ToolRecoveryNotice.unknown(userStopped = interrupted)
                    Stubbed(call, notice.output, notice.code)
                }
            }
    }
}
