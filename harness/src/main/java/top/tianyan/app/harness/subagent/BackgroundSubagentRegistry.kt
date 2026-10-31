package top.tianyan.app.harness.subagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.harness.PendingMessage
import top.tianyan.app.harness.backgroundCompletionText
import top.tianyan.app.harness.queue.PromptQueue
import top.tianyan.app.harness.queue.PromptQueueManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 后台子代理批次注册表（借鉴 DSH continuable subagent 的启动-收据-回传骨架）。
 *
 * background=true 的 invoke_subagent 不再拒绝：批次在进程级 scope 独立运行（与主轮
 * 解耦，不锁父会话），立即向模型返回启动收据；完成后把汇总作为 STEER 消息入队
 * 父会话——正在运行的轮次在下一步前消费，空闲会话等用户下次输入时触发推理。
 *
 * 保活与恢复边界（让步 C10）：批次启动即拉起前台服务（由 FGS 侧感知 [hasActive]
 * 延长保活），但进程被杀后批次协程不自动恢复——Lane 对话树持久，可用 task_id
 * 续跑（数据保留即 continuable 的"seed"语义；调度身份恢复让步）。
 */
class BackgroundSubagentRegistry(
    private val promptQueueManager: PromptQueueManager,
    private val logger: AppLogger,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, MutableSet<Job>>()

    /** 活跃批次的原子计数：FGS 判定高频读取，计数器避免与完成回调的遍历竞态。 */
    private val activeCount = AtomicInteger(0)

    /** 是否有正在运行的后台子代理批次（FGS 保活判定用）。 */
    val hasActive: Boolean get() = activeCount.get() > 0

    fun activeCountForSession(sessionId: String): Int =
        jobs[sessionId]?.count { it.isActive } ?: 0

    /**
     * 启动后台批次。[block] 是完整的批次执行（SubagentOrchestrator.runBatch），
     * 以 lambda 注入避免 registry ↔ orchestrator 循环依赖；完成后投递汇总。
     */
    fun launch(parentSessionId: String, taskCount: Int, block: suspend () -> Pair<Boolean, String>) {
        activeCount.incrementAndGet()
        val job = scope.launch {
            val (allSucceeded, summary) = try {
                block()
            } catch (cancelled: CancellationException) {
                logger.logAgent(parentSessionId, "BackgroundSubagentBatch", "cancelled")
                throw cancelled
            } catch (failure: Throwable) {
                logger.logAgent(parentSessionId, "BackgroundSubagentBatch", "crashed: ${failure.message}")
                false to "后台子任务批次异常（${failure::class.simpleName ?: "Throwable"}）：${failure.message ?: "无错误消息"}"
            }
            promptQueueManager.enqueue(
                parentSessionId,
                PromptQueue.STEER,
                PendingMessage(text = backgroundCompletionText(allSucceeded, summary)),
            )
            logger.logAgent(
                parentSessionId,
                "BackgroundSubagentBatch",
                "completed, tasks=$taskCount, allSucceeded=$allSucceeded, summaryChars=${summary.length}",
            )
        }
        jobs.getOrPut(parentSessionId) { ConcurrentHashMap.newKeySet() }.add(job)
        job.invokeOnCompletion {
            jobs[parentSessionId]?.remove(job)
            activeCount.decrementAndGet()
        }
    }

    /** 会话删除时取消其全部后台批次（防写已删会话）；主轮取消不影响后台批次。 */
    fun cancelForSession(sessionId: String) {
        jobs.remove(sessionId)?.forEach { it.cancel() }
    }
}
