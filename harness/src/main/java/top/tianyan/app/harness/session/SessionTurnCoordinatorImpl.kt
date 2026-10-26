package top.tianyan.app.harness.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.datastore.AgentPreferences
import kotlin.coroutines.cancellation.CancellationException

/**
 * [SessionTurnCoordinator] 的生产级实现。
 *
 * ## 架构分层防死锁与防假死设计
 * 1. **双锁分离 (Dual-Mutex Design)**：
 *    - `stateMutex`：微秒级短命互斥锁，仅用于保护会话队列修改（入队/出队）、任务状态机、取消标记判定等短临界区。
 *      即使长耗时 Agent Turn 正在执行中，其他协程仍能随时获得 `stateMutex` 立即将后续消息加入队列或发出 `cancel` 信号。
 *    - `turnMutex`：长周期物理互斥锁，确保同一会话内物理上绝对只有一个 Turn 在调用 LLM 与执行沙箱工具。
 * 2. **Cross-Session 动态优先级门控 (Priority-Aware Dynamic Concurrency Gate)**：
 *    内置 [GlobalTurnLimiter]，精确维护在途 Turn 总数。当用户通过 [AgentPreferences] 动态调整并发上限时，
 *    平滑扩展或收缩，绝不丢弃在途计数；同时支持审批恢复/用户打断（HIGH）优先排队插队。
 * 3. **同步引用计数生命周期回收 (Synchronous Reference-Counted Cleanup)**：
 *    锁字典的管理与引用计数在同步监控器保护下执行，无挂起与协程调度开销，在 `finally` 块中 100% 免疫
 *    [CancellationException]，彻底杜绝锁对象内存泄漏。
 */
