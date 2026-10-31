package top.tianyan.app.harness

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import top.tianyan.app.harness.task.TaskDeliveryReport

/** UI transcript record; excluded from provider context and compaction prompts. */
@Serializable
@SerialName("task_delivery")
data class TaskDelivery(
    override val id: String,
    override val createdAt: Long,
    val report: TaskDeliveryReport,
) : HarnessMessage {
    /** Plain conversation still has a durable record, without adding an empty work card to the chat. */
    val hasExecutionDetails: Boolean get() = report.observations.isNotEmpty() || report.artifacts.isNotEmpty() || !report.historyComplete
}
