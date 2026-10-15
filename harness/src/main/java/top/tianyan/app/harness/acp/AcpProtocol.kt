package top.tianyan.app.harness.acp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/**
 * Agent Client Protocol（ACP）线格式。
 *
 * ACP 是公开协议（agentclientprotocol/agent-client-protocol）：编辑器/宿主与
 * 任意编码智能体之间的 JSON-RPC 2.0 会话协议。小万（OmniBot）用它把 Claude Code /
 * Codex / DSH 这些自带工具循环的 CLI 引擎接进自己的会话；天衍走同一条路，
 * 于是「智能体」不再只是一段被抄进系统提示词的人格文本，而是一个真实进程。
 *
 * 传输约定：一行一个 JSON 对象（换行分帧），stdout 承载 Agent→Client，
 * stdin 承载 Client→Agent。与天衍既有的 MCP STDIO 通道同构，可共用同一套
 * LinuxSession 拉管与按行缓冲。
 *
 * 只实现会话生命周期所需的最小集合：initialize / session/new / session/prompt /
 * session/cancel，以及 Agent 侧的 session/update 通知与 session/request_permission
 * 反向请求。其余可选能力（session/list、session/load 等）由 Agent 在 initialize
 * 时自我声明——宿主绝不假设。
 */
public object AcpProtocol {
    /** 本客户端实现的 ACP 协议版本。能力协商以 Agent 的 initialize 回复为准。 */
    const val PROTOCOL_VERSION = 1

    const val METHOD_INITIALIZE = "initialize"
    const val METHOD_SESSION_NEW = "session/new"
    const val METHOD_SESSION_PROMPT = "session/prompt"
    const val METHOD_SESSION_CANCEL = "session/cancel"

    const val METHOD_SESSION_UPDATE = "session/update"
    const val METHOD_REQUEST_PERMISSION = "session/request_permission"

    /** ACP 保留扩展命名空间：以下划线开头的自定义方法原样转发。 */
    const val EXTENSION_PREFIX = "_"

    fun isExtension(method: String): Boolean = method.startsWith(EXTENSION_PREFIX)
}

