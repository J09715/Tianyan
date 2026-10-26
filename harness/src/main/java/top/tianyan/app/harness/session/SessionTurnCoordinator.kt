package top.tianyan.app.harness.session

import kotlinx.coroutines.flow.StateFlow

/**
 * Agent 轮次执行优先级。
 */
enum class TurnPriority {
    /** 审批续跑、用户打断纠偏 (Steer)；优先竞争全局并发槽位 */
    HIGH,
    /** 普通用户消息与会话发送 */
    NORMAL,
    /** 后台子智能体 (Subagent Lane)、工作流后台节点、定时巡检 */
    BACKGROUND,
}

/**
 * 会话当前的调度排队状态（供 UI 投影与前台服务通知展示）。
 */
sealed interface TurnSchedulingState {
    /** 空闲，没有正在执行或排队的 Turn */
    data object Idle : TurnSchedulingState

    /** 会话内部互斥排队中（前面有同一会话更早的 Turn 正在运行） */
    data class QueuedInSession(val queuePosition: Int = 1) : TurnSchedulingState

    /** 已获得会话独占权，正在等待全局并发配额槽位（避免多会话并发挤爆端侧资源） */
    data class WaitingGlobalSlot(val waitingCount: Int = 1) : TurnSchedulingState

    /** 正在执行模型推理与工具循环 */
    data class Executing(val startedAt: Long, val priority: TurnPriority) : TurnSchedulingState
}

/**
 * 会话级并发排队与调度中枢接口（借鉴 PalmClaw SessionTurnCoordinator 设计）。
 *
 * 核心职责：
 * 1. **Per-Session 串行化**：确保同一会话内的 Agent 轮次严格串行，具备引用计数清理，杜绝锁对象内存泄漏；
 * 2. **Cross-Session 有界并发**：控制跨会话最大全局并行 Turn 数量，避免移动端沙箱 CPU 卡顿与模型 API 429；
 * 3. **两阶段防饥饿加锁**：先获得会话锁排队，再竞争全局配额，杜绝跨会话饿死。
 */
interface SessionTurnCoordinator {
    /** 当前正在占用全局配额执行中的 Turn 总数 */
    val activeTurnCount: StateFlow<Int>

    /** 各会话的调度状态快照映射（可观测性） */
    val sessionSchedulingStates: StateFlow<Map<String, TurnSchedulingState>>

    /**
     * 在会话独占 + 全局有界并发受控环境下执行一个 Agent Turn。
     */
    suspend fun <T> withSessionTurn(
        sessionId: String,
        priority: TurnPriority = TurnPriority.NORMAL,
        block: suspend () -> T,
    ): T

    /**
     * 仅获取会话级独占 Mutex 执行轻量级会话操作（如队列消费、审批过期检查、状态流刷新），
     * 不占用全局并发配额槽位。
     */
    suspend fun <T> withSessionMutex(
        sessionId: String,
        block: suspend () -> T,
    ): T

    /**
     * 当会话被彻底删除时淘汰并清理其锁状态与缓存。
     */
    fun evictSession(sessionId: String)

    companion object {
        const val DEFAULT_MAX_CONCURRENT_TURNS = 2
    }
}
