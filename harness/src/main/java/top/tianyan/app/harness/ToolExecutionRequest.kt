package top.tianyan.app.harness

import java.util.Collections
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Read-only checkpoint context. Approval grants and execution controls are not extension inputs. */
data class ToolExecutionRequest(
    val call: ToolCall,
    val sessionId: String,
    val workspace: String,
    val operationId: String?,
)

/** Json containers delegate to caller collections, so copy and freeze every container. */
internal fun ToolExecutionRequest.observationSnapshot(): ToolExecutionRequest =
    copy(call = call.copy(args = call.args.readOnlySnapshot() as JsonObject))

private fun JsonElement.readOnlySnapshot(): JsonElement = when (this) {
    is JsonObject -> JsonObject(Collections.unmodifiableMap(mapValues { (_, value) -> value.readOnlySnapshot() }))
    is JsonArray -> JsonArray(Collections.unmodifiableList(map { it.readOnlySnapshot() }))
    else -> this
}
