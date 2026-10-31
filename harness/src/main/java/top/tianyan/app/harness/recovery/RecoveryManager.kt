package top.tianyan.app.harness.recovery

import java.util.UUID
import kotlinx.serialization.json.Json
import top.tianyan.app.core.database.AgentApprovalRepository
import top.tianyan.app.core.database.HarnessRuntimeRepository
import top.tianyan.app.harness.effects.ToolRecoveryNotice
import top.tianyan.app.harness.events.HarnessEvent
import top.tianyan.app.harness.events.HarnessEventBus
import top.tianyan.app.harness.operation.OperationCoordinator
import top.tianyan.app.harness.operation.OperationPhase
import top.tianyan.app.harness.operation.OperationSnapshot
import top.tianyan.app.harness.operation.OperationStatus
import top.tianyan.app.harness.operation.ReplayPolicy
import top.tianyan.app.harness.session.SessionTreeStore

sealed interface RecoveryOutcome {
    data object Clean : RecoveryOutcome
    data class Suspended(val operationId: String, val reason: String) : RecoveryOutcome
    data class ToolInterrupted(val operationId: String, val toolCallId: String) : RecoveryOutcome
    data object WaitingApproval : RecoveryOutcome
}

/** Applies explicit crash policy from the last durable operation snapshot. */
class RecoveryManager(
    private val repository: HarnessRuntimeRepository,
    private val coordinator: OperationCoordinator,
    private val approvalRepository: AgentApprovalRepository? = null,
    private val json: Json,
    private val eventBus: HarnessEventBus,
) {
    suspend fun recoverSession(sessionId: String): RecoveryOutcome {
        val laneNames = listOf(SessionTreeStore.MAIN_LANE) +
            repository.listActiveOperations(sessionId).map { it.laneName }
                .filter { it != SessionTreeStore.MAIN_LANE }.distinct()
        var mainOutcome: RecoveryOutcome = RecoveryOutcome.Clean
        for (laneName in laneNames) {
            // An old operation row can survive a lane move; only its current pointer is recoverable.
            if (laneName != SessionTreeStore.MAIN_LANE && repository.findLane(sessionId, laneName)?.currentOperationId == null) continue
            val outcome = recoverSessionInternal(sessionId, laneName)
            if (laneName == SessionTreeStore.MAIN_LANE) mainOutcome = outcome
            if (outcome !is RecoveryOutcome.Clean) {
                val detail = when (outcome) {
                    is RecoveryOutcome.Suspended -> outcome.reason
                    is RecoveryOutcome.ToolInterrupted -> "工具结果未知，已写入核验指引"
                    RecoveryOutcome.WaitingApproval -> "存在等待中的审批"
                    RecoveryOutcome.Clean -> null
                }
                val operationId = when (outcome) {
                    is RecoveryOutcome.Suspended -> outcome.operationId
                    is RecoveryOutcome.ToolInterrupted -> outcome.operationId
                    else -> null
                }
                eventBus.emit(
                    HarnessEvent.RecoveryApplied(
                        sessionId = sessionId,
                        timestamp = System.currentTimeMillis(),
                        operationId = operationId,
                        outcome = outcome::class.simpleName ?: "unknown",
                        detail = detail,
                    ),
                )
            }
        }
        return mainOutcome
    }

    private suspend fun recoverSessionInternal(sessionId: String, laneName: String): RecoveryOutcome {
        val operation = coordinator.active(sessionId, laneName) ?: return RecoveryOutcome.Clean
        if (operation.status == OperationStatus.WAITING_APPROVAL.id) {
            // 审批等待期间进程死亡：审批可能已在停机期间过期/丢失。
            // pendingNow 会先清扫过期请求；无可恢复审批时终止该操作，避免 lane 永久占用。
            if (approvalRepository != null && approvalRepository.pendingNow(sessionId).isEmpty()) {
                coordinator.finish(sessionId, "aborted", details = "等待中的审批已过期或失效", laneName = operation.laneName)
                return RecoveryOutcome.Suspended(operation.id, "等待中的审批已过期或失效")
            }
            return RecoveryOutcome.WaitingApproval
        }
        val snapshot = runCatching {
            json.decodeFromString(OperationSnapshot.serializer(), operation.stateJson)
        }.getOrElse {
            coordinator.suspendOperation(operation.id, "无法解码持久化运行状态")
            return RecoveryOutcome.Suspended(operation.id, "运行状态损坏")
        }

        if (snapshot.phase == OperationPhase.TOOL_INTENT.id && operation.replayPolicy != ReplayPolicy.SAFE.id) {
            val toolCallId = snapshot.effectId ?: operation.pendingEffectId ?: "unknown"
            coordinator.toolSettled(
                operationId = operation.id,
                message = ToolRecoveryNotice.unknown().result(
                    id = UUID.randomUUID().toString(),
                    createdAt = System.currentTimeMillis(),
                    toolCallId = toolCallId,
                ),
                round = snapshot.round,
            )
            coordinator.suspendOperation(operation.id, "工具结果未知，需核验实际状态后继续")
            return RecoveryOutcome.ToolInterrupted(operation.id, toolCallId)
        }

        val reason = when {
            snapshot.phase == OperationPhase.TOOL_INTENT.id -> "可安全重放的工具在执行期间中断"
            snapshot.phase == OperationPhase.PROVIDER_INTENT.id -> "模型请求结果未落盘，是否产生费用无法确定"
            else -> "运行在检查点之外中断"
        }
        coordinator.suspendOperation(operation.id, reason)
        return RecoveryOutcome.Suspended(operation.id, reason)
    }
}