class SessionTurnCoordinatorImpl(
    private val preferences: AgentPreferences? = null,
    private val logger: AppLogger? = null,
    initialMaxConcurrentTurns: Int = SessionTurnCoordinator.DEFAULT_MAX_CONCURRENT_TURNS,
) : SessionTurnCoordinator {

    private val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val entriesLock = Any()
    private val entries = mutableMapOf<String, SessionEntry>()

    private val _activeTurnCount = MutableStateFlow(0)
    override val activeTurnCount: StateFlow<Int> = _activeTurnCount.asStateFlow()

    private val _sessionSchedulingStates = MutableStateFlow<Map<String, TurnSchedulingState>>(emptyMap())
    override val sessionSchedulingStates: StateFlow<Map<String, TurnSchedulingState>> =
        _sessionSchedulingStates.asStateFlow()

    private val globalLimiter = GlobalTurnLimiter(initialMaxConcurrentTurns) { count ->
        _activeTurnCount.value = count
    }

    init {
        preferences?.let { prefs ->
            coordinatorScope.launch {
                prefs.maxConcurrentAgentTurns.collectLatest { configuredLimit ->
                    // 上限与 SettingsDataStore.setMaxConcurrentAgentTurns 的写入口径 (1..4) 保持一致
                    val safeLimit = configuredLimit.coerceIn(1, 4)
                    logger?.i("SessionTurnCoordinator: reconfiguring max concurrent turns to $safeLimit")
                    globalLimiter.setLimit(safeLimit)
                }
            }
        }
    }

    override suspend fun <T> withSessionTurn(
        sessionId: String,
        priority: TurnPriority,
        block: suspend () -> T,
    ): T {
        val key = normalizeSessionId(sessionId)
        val entry = acquireEntry(key)
        val queuePos = synchronized(entry) {
            entry.queuedTurns++
            entry.queuedTurns
        }
        if (queuePos > 1) {
            updateState(key, TurnSchedulingState.QueuedInSession(queuePos - 1))
        }
        try {
            return entry.turnMutex.withLock {
                updateState(key, TurnSchedulingState.WaitingGlobalSlot())
                globalLimiter.acquire(priority)
                try {
                    updateState(key, TurnSchedulingState.Executing(System.currentTimeMillis(), priority))
                    block()
                } finally {
                    globalLimiter.release()
                }
            }
        } finally {
            val remainingQueued = synchronized(entry) {
                entry.queuedTurns = (entry.queuedTurns - 1).coerceAtLeast(0)
                entry.queuedTurns
            }
            if (remainingQueued == 0) {
                updateState(key, TurnSchedulingState.Idle)
            }
            releaseEntry(key, entry)
        }
    }

    override suspend fun <T> withSessionMutex(
        sessionId: String,
        block: suspend () -> T,
    ): T {
        val key = normalizeSessionId(sessionId)
        val entry = acquireEntry(key)
        try {
            return entry.stateMutex.withLock {
                block()
            }
        } finally {
            releaseEntry(key, entry)
        }
    }

    override fun evictSession(sessionId: String) {
        val key = normalizeSessionId(sessionId)
        synchronized(entriesLock) {
            entries[key]?.let { entry ->
                // 存活在途持锁协程时禁止强拆 entry：remove 会让复活会话通过 getOrPut
                // 造出第二把 turnMutex，同会话串行化被打破。留给 releaseEntry 在引用归零时移除。
                if (entry.references <= 0) {
                    entries.remove(key)
                }
            }
        }
        updateState(key, TurnSchedulingState.Idle)
    }

    private fun acquireEntry(sessionId: String): SessionEntry {
        return synchronized(entriesLock) {
            entries.getOrPut(sessionId) { SessionEntry() }
                .also { it.references += 1 }
        }
    }

    private fun releaseEntry(sessionId: String, entry: SessionEntry) {
        synchronized(entriesLock) {
            entry.references = (entry.references - 1).coerceAtLeast(0)
            if (entry.references <= 0) {
                entries.remove(sessionId)
            }
        }
    }

    private fun normalizeSessionId(sessionId: String): String {
        return sessionId.trim().ifBlank { "default-session" }
    }

    private fun updateState(sessionId: String, state: TurnSchedulingState) {
        synchronized(this) {
            val currentMap = _sessionSchedulingStates.value.toMutableMap()
            if (state is TurnSchedulingState.Idle) {
                currentMap.remove(sessionId)
            } else {
                currentMap[sessionId] = state
            }
            _sessionSchedulingStates.value = currentMap
        }
    }

    private class SessionEntry {
        /** 保护队列修改、状态机读写、取消判定等微秒级轻量操作 */
        val stateMutex = Mutex()
        /** 保护同一会话物理上只有一个 Turn 正在执行 */
        val turnMutex = Mutex()
        var references: Int = 0
        var queuedTurns: Int = 0
    }

    /**
     * 具备动态配额调节与优先级调度的全局并发门控 (Priority-Aware Dynamic Concurrency Gate)。
     */
    private class GlobalTurnLimiter(
        initialLimit: Int,
        private val onActiveCountChanged: (Int) -> Unit = {},
    ) {
        private var currentLimit = initialLimit.coerceAtLeast(1)
        private var runningCount = 0

        private class Waiter(
            val priority: TurnPriority,
            val deferred: CompletableDeferred<Unit> = CompletableDeferred(),
        )

        private val waiters = mutableListOf<Waiter>()

        suspend fun acquire(priority: TurnPriority) {
            val waiter = synchronized(this) {
                if (runningCount < currentLimit && waiters.isEmpty()) {
                    runningCount++
                    onActiveCountChanged(runningCount)
                    return
                }
                val w = Waiter(priority)
                // 优先级排序：HIGH (ordinal 0) 优先于 NORMAL (ordinal 1) 优先于 BACKGROUND (ordinal 2)
                val insertIndex = waiters.indexOfFirst { it.priority.ordinal > priority.ordinal }
                if (insertIndex >= 0) {
                    waiters.add(insertIndex, w)
                } else {
                    waiters.add(w)
                }
                w
            }

            try {
                waiter.deferred.await()
            } catch (e: CancellationException) {
                synchronized(this) {
                    if (!waiter.deferred.isCompleted) {
                        waiters.remove(waiter)
                    } else {
                        // 恰好在取消瞬间被唤醒并获得了槽位，必须转交释放给下一个排队者
                        releaseLocked()
                    }
                }
                throw e
            }
        }

        fun release() {
            synchronized(this) {
                releaseLocked()
            }
        }

        fun setLimit(newLimit: Int) {
            synchronized(this) {
                currentLimit = newLimit.coerceAtLeast(1)
                drainWaitersLocked()
            }
        }

        private fun releaseLocked() {
            runningCount = (runningCount - 1).coerceAtLeast(0)
            onActiveCountChanged(runningCount)
            drainWaitersLocked()
        }

        private fun drainWaitersLocked() {
            while (runningCount < currentLimit && waiters.isNotEmpty()) {
                val next = waiters.removeAt(0)
                if (next.deferred.complete(Unit)) {
                    runningCount++
                    onActiveCountChanged(runningCount)
                }
            }
        }
    }
}
