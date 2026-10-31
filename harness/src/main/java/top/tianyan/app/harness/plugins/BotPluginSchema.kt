package top.tianyan.app.harness.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.tianyan.app.plugin.api.PluginDescriptor
import top.tianyan.app.plugin.api.PluginExtension
import top.tianyan.app.plugin.api.PluginExtensionPoint

/** 插件目录校验只接受 `[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*`，扩展 ID 必须与工具 ID 完全一致。 */
internal fun botManagementDescriptor() = PluginDescriptor(
    id = "tianyan.bot-management",
    version = "1.0.0",
    extensions = listOf(
        PluginExtension("create-bot", PluginExtensionPoint.TOOL),
        PluginExtension("assign-task", PluginExtensionPoint.TOOL),
        PluginExtension("list-bots", PluginExtensionPoint.TOOL),
        PluginExtension("list-assignments", PluginExtensionPoint.TOOL),
        PluginExtension("inspect-assignment", PluginExtensionPoint.TOOL),
        PluginExtension("cancel-assignment", PluginExtensionPoint.TOOL),
        PluginExtension("continue-assignment", PluginExtensionPoint.TOOL),
        PluginExtension("report-assignment", PluginExtensionPoint.TOOL),
        PluginExtension("list-models", PluginExtensionPoint.TOOL),
        PluginExtension("select-model", PluginExtensionPoint.TOOL),
        PluginExtension("inspect-governance", PluginExtensionPoint.TOOL),
        PluginExtension("submit-proposal", PluginExtensionPoint.TOOL),
        PluginExtension("review-proposal", PluginExtensionPoint.TOOL),
    ),
)

internal fun botMemoryDescriptor() = PluginDescriptor(
    id = "tianyan.bot-memory",
    version = "1.0.0",
    extensions = listOf(
        PluginExtension("memory-context", PluginExtensionPoint.CONTEXT),
        PluginExtension("memory-read", PluginExtensionPoint.TOOL),
        PluginExtension("memory-write", PluginExtensionPoint.TOOL),
        PluginExtension("memory-list", PluginExtensionPoint.TOOL),
        PluginExtension("memory-history", PluginExtensionPoint.TOOL),
    ),
)

internal fun botScheduleDescriptor() = PluginDescriptor(
    id = "tianyan.bot-schedule",
    version = "1.0.0",
    extensions = listOf(
        PluginExtension("schedule-task", PluginExtensionPoint.TOOL),
        PluginExtension("list-schedules", PluginExtensionPoint.TOOL),
        PluginExtension("cancel-schedule", PluginExtensionPoint.TOOL),
    ),
)

/** Bot 插件共用的严格 Schema 片段（PackageSchemas 支持的子集）。 */
internal fun pluginStringProperty(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

internal fun pluginIntegerProperty(description: String, minimum: Int, maximum: Int): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
    put("minimum", minimum)
    put("maximum", maximum)
}

internal fun pluginBooleanProperty(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

internal fun pluginStringArrayProperty(description: String, itemDescription: String): JsonObject = buildJsonObject {
    put("type", "array")
    put("description", description)
    put("items", buildJsonObject {
        put("type", "string")
        put("description", itemDescription)
    })
}

internal fun pluginObjectSchema(required: List<String>, properties: Map<String, JsonObject>): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", JsonObject(properties))
    if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
    put("additionalProperties", false)
}
