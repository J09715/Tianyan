package top.tianyan.app.harness.mcp.server

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.tianyan.app.runtime.browser.secret.SecretRedactingInterceptor
import top.tianyan.app.runtime.browser.tools.BrowserMcpTools

/**
 * 把 `mcp__browser__<tool>` 转发到 [BrowserMcpTools] 实例。
 */
@Singleton
class McpToolDispatcher @Inject constructor(
    private val browserTools: BrowserMcpTools,
    private val redTeamBridge: RedTeamMcpBridge? = null,
) {
    fun listTools(): List<JsonObject> = buildList {
        addAll(browserTools.list().map { spec ->
            buildJsonObject {
                put("name", JsonPrimitive(spec.name))
                put("risk", JsonPrimitive(spec.risk.name))
                put("capability", JsonPrimitive(spec.capability.name))
                put("description", JsonPrimitive(spec.description))
                put("inputSchema", spec.inputSchema)
            }
        })
        // 引擎型智能体没有天衍的工具表，红队只能经 MCP 暴露给它们（见 RedTeamMcpBridge）。
        redTeamBridge?.takeIf { it.available }?.let { add(it.describe()) }
    }

    /**
     * 派发一次工具调用。
     *
     * @param sessionId 宿主注入的会话 id。引擎在 MCP server 的 URL 里带着
     *   `?session_id=xxx` 连过来（见 [AcpSessionRunner.buildTianyanMcpServers]），
     *   但引擎**不会**把这个参数抄进工具调用——它只是连服务器的地址。
     *   因此服务端在派发前从 query 里取出并注入，工具层（如 [RedTeamMcpBridge]）
     *   才能知道这笔调用属于哪个天衍会话。
     */
    suspend fun dispatch(toolName: String, args: JsonObject, sessionId: String? = null): JsonObject {
        // 红队桥接优先：工具名与浏览器工具不重叠，放前面避免误落进浏览器分支。
        redTeamBridge?.takeIf { it.available && it.toolName == toolName }?.let {
            return it.invoke(args.withSessionId(sessionId))
        }
        val res = browserTools.invoke(toolName, args)
        // 统一脱敏出口：cookie/页面源码/console 等可能夹带敏感值，防止直接回灌 LLM
        val text = SecretRedactingInterceptor.apply(
            if (res.imageAttachments.isEmpty()) res.output else buildJsonObject {
                put("output", JsonPrimitive(res.output))
                put("imageAttachments", kotlinx.serialization.json.JsonArray(res.imageAttachments.map { ir ->
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
            put("content", kotlinx.serialization.json.JsonArray(listOf(buildJsonObject {
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive(text))
            })))
            put("isError", JsonPrimitive(!res.success))
        }
    }
}

/**
 * 补上宿主注入的会话 id。
 *
 * **只在调用方没给 session_id 时才补**：引擎如果自己带了（未来支持从工具参数
 * 传入），以引擎的为准——它可能是在处理另一个会话的上下文。
 */
private fun JsonObject.withSessionId(sessionId: String?): JsonObject {
    if (sessionId.isNullOrBlank()) return this
    if (containsKey("session_id")) return this
    return buildJsonObject {
        for ((k, v) in this@withSessionId) put(k, v)
        put("session_id", JsonPrimitive(sessionId))
    }
}
