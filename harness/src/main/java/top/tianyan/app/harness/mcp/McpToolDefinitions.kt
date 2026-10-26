package top.tianyan.app.harness.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.tianyan.app.core.model.McpToolInfo
import top.tianyan.app.harness.ApiFunctionDefinition
import top.tianyan.app.harness.ApiToolDefinition

/** 把 @ 挂载的 MCP 工具转成 provider function schema；名称使用稳定编码。 */
internal fun McpToolInfo.toApiToolDefinition(): ApiToolDefinition {
    val parsed = runCatching { Json.parseToJsonElement(parametersJson) as? JsonObject }.getOrNull()
    val parameters = when {
        parsed == null -> buildJsonObject { put("type", "object") }
        parsed.containsKey("type") -> parsed
        else -> JsonObject(parsed + ("type" to JsonPrimitive("object")))
    }
    return ApiToolDefinition(
        function = ApiFunctionDefinition(
            name = McpToolApiName.encode(this),
            description = "[$serverName] ${description.ifBlank { name }}".take(1_200),
            parameters = parameters,
        ),
    )
}
