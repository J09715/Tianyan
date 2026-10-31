package top.tianyan.app.harness

import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import top.tianyan.app.harness.core.ToolCheckpoints

/** The supplied executor retains all approval controls; extensions can veto or append sanitized text. */
internal class ToolExecutionBoundary(
    private val checkpoints: ToolCheckpoints<ToolExecutionRequest, ToolResult>,
    private val formatOutput: suspend (ToolExecutionRequest, String) -> String,
) {
    suspend fun execute(request: ToolExecutionRequest, executeWithPolicy: suspend () -> ToolResult): ToolResult {
        val observation = request.observationSnapshot()
        val blocked = checkpoints.before(observation)
        val result = if (blocked != null) {
            ToolResult(
                id = UUID.randomUUID().toString(), createdAt = System.currentTimeMillis(),
                toolCallId = request.call.id, success = false,
                output = "工具扩展 ${blocked.checkpointId} 已阻止本次调用：${blocked.reason}",
                metadata = mapOf("tool_checkpoint" to blocked.checkpointId),
                errorCode = "TOOL_CHECKPOINT_BLOCKED",
            )
        } else executeWithPolicy()
        // Detach mutable collections. Only the returned notes affect the actual result.
        val notes = checkpoints.after(observation, result.copy(
            metadata = Collections.unmodifiableMap(HashMap(result.metadata)),
            imageAttachments = Collections.unmodifiableList(ArrayList(result.imageAttachments)),
        ))
        if (blocked == null && notes.isEmpty()) return result
        val annotated = result.output + notes.joinToString("") { "\n\n【工具扩展 ${it.checkpointId}】\n${it.text}" }
        return try {
            val output = formatOutput(observation, annotated)
            currentCoroutineContext().ensureActive()
            result.copy(output = output)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            currentCoroutineContext().ensureActive()
            // Discard unsanitized notes; an annotation failure cannot change effect/control state.
            result.copy(output = if (blocked != null) "工具扩展已阻止本次调用，扩展说明处理失败。"
                else result.output + "\n\n工具扩展说明处理失败，工具结果状态保持不变。")
        }
    }
}
