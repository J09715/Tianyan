package top.tianyan.app.workflow

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.Calendar
import java.util.concurrent.TimeUnit
import top.tianyan.app.core.database.WorkflowScheduleEntity
import top.tianyan.app.core.model.workflow.WorkflowScheduleRepeat
import top.tianyan.app.harness.workflow.WorkflowScheduleDispatcher
import top.tianyan.app.harness.workflow.WorkflowScheduleRepository

/**
 * WorkManager 实现：UniqueWork 以 scheduleId 命名，进程被杀/设备重启后由系统自动
 * 恢复排队。DAILY 用 24h 周期 + 到点 initialDelay（Doze 下可能有分钟级顺延）；
 * INTERVAL 用 N 分钟周期（下限 15 分钟）；ONCE 用一次性延时任务。
 */
class WorkManagerScheduleDispatcher(
    private val context: Context,
) : WorkflowScheduleDispatcher {

    override fun dispatch(entity: WorkflowScheduleEntity) {
        if (!entity.enabled) return
        val workManager = WorkManager.getInstance(context)
        val uniqueName = uniqueWorkName(entity.id)
        val inputData = workDataOf(WorkflowScheduleWorker.KEY_SCHEDULE_ID to entity.id)
        when (WorkflowScheduleRepeat.valueOf(entity.repeatType)) {
            WorkflowScheduleRepeat.DAILY -> {
                val hour = entity.hour ?: return
                val minute = entity.minute ?: return
                val request = PeriodicWorkRequestBuilder<WorkflowScheduleWorker>(24, TimeUnit.HOURS)
                    .setInitialDelay(nextDailyDelayMs(hour, minute), TimeUnit.MILLISECONDS)
                    .setInputData(inputData)
                    .addTag(TAG)
                    .build()
                workManager.enqueueUniquePeriodicWork(uniqueName, ExistingPeriodicWorkPolicy.UPDATE, request)
            }
            WorkflowScheduleRepeat.INTERVAL -> {
                val interval = (entity.intervalMinutes ?: WorkflowScheduleRepository.MIN_INTERVAL_MINUTES)
                    .coerceAtLeast(WorkflowScheduleRepository.MIN_INTERVAL_MINUTES)
                val request = PeriodicWorkRequestBuilder<WorkflowScheduleWorker>(interval.toLong(), TimeUnit.MINUTES)
                    .setInputData(inputData)
                    .addTag(TAG)
                    .build()
                workManager.enqueueUniquePeriodicWork(uniqueName, ExistingPeriodicWorkPolicy.UPDATE, request)
            }
            WorkflowScheduleRepeat.ONCE -> {
                val target = entity.onceAtEpochMillis ?: return
                val delay = (target - System.currentTimeMillis()).coerceAtLeast(0L)
                val request = OneTimeWorkRequestBuilder<WorkflowScheduleWorker>()
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setInputData(inputData)
                    .addTag(TAG)
                    .build()
                workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)
            }
        }
    }

    override fun cancel(scheduleId: String) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(scheduleId)) }
    }

    /** 距下一个 hour:minute（本地时区）的毫秒数。 */
    private fun nextDailyDelayMs(hour: Int, minute: Int): Long {
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (calendar.timeInMillis <= now) calendar.add(Calendar.DAY_OF_YEAR, 1)
        return calendar.timeInMillis - now
    }

    private fun uniqueWorkName(scheduleId: String) = "workflow_schedule_$scheduleId"

    object ScheduleDispatcherModule {
        fun provideDispatcher(context: Context): WorkflowScheduleDispatcher =
            WorkManagerScheduleDispatcher(context)
    }

    private companion object {
        const val TAG = "workflow_schedule"
    }
}
