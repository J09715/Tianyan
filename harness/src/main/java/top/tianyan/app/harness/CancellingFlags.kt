package top.tianyan.app.harness

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 「取消中」会话标记。
 *
 * 原先是直接在 [HarnessLoop] 里用裸并发集合就地增删：置位点与唯一的摘除点被两次
 * `withSessionMutex` 划进了不同代码块，中间还夹着多次可失败的数据库写入与
 * `cancelAndJoin()`。任一步抛出、或协程在两次加锁之间被取消，标记就会永久驻留。
 * 此后 `isSessionBusy()` 恒为 true，会话表现为「分支无法切换、消息无法发送」，
 * 界面上分支卡片全部置灰且点击毫无反馈——现象与原因相隔太远，极难定位。
 *
 * 这里把标记的读、写与收尾收敛到一处：
 *  - [guarded]：置位块一旦失败就立刻摘除标记，从源头堵住泄漏；
 *  - [duringCancel]：整段取消（含第二阶段）跑完才摘除，协程被取消也不能提前摘标；
 *  - [isCancelling]：无存活任务时并不马上摘标。取消的第二阶段就发生在
 *    「任务已离开 sessionJobs、标记尚未摘除」的窗口里，立刻自愈会让
 *    `isSessionBusy()` 变 false，新的 run 被放行后又被第二阶段误标成已取消。
 *    只有标记在无任务状态下持续闲置超过 [STALE_AFTER_MS]，才当作泄漏清掉。
 */
internal class CancellingFlags(
    private val staleAfterMs: Long = STALE_AFTER_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val markedAt = ConcurrentHashMap<String, Long>()
    private val idleSince = ConcurrentHashMap<String, Long>()

    /** 置位。调用方须保证最终会有配对的 [clear]（或用 [guarded] / [duringCancel]）。 */
    fun mark(sessionId: String) {
        markedAt[sessionId] = now()
        idleSince.remove(sessionId)
    }

    /** 摘除。幂等。 */
    fun clear(sessionId: String) {
        markedAt.remove(sessionId)
        idleSince.remove(sessionId)
    }

    /** 只读判断，不做自愈。用于旁路统计等不应改变状态的场景。 */
    fun isSet(sessionId: String): Boolean = markedAt.containsKey(sessionId)

    /**
     * 是否会话正处于「取消中」。
     *
     * [hold] 为 true 表示仍有存活任务或待批审批，标记有效，照常视为取消中，
     * 并清掉闲置计时——长时间 `cancelAndJoin` 不能把标记算成陈旧泄漏。
     * [hold] 为 false 时标记仍然有效：第一次观察到「无任务」只是取消第二阶段
     * 正在进锁，立刻摘标会和那一阶段竞态。要连续闲置满 [staleAfterMs] 才摘除。
     */
    fun isCancelling(sessionId: String, hold: Boolean): Boolean {
        if (!markedAt.containsKey(sessionId)) return false
        if (hold) {
            idleSince.remove(sessionId)
            return true
        }
        val observedAt = now()
        val idleAt = idleSince.putIfAbsent(sessionId, observedAt)
        if (!markedAt.containsKey(sessionId)) {
            idleSince.remove(sessionId, observedAt)
            return false
        }
        if (idleAt == null) return true
        if (observedAt - idleAt < staleAfterMs) return true
        val marked = markedAt[sessionId] ?: return false
        if (markedAt.remove(sessionId, marked)) {
            idleSince.remove(sessionId, idleAt)
        }
        return markedAt.containsKey(sessionId)
    }

    /**
     * 先置位，再执行 [block]；块内抛出时立刻摘除标记后原样抛出。
     *
     * 这样置位块（清理队列、标记任务取消、获取会话互斥等）中途失败不会留下粘性标记。
     * 注意成功路径**不**摘除：标记要一直保留到取消的第二阶段收尾完成。
     */
    internal inline fun <T> guarded(sessionId: String, block: () -> T): T {
        mark(sessionId)
        return try {
            block()
        } catch (failure: Throwable) {
            clear(sessionId)
            throw failure
        }
    }

    /**
     * 跑完整段取消，返回后才摘除标记。
     *
     * [NonCancellable] 包住整段（含第二阶段的挂起点）：调用方协程被取消时，
     * 也不能在 `rejectPendingApprovals` / `markCancelled` 之前摘标。提前摘标会让
     * `isSessionBusy()` 放行新 run，第二阶段再把那个新 run 误标成已取消。
     * 这里的 [clear] 是泄漏兜底，且幂等。启动后继 run 之前调用方仍要自己摘标：
     * `finishRun` 看到标记还在会跳过队列排空，不能等到本函数返回。
     */
    internal suspend inline fun <T> duringCancel(
        sessionId: String,
        crossinline block: suspend () -> T,
    ): T = withContext(NonCancellable) {
        try {
            block()
        } finally {
            clear(sessionId)
        }
    }

    companion object {
        /** 无任务闲置达到此时长才把标记当泄漏清掉。远长于取消第二阶段进锁的窗口。 */
        const val STALE_AFTER_MS = 30_000L
    }
}