@Serializable
public data class AcpRequest(
    val jsonrpc: String = "2.0",
    val id: String,
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
public data class AcpNotification(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
public data class AcpResponse(
    val jsonrpc: String = "2.0",
    val id: String? = null,
    val result: JsonElement? = null,
    val error: AcpError? = null,
)

@Serializable
public data class AcpError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

/** Agent → Client 的反向请求（如权限审批），宿主必须回一条同 id 的响应。 */
@Serializable
public data class AcpInboundRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement,
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
public data class AcpInitializeParams(
    val protocolVersion: Int = AcpProtocol.PROTOCOL_VERSION,
    val clientCapabilities: JsonObject = JsonObject(emptyMap()),
    val clientInfo: AcpClientInfo = AcpClientInfo(),
) {
    public companion object {
        /**
         * 显式编码，不依赖 `Json.encodeToString` 的默认值策略。
         *
         * 宿主注入的 Json 实例开着 `encodeDefaults=false`（为了不把空字段塞进
         * provider 请求）。若这里直接序列化，`protocolVersion` 与空的
         * `clientCapabilities` 都会被静默丢掉——Agent 收到一个没有版本号的
         * initialize，握手要么失败、要么按最低版本协商。协议字段逐个写出来。
         */
        fun toJsonElement(json: Json): JsonElement = buildJsonObject {
            put("protocolVersion", AcpProtocol.PROTOCOL_VERSION)
            put("clientCapabilities", json.encodeToJsonElement(JsonObject.serializer(), JsonObject(emptyMap())))
            put(
                "clientInfo",
                buildJsonObject {
                    put("name", "Tianyan")
                    put("title", "天衍")
                    put("version", "1.0.0")
                },
            )
        }
    }
}

@Serializable
public data class AcpClientInfo(
    val name: String = "Tianyan",
    val title: String = "天衍",
    val version: String = "1.0.0",
)

/** initialize 回复里宿主真正会用的部分；其余字段按 ignoreUnknownKeys 丢弃。 */
@Serializable
public data class AcpInitializeResult(
    val protocolVersion: Int = AcpProtocol.PROTOCOL_VERSION,
    val agentCapabilities: JsonObject = JsonObject(emptyMap()),
    val agentInfo: AcpAgentInfo? = null,
)

@Serializable
public data class AcpAgentInfo(
    val name: String = "",
    val title: String = "",
    val version: String = "",
)

@Serializable
public data class AcpNewSessionResult(
    val sessionId: String,
)

@Serializable
public data class AcpPromptResult(
    val stopReason: String = "end_turn",
)

/** ACP 内容块：目前只产出文本；图像/资源块按 type 原样透传。 */
@Serializable
public data class AcpContentBlock(
    val type: String = "text",
    val text: String? = null,
) {
    public companion object {
        fun text(value: String): AcpContentBlock = AcpContentBlock(type = "text", text = value)
    }
}

/**
 * session/update 通知里的会话更新载荷。
 *
 * 字段名沿用 ACP wire schema：`sessionUpdate` 判别类型，具体内容随类型不同。
 * 这里只解析天衍会上屏的部分，其余原样保留在 [raw] 里以便透传。
 */
public data class AcpSessionUpdate(
    val kind: String,
    val text: String,
    val raw: JsonObject,
) {
    val isAssistantText: Boolean get() = kind == KIND_AGENT_MESSAGE_CHUNK || kind == KIND_AGENT_MESSAGE
    val isToolCall: Boolean get() = kind.startsWith(KIND_TOOL_CALL_PREFIX)
    val isThought: Boolean get() = kind == KIND_AGENT_THOUGHT_CHUNK || kind == KIND_AGENT_THOUGHT

    companion object {
        const val KIND_AGENT_MESSAGE_CHUNK = "agent_message_chunk"
        const val KIND_AGENT_MESSAGE = "agent_message"
        const val KIND_AGENT_THOUGHT_CHUNK = "agent_thought_chunk"
        const val KIND_AGENT_THOUGHT = "agent_thought"
        const val KIND_TOOL_CALL_PREFIX = "tool_call"

        /** 从 update 载荷解析出上屏所需的判别信息；无法识别时返回 null 由调用方丢弃。 */
        fun from(params: JsonElement?): AcpSessionUpdate? {
            val update = (params as? JsonObject)?.get("update") as? JsonObject ?: return null
            val kind = (update["sessionUpdate"] as? JsonPrimitive)?.contentOrNullSafe() ?: return null
            return AcpSessionUpdate(
                kind = kind,
                text = extractText(update),
                raw = update,
            )
        }

        /**
         * ACP 的文本内容嵌套在 content 块里；chunk 类型给 content.text，
         * 消息类型给 content 数组。两种形态都要能取到，否则上屏为空。
         */
        private fun extractText(update: JsonObject): String {
            val content = update["content"] ?: return ""
            (content as? JsonObject)?.get("text")?.let { element ->
                return (element as? JsonPrimitive)?.contentOrNullSafe().orEmpty()
            }
            val array = content as? JsonArray ?: return ""
            return array.mapNotNull { block ->
                ((block as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNullSafe()
            }.joinToString("")
        }
    }
}

/** 取字符串内容；非字符串字面量（布尔/数字）一律当缺失处理，避免把 true 当成会话 id。 */
private fun JsonPrimitive.contentOrNullSafe(): String? =
    content.takeIf { isString && it.isNotEmpty() }

/** 权限审批的反向请求载荷（Agent → Client）。 */
public data class AcpPermissionRequest(
    val requestId: JsonElement,
    val sessionId: String,
    val toolCall: JsonObject?,
    val options: List<AcpPermissionOption>,
) {
    companion object {
        fun from(request: AcpInboundRequest): AcpPermissionRequest? {
            val params = request.params as? JsonObject ?: return null
            val sessionId = (params["sessionId"] as? JsonPrimitive)?.contentOrNullSafe() ?: return null
            val options = (params["options"] as? JsonArray)
                ?.mapNotNull { element ->
                    val obj = element as? JsonObject ?: return@mapNotNull null
                    val optionId = (obj["optionId"] as? JsonPrimitive)?.contentOrNullSafe() ?: return@mapNotNull null
                    AcpPermissionOption(
                        optionId = optionId,
                        name = (obj["name"] as? JsonPrimitive)?.contentOrNullSafe().orEmpty(),
                        kind = (obj["kind"] as? JsonPrimitive)?.contentOrNullSafe().orEmpty(),
                    )
                }
                .orEmpty()
            return AcpPermissionRequest(
                requestId = request.id,
                sessionId = sessionId,
                toolCall = params["toolCall"] as? JsonObject,
                options = options,
            )
        }
    }
}

public data class AcpPermissionOption(
    val optionId: String,
    val name: String,
    val kind: String,
) {
    /** ACP 约定 allow_once / allow_always 表示放行，其余一律视为拒绝。 */
    val isAllow: Boolean get() = kind.startsWith("allow")
}

/** 构造一条 allow/deny 响应，回给 Agent 的反向请求。 */
public fun acpPermissionResponse(requestId: JsonElement, option: AcpPermissionOption?): JsonObject =
    buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", requestId)
        put(
            "result",
            buildJsonObject {
                put(
                    "outcome",
                    buildJsonObject {
                        if (option != null) {
                            put("outcome", "selected")
                            put("optionId", option.optionId)
                        } else {
                            put("outcome", "cancelled")
                        }
                    },
                )
            },
        )
    }

/** 构造一条 JSON-RPC 错误响应，用于拒绝无法处理的反向请求。 */
public fun acpErrorResponse(requestId: JsonElement, code: Int, message: String): JsonObject =
    buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", requestId)
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
            },
        )
    }

/** 会话取消通知（无 id）。 */
public fun acpCancelNotification(sessionId: String): JsonObject =
    buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", AcpProtocol.METHOD_SESSION_CANCEL)
        put("params", buildJsonObject { put("sessionId", sessionId) })
    }
