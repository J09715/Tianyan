package top.tianyan.app.workflow

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import top.tianyan.app.core.model.workflow.WorkflowRunTrigger
import top.tianyan.app.core.model.workflow.WorkflowScheduleRepeat
import top.tianyan.app.harness.workflow.WorkflowRunManager
import top.tianyan.app.harness.workflow.WorkflowScheduleRepository
import top.tianyan.app.service.WorkflowForegroundService

/**
 * 定时计划到点执行：读计划 → 取定义 → 交给 WorkflowRunManager 启动（trigger=SCHEDULE）。
 * 运行启动后 Worker 立即返回成功；存活由前台服务接管（Application 联动启动）。
 * 运行时未就绪（如从未安装沙箱）会落一条 FAILED 历史并通知用户，绝不自动下载 RootFS。
 */
class WorkflowScheduleWorker(
    appContext: Context,
    params: WorkerParameters,
    private val scheduleRepository: WorkflowScheduleRepository,
    private val runManager: WorkflowRunManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val scheduleId = inputData.getString(KEY_SCHEDULE_ID)
        if (scheduleId == null) {
            Log.w(TAG, "缺少 scheduleId 输入，丢弃本次触发")
            return Result.failure()
        }
        val schedule = scheduleRepository.findSchedule(scheduleId)
            ?: return Result.failure().also { Log.w(TAG, "计划不存在（可能已删除）：$scheduleId") }
        if (!schedule.enabled) return Result.success()

        val definition = scheduleRepository.findDefinition(schedule.workflowId)
        if (definition == null) {
            Log.w(TAG, "工作流定义缺失：${schedule.workflowId}")
            return Result.failure()
        }

        val result = runManager.start(
            definition = definition,
            variables = scheduleRepository.decodeVariables(schedule.variablesJson),
            workspacePath = schedule.workspacePath.ifBlank { "/workspace" },
            trigger = WorkflowRunTrigger.Schedule(scheduleId),
        )
        if (!result.accepted) {
            // 启动失败（典型：awaitRuntimeReady 超时——刚开机/沙箱未就绪）**不能**当成「已执行」。
            // updateRunInfo 会把 ONCE 计划 setEnabled(false) + cancel，若此处照样调用，
            // 一次性计划会在一次失败后被永久停用且不再重试，而用户只会看到计划「消失」。
            // 故失败时只记日志、不推进 nextRunAt；由 WorkManager 按重试策略再次投递。
            Log.w(TAG, "定时计划 ${schedule.name} 启动失败：${result.failureReason}（保留下次触发，不回写运行信息）")
            // Result.retry() 无上限（退避最长可达 5 小时），沙箱长期未就绪时会无限重试、反复落失败日志。
            // 触顶后：ONCE 计划自动停用（UI 可见、可手动重启），周期计划保持启用、由下个周期自愈。
            if (runAttemptCount >= MAX_START_RETRIES) {
                if (schedule.repeatType == WorkflowScheduleRepeat.ONCE.name) {
                    Log.e(TAG, "定时计划 ${schedule.name} 重试 $MAX_START_RETRIES 次仍未启动成功，自动停用")
                    scheduleRepository.setEnabled(scheduleId, false)
                }
                return Result.failure()
            }
            return Result.retry()
        }
        scheduleRepository.updateRunInfo(scheduleId, result.executionId, System.currentTimeMillis())
        // 双保险：Application 的 running 联动可能慢于 Worker 结束，这里直接拉起前台保活
        WorkflowForegroundService.start(applicationContext)
        return Result.success()
    }

    companion object {
        const val KEY_SCHEDULE_ID = "key_schedule_id"
        /** 启动失败重试上限（不含首次；默认指数退避 30s 起，共 4 次尝试窗口约 3.5 分钟）。 */
        private const val MAX_START_RETRIES = 3
        private const val TAG = "WorkflowScheduleWorker"
    }
}
