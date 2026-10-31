package top.tianyan.app.harness.workflow

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import top.tianyan.app.core.database.WorkflowRepository
import top.tianyan.app.core.database.WorkflowScheduleEntity
import top.tianyan.app.core.model.workflow.MorningReportSentinel
import top.tianyan.app.core.model.workflow.WorkflowScheduleRepeat

/**
 * 晨报哨兵的派生状态（由定时计划表映射而来）。
 * 乾坤「特色功能」页内的晨报哨兵控制与定时计划共用同一套状态与操作。
 */
data class SentinelState(
    val enabled: Boolean = false,
    val hour: Int = DEFAULT_HOUR,
    val minute: Int = 0,
    val nextRunAt: Long? = null,
    val lastRunAt: Long? = null,
) {
    companion object {
        const val DEFAULT_HOUR = 8
    }
}

/** 晨报哨兵的定时计划流（过滤出固定 workflowId 的唯一计划）。 */
private fun WorkflowScheduleRepository.sentinelScheduleFlow(): Flow<WorkflowScheduleEntity?> =
    observeSchedules().map { schedules -> schedules.firstOrNull { it.workflowId == MorningReportSentinel.WORKFLOW_ID } }

/** 在给定协程作用域内持续观察哨兵状态。 */
fun CoroutineScope.observeSentinelState(
    scheduleRepository: WorkflowScheduleRepository,
    onState: (SentinelState) -> Unit,
) {
    launch {
        scheduleRepository.sentinelScheduleFlow().collect { entity ->
            onState(
                if (entity == null) {
                    SentinelState()
                } else {
                    SentinelState(
                        enabled = entity.enabled,
                        hour = entity.hour ?: SentinelState.DEFAULT_HOUR,
                        minute = entity.minute ?: 0,
                        nextRunAt = entity.nextRunAt,
                        lastRunAt = entity.lastRunAt,
                    )
                },
            )
        }
    }
}

/**
 * 开启晨报哨兵（或调整触发时间）。先确保内置工作流已落库（外键依赖），
 * 再以固定 DAILY 计划 upsert：不存在则新建，存在则保留原 id 只改时间/启用态。
 */
suspend fun enableSentinelSchedule(
    scheduleRepository: WorkflowScheduleRepository,
    workflowRepository: WorkflowRepository,
    hour: Int,
    minute: Int,
) {
    workflowRepository.ensureBuiltins()
    val existing = scheduleRepository.sentinelScheduleFlow().first()
    val entity = existing ?: WorkflowScheduleEntity(
        id = scheduleRepository.newScheduleId(),
        workflowId = MorningReportSentinel.WORKFLOW_ID,
        name = "晨报哨兵",
        enabled = false,
        repeatType = WorkflowScheduleRepeat.DAILY.name,
        hour = null,
        minute = null,
        intervalMinutes = null,
        onceAtEpochMillis = null,
        variablesJson = "{}",
        workspacePath = "/workspace",
        modelId = null,
        modelVariant = null,
        lastExecutionId = null,
        lastRunAt = null,
        nextRunAt = null,
        createdAt = System.currentTimeMillis(),
    )
    scheduleRepository.upsert(entity.copy(enabled = true, hour = hour.coerceIn(0, 23), minute = minute.coerceIn(0, 59)))
}

/** 关闭晨报哨兵：删除定时计划（WorkManager 注册随 dispatcher 联动取消）。 */
suspend fun disableSentinelSchedule(scheduleRepository: WorkflowScheduleRepository) {
    scheduleRepository.sentinelScheduleFlow().first()?.let { scheduleRepository.delete(it.id) }
}
