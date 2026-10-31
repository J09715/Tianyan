package top.tianyan.app.runtime.shell

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** One per local runtime: known memory-heavy foreground workloads share a FIFO, cancellable slot. */
internal class ForegroundCommandAdmission(private val nanoTime: () -> Long = System::nanoTime) {
    private val memorySlot = Semaphore(1)

    suspend fun execute(command: ShellCommand, executor: ShellExecutor, run: suspend () -> CommandResult): CommandResult {
        if (!CommandResourcePolicy.needsMemorySlot(command)) return command.withOutputCapture(run)
        val queuedAt = nanoTime()
        return memorySlot.withPermit {
            currentCoroutineContext().ensureActive()
            retryRetainedTeardown(executor)
            check(!executor.hasUnsettled()) { "前台进程清理尚未完成，暂不能启动新的重型命令；先结束残留进程或重试运行时清理。" }
            val waitMs = ((nanoTime() - queuedAt) / 1_000_000).coerceAtLeast(0)
            // Command timeout and private output capture start only after admission.
            command.withOutputCapture(run).let { it.copy(resourceWaitMs = it.resourceWaitMs + waitMs) }
        }
    }

    /**
     * 上一轮收尾失败会让名额一直滞留（[ShellExecutor.hasUnsettled]），此前只能等用户手动触发
     * 运行时清理。这里在每次重型命令准入前先自愈重试一次：残留进程若已消亡，[ShellExecutor.closePending]
     * 会成功并释放名额；仍收尾不了才由下方 check 拒绝本次命令（保持内存安全）。
     */
    private suspend fun retryRetainedTeardown(executor: ShellExecutor) {
        if (!executor.hasUnsettled()) return
        try {
            executor.closePending()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // 仍无法收尾，交给 check 判定
        }
    }
}
