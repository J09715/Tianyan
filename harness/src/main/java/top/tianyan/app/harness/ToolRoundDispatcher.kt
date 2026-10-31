package top.tianyan.app.harness

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * 单回合多工具调用的受限并发调度器。
 *
 * 约束：
 * - 连续的 [isParallelSafe] 工具在 [parallelism] 个许可内并发执行；其他工具形成顺序屏障，
 *   等前一组全部完成后执行，完成后才启动下一组；
 * - 需要工作区锁的工具（写文件/下载/MCP 等）按 mutationScope（工作区）互斥。
 *   base 保持回合内顺序，但不持有跨会话工作区锁，避免长下载阻塞其他会话的命令；
 *   已识别的重型命令由运行时资源调度器另行排队，不保证任意 Shell 写入互斥。
 *   审批恢复（resolveApproval）执行被批准的变更工具也经 [withMutationLock] 走同一把锁；
 *   子智能体不取工作区锁（[needsMutationLock] 对其恒为 false），而是按声明的 write_paths
 *   参与并发组：只有与组内已有成员两两不冲突（见 [conflicts]）才并入同一组，
 *   写路径相交或整工作区独占（["*"]）即断组，退化为顺序屏障。
 * - 任一工具触发审批暂停（[Pause.abort]）后，尚未开始的工具不再启动，在途工具自然跑完，
 *   与原串行"中途暂停、后续调用不执行"的语义保持一致；
 * - 取消沿结构化并发传播：外层 Job 被取消时，所有在途工具被打断并向上抛出
 *   CancellationException，由 HarnessLoop 的悬空调用修复逻辑收尾。
 */
class ToolRoundDispatcher() {
    /** 工作区（或语义等价的 scope key）→ 互斥锁；blank key 兜底为全局单锁。 */
    private class MutationLock(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val mutationMutexes = ConcurrentHashMap<String, MutationLock>()

    /**
     * 按工作区串行执行变更类副作用。除本调度器外，审批恢复路径（被批准的
     * write/download/mcp 等）也必须经此方法取锁，否则会与并发会话的同工作区写入踩踏。
     */
    suspend fun <T> withMutationLock(scopeKey: String, block: suspend () -> T): T {
        val key = scopeKey.trim().ifBlank { GLOBAL_SCOPE }
        val holder = mutationMutexes.compute(key) { _, current ->
            (current ?: MutationLock()).also { it.users++ }
        }!!
        try {
            return holder.mutex.withLock { block() }
        } finally {
            // Retain waiting callers too, so reclamation cannot create a second live lock.
            mutationMutexes.compute(key) { _, current ->
                if (current !== holder) current else holder.takeIf { --it.users > 0 }
            }
        }
    }

    internal val retainedMutationScopeCount: Int get() = mutationMutexes.size

    /** Shared by lane effects and approval replay; orchestration never holds a parent lock. */
    internal suspend fun <T> withToolLock(tool: HarnessTool, scopeKey: String, block: suspend () -> T): T =
        if (ToolSchedulingPolicy.needsMutationLock(tool)) withMutationLock(scopeKey, block) else block()

    class Pause private constructor() {
        private val aborted = AtomicBoolean(false)
        fun abort() { aborted.set(true) }
        fun isAborted(): Boolean = aborted.get()

        companion object {
            fun create(): Pause = Pause()
        }
    }

    suspend fun <T> dispatch(
        items: List<T>,
        parallelism: Int = DEFAULT_PARALLELISM,
        mutationScope: String = "",
        isParallelSafe: (T) -> Boolean,
        needsMutationLock: (T) -> Boolean = { !isParallelSafe(it) },
        /** 组内两两判定：返回 true 时后一个调用断组，形成顺序屏障。 */
        conflicts: (T, T) -> Boolean = { _, _ -> false },
        run: suspend (T, Pause) -> Unit,
    ) {
        if (items.isEmpty()) return
        val pause = Pause.create()
        val permits = Semaphore(parallelism.coerceAtLeast(1))
        suspend fun execute(item: T) {
            if (pause.isAborted()) return
            if (needsMutationLock(item)) {
                withMutationLock(mutationScope) {
                    if (!pause.isAborted()) run(item, pause)
                }
            } else run(item, pause)
        }
        var cursor = 0
        while (cursor < items.size && !pause.isAborted()) {
            if (!isParallelSafe(items[cursor]) || parallelism <= 1) {
                execute(items[cursor++])
                continue
            }
            val start = cursor++
            // 并发组成员必须两两可并行：写路径相交（或任一方独占整工作区）的调用在此断组，
            // 形成一个顺序屏障，等前面这组全部落库后再启动。
            while (
                cursor < items.size &&
                isParallelSafe(items[cursor]) &&
                (start until cursor).none { earlier -> conflicts(items[earlier], items[cursor]) }
            ) {
                cursor++
            }
            val end = cursor
            // Await the entire group, including durable result publication, before the barrier.
            coroutineScope {
                val group = this
                for (index in start until end) {
                    // FIFO permits keep ordered intent publication free of gaps.
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            permits.withPermit { execute(items[index]) }
                        } catch (cancelled: CancellationException) {
                            // Child cancellation alone does not cancel siblings waiting on publication.
                            group.cancel("Tool group cancelled", cancelled)
                            throw cancelled
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val DEFAULT_PARALLELISM = 4
        private const val GLOBAL_SCOPE = "<global>"
    }
}
