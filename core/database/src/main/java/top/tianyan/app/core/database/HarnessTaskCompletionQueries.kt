package top.tianyan.app.core.database

import androidx.room.Query

/** Completion CAS is scoped to the task's current operation and owning session. */
interface HarnessTaskCompletionQueries {
    @Query("""
        UPDATE agent_tasks SET status = 'COMPLETED', completedAt = :now, updatedAt = :now,
            progress = 1.0, statusDetail = '已完成', errorMessage = NULL, nextRunAt = NULL
        WHERE id = :taskId AND sessionId = :sessionId AND operationId = :operationId
            AND status IN ('RUNNING', 'RECOVERING', 'WAITING_APPROVAL')
    """)
    suspend fun completeTaskRecord(taskId: String, sessionId: String, operationId: String, now: Long): Int
}
