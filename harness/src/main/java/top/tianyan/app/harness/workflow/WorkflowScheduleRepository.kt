package top.tianyan.app.harness.workflow

import java.util.Calendar
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import top.tianyan.app.core.database.WorkflowRepository
import top.tianyan.app.core.database.WorkflowScheduleEntity
import top.tianyan.app.core.database.WorkflowScheduleStore
import top.tianyan.app.core.model.workflow.WorkflowDefinition
import top.tianyan.app.core.model.workflow.WorkflowScheduleRepeat

/**
 * 定时计划到点后的执行派发接口。harness 不依赖 WorkManager；
 * app 模块实现为 WorkManager UniqueWork 注册（进程被杀后到点仍会拉起执行）。
 */
interface WorkflowScheduleDispatcher {
    fun dispatch(entity: WorkflowScheduleEntity)
    fun cancel(scheduleId: String)
}

/**
 * workflow_schedules 的增删改查 + nextRunAt 计算。所有写操作都会联动 dispatcher，
 * 保证 Room 状态与 WorkManager 注册一致；enabled=false 的计划只保留配置不再触发。
 */
class WorkflowScheduleRepository(
    private val store: WorkflowScheduleStore,
    private val workflowRepository: WorkflowRepository,
    private val dispatcher: WorkflowScheduleDispatcher,
) {
    fun observeSchedules(): Flow<List<WorkflowScheduleEntity>> = store.observeSchedules()

    suspend fun findSchedule(id: String): WorkflowScheduleEntity? = store.findSchedule(id)

    suspend fun findDefinition(id: String): WorkflowDefinition? = workflowRepository.findById(id)

    suspend fun upsert(entity: WorkflowScheduleEntity) {
        val normalized = entity.copy(
            name = entity.name.trim().ifBlank { "定时计划" },
            intervalMinutes = entity.intervalMinutes?.coerceAtLeast(MIN_INTERVAL_MINUTES),
            nextRunAt = if (entity.enabled) computeNextRunAt(entity) else null,
        )
        store.upsert(normalized)
        if (normalized.enabled) {
            dispatcher.dispatch(normalized)
        } else {
            dispatcher.cancel(normalized.id)
        }
    }

    suspend fun delete(id: String) {
        store.delete(id)
        dispatcher.cancel(id)
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        val entity = store.findSchedule(id) ?: return
        val updated = entity.copy(
            enabled = enabled,
            nextRunAt = if (enabled) computeNextRunAt(entity) else null,
        )
        store.upsert(updated)
        if (enabled) {
            dispatcher.dispatch(updated)
        } else {
            dispatcher.cancel(id)
        }
    }

    suspend fun updateRunInfo(id: String, executionId: String, runAt: Long) {
        val entity = store.findSchedule(id) ?: return
        // ONCE 触发后自动停用；周期计划按当前时刻推算下一次
        val firedOnce = entity.repeatType == WorkflowScheduleRepeat.ONCE.name
        val next = if (firedOnce) null else computeNextRunAt(entity, from = runAt)
        store.updateRunInfo(id, executionId, runAt, next)
        if (firedOnce) {
            store.setEnabled(id, false)
            dispatcher.cancel(id)
        }
    }

    /** 下一次触发时刻（本地时区）。DAILY 取今天/明天的 hour:minute；INTERVAL 顺延；ONCE 取配置时刻。 */
    fun computeNextRunAt(entity: WorkflowScheduleEntity, from: Long = System.currentTimeMillis()): Long? =
        when (WorkflowScheduleRepeat.valueOf(entity.repeatType)) {
            WorkflowScheduleRepeat.DAILY -> {
                val hour = entity.hour ?: return null
                val minute = entity.minute ?: return null
                val calendar = Calendar.getInstance().apply {
                    timeInMillis = from
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (calendar.timeInMillis <= from) {
                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                }
                calendar.timeInMillis
            }
            WorkflowScheduleRepeat.INTERVAL -> {
                val interval = entity.intervalMinutes?.takeIf { it >= MIN_INTERVAL_MINUTES } ?: MIN_INTERVAL_MINUTES
                from + interval * 60_000L
            }
            WorkflowScheduleRepeat.ONCE -> entity.onceAtEpochMillis?.takeIf { it > from }
        }

    fun decodeVariables(variablesJson: String): Map<String, String> =
        runCatching {
            VARIABLES_JSON.decodeFromString<Map<String, String>>(variablesJson.ifBlank { "{}" })
        }.getOrDefault(emptyMap())

    fun newScheduleId(): String = "sched_${UUID.randomUUID().toString().take(12)}"

    companion object {
        /** WorkManager 周期任务下限 15 分钟。 */
        const val MIN_INTERVAL_MINUTES = 15
        private val VARIABLES_JSON = Json { ignoreUnknownKeys = true }
    }
}
