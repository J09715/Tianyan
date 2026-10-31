package top.tianyan.app.harness.mcp.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.tianyan.app.runtime.browser.secret.SecretRedactingInterceptor
import top.tianyan.app.runtime.browser.tools.BrowserMcpTools
import kotlinx.serialization.json.JsonArray

/**
 * 把 `mcp__browser__<tool>` 转发到 [BrowserMcpTools] 实例，并把 [extraProviders]
 * （如被控端的 Harness 工具）聚合进同一个 tools/list 与 tools/call 出口。
 */
class McpToolDispatcher(
    private val browserTools: BrowserMcpTools,
    private val extraProviders: List<McpToolProvider> = emptyList(),
) {
    fun listTools(): List<JsonObject> = browserTools.list().map { spec ->
        buildJsonObject {
            put("name", JsonPrimitive(spec.name))
            put("risk", JsonPrimitive(spec.risk.name))
            put("capability", JsonPrimitive(spec.capability.name))
            put("description", JsonPrimitive(spec.description))
            put("inputSchema", spec.inputSchema)
        }
    } + extraProviders.flatMap { it.listTools() }

    suspend fun dispatch(toolName: String, args: JsonObject): JsonObject {
        // 先让外部 provider 认领（其名称空间与 browser.* 不重叠，命中即转发）
        extraProviders.firstOrNull { provider ->
            provider.listTools().any { (it["name"] as? JsonPrimitive)?.content == toolName }
        }?.let { return it.callTool(toolName, args) }

        val res = browserTools.invoke(toolName, args)
        // 统一脱敏出口：cookie/页面源码/console 等可能夹带敏感值，防止直接回灌 LLM
        val text = SecretRedactingInterceptor.apply(
            if (res.imageAttachments.isEmpty()) res.output else buildJsonObject {
                put("output", JsonPrimitive(res.output))
                put("imageAttachments", JsonArray(res.imageAttachments.map { ir ->
                    buildJsonObject {
                        put("id", JsonPrimitive(ir.id))
                        put("uri", JsonPrimitive(ir.uri))
                        put("mime", JsonPrimitive(ir.mime))
                        put("width", JsonPrimitive(ir.width))
                        put("height", JsonPrimitive(ir.height))
                    }
                }))
            }.toString()
        )
        return buildJsonObject {
            put("content", JsonArray(listOf(buildJsonObject {
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive(text))
            })))
            put("isError", JsonPrimitive(!res.success))
        }
    }
}
