package top.tianyan.app.harness.plugins

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.tianyan.app.core.model.bot.BotAssignment
import top.tianyan.app.core.model.bot.BotAssignmentStatus
import top.tianyan.app.harness.bot.BotAssignmentTracker
import top.tianyan.app.harness.groupchat.GroupTurnOutcome

/** 先持久化受理，再执行；完成状态落盘后才允许唤醒主控检查依赖。 */
/** 跟踪一次群聊前台委派：成员在自己的会话里执行，任务状态与完成回传由这里收口。 */
internal suspend fun CoroutineScope.launchBotAssignment(
    tracker: BotAssignmentTracker,
    assignment: BotAssignment,
    run: suspend () -> GroupTurnOutcome,
    report: suspend (String, Boolean) -> Unit,
) {
    val job = launch(start = CoroutineStart.LAZY) {
        var status = BotAssignmentStatus.FAILED
        var summary: String? = null
        var error: String? = null
        var notification = ""
        try {
            val result = run()
            summary = result.text
            status = if (result.failed) BotAssignmentStatus.FAILED else BotAssignmentStatus.COMPLETED
            if (result.failed) error = "成员执行未成功"
            notification = formatAssignmentReport(assignment, result)
        } catch (cancelled: CancellationException) {
            status = BotAssignmentStatus.CANCELLED
            error = "已取消"
            notification = "🛑 **${assignment.botName}** 的任务已取消（任务 ${assignment.id}）"
            throw cancelled
        } catch (failure: Throwable) {
            error = failure.message ?: "未知错误"
            notification = "⚠️ **${assignment.botName}** 的委派执行失败（任务 ${assignment.id}）：$error"
        } finally {
            withContext(NonCancellable) {
                tracker.finish(assignment.id, status, summary, error)
                report(notification, status != BotAssignmentStatus.CANCELLED)
            }
        }
    }
    try {
        tracker.start(assignment, job)
    } catch (failure: Throwable) {
        job.cancel()
        throw failure
    }
    job.start()
}
