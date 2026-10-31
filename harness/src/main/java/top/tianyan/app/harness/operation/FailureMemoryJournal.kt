package top.tianyan.app.harness.operation

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import top.tianyan.app.core.database.HarnessOperationEntity
import top.tianyan.app.core.database.HarnessRuntimeRepository
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolResult

/** Memory is stored by the caller in the same transaction as the observed ToolResult. */
internal suspend fun failureMemoryAfterSettlement(repository: HarnessRuntimeRepository, json: Json,
    operation: HarnessOperationEntity, message: HarnessMessage?, now: Long): FailureMemoryState {
    val previous = try { json.decodeFromString(OperationSnapshot.serializer(), operation.stateJson).failureMemory }
        catch (_: SerializationException) { null }
    val result = message as? ToolResult
    val source = result?.let { repository.findEntry(operation.sessionId, it.toolCallId) }
    val call = source?.takeIf { it.sessionId == operation.sessionId && it.entryType == "message" }?.let {
        try { json.decodeFromString(HarnessMessage.serializer(), it.payloadJson) as? ToolCall }
        catch (_: SerializationException) { null }
    }
    return FailureMemory.update(previous, operation.id, call, result, now)
}
