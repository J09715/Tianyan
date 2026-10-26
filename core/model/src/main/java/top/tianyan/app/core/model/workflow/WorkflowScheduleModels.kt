package top.tianyan.app.core.model.workflow

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 定时计划的重复语义：DAILY=每天固定时刻；INTERVAL=每 N 分钟（WorkManager 周期下限 15 分钟）；ONCE=一次性延时。 */
enum class WorkflowScheduleRepeat { DAILY, INTERVAL, ONCE }

/** 一次工作流运行的触发来源：用户手动启动，或定时计划到点拉起。 */
@Serializable
sealed class WorkflowRunTrigger {
    @Serializable
    @SerialName("manual")
    data object Manual : WorkflowRunTrigger()

    @Serializable
    @SerialName("schedule")
    data class Schedule(val scheduleId: String) : WorkflowRunTrigger()
}

val WorkflowRunTrigger.triggerSource: String
    get() = if (this is WorkflowRunTrigger.Schedule) "SCHEDULE" else "MANUAL"
