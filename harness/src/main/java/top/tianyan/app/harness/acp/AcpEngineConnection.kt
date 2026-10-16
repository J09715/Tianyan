package top.tianyan.app.harness.acp

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.encodeToJsonElement
import top.tianyan.app.core.model.RuntimeState
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.core.tools.ProviderManager
import top.tianyan.app.runtime.shell.LinuxSession
import top.tianyan.app.runtime.shell.SessionConfig
import top.tianyan.app.runtime.shell.TerminalOutput
import kotlin.time.Duration.Companion.milliseconds

/** 一个已建立的 ACP 会话：会话 id + 归属的引擎。 */
data class AcpSessionHandle(
    val sessionId: String,
    val engineId: String,
)

/** Agent → 宿主的单向事件流：上屏文本、思考、工具调用、权限审批、进程退出。 */
sealed interface AcpEvent {
    val sessionId: String

    data class AssistantText(override val sessionId: String, val text: String) : AcpEvent
    data class Thought(override val sessionId: String, val text: String) : AcpEvent
    data class ToolCall(override val sessionId: String, val kind: String, val raw: JsonObject) : AcpEvent
    data class Permission(
        override val sessionId: String,
        val request: AcpPermissionRequest,
    ) : AcpEvent

    data class TurnFinished(override val sessionId: String, val stopReason: String) : AcpEvent
    data class Exited(override val sessionId: String, val message: String) : AcpEvent
}

/**
 * 单条 ACP 引擎连接：一个 PRoot 内的 CLI 进程 + 一条换行分帧的 JSON-RPC 会话。
 *
 * 传输与天衍既有的 MCP STDIO 通道同构（stdin 写、stdout 按行读），但语义不同：
 * ACP 是双向的——Agent 会主动发起 session/update 通知与 session/request_permission
 * 反向请求，宿主必须回包。因此这里不能复用「发一条请求等一条响应」的简单模型，
 * 而是常驻一个读循环把帧分流到三条路：
 *
 *   1. 我方请求的响应（id 命中 pending）→ 唤醒对应挂起的请求；
 *   2. Agent 的反向请求（带 method + id）→ 转成 [AcpEvent.Permission] 交给宿主裁决；
 *   3. Agent 的通知（带 method 无 id）→ 转成 [AcpEvent] 推给上层上屏。
 *
 * 进程生命周期绑在 [LinuxRuntime] 的 PTY 会话上，沙箱未就绪时拒绝建立连接。
 */
class AcpEngineConnection(
    val engineId: String,
    private val session: LinuxSession,
    private val json: Json,
    private val scope: CoroutineScope,
    private val maxFrameChars: Int = MAX_FRAME_CHARS,
) {
    private val pending = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<AcpResponse>>()
    private val events = MutableSharedFlow<AcpEvent>(extraBufferCapacity = EVENT_BUFFER)
    val eventFlow: SharedFlow<AcpEvent> = events.asSharedFlow()

    /**
     * 事件流没有 replay，读循环又一直在跑：如果调用方在订阅生效前就发起 prompt，
     * 引擎最早吐出的那几个 chunk 会静默丢掉——表现为「回复少了第一句」。
     * 因此这里等订阅真正挂上再返回，而不是赌 launch 的调度顺序。
     */
    suspend fun awaitSubscriber(timeoutMs: Long = SUBSCRIBE_TIMEOUT_MS) {
        // 尽力而为：等不到订阅也不该把整个回合判失败，只是可能丢开头几帧。
        withTimeoutOrNull(timeoutMs.milliseconds) {
            events.subscriptionCount.first { count -> count > 0 }
        }
    }

    private val writeMutex = Mutex()
    private val inboundQueue: Channel<JsonObject> = Channel(capacity = FRAME_BUFFER)

    /** 回显防线：识别「自己写出去又被 PTY 回显读回」的帧，见 [FrameEchoGuard]。 */
    private val echoGuard = top.tianyan.app.runtime.pty.FrameEchoGuard()

    @Volatile
    private var closed = false

    val isAlive: Boolean get() = !closed && session.isAlive

    /** 引擎侧会话 id → 天衍会话 id 的反查表（一个进程可承载多个会话）。 */
    private val sessionIndex = ConcurrentHashMap<String, String>()

    init {
        scope.launch { readLoop() }
    }

    private suspend fun readLoop() {
        val buffer = StringBuilder()
        try {
            session.output.collect { output: TerminalOutput ->
                if (output.stream == top.tianyan.app.runtime.shell.TerminalStream.STDERR) return@collect
                val chunk = output.text
                var start = 0
                while (start < chunk.length) {
                    val newline = chunk.indexOf('\n', start)
                    val end = if (newline >= 0) newline else chunk.length
                    buffer.append(chunk, start, end)
                    if (newline < 0) break
                    val line = buffer.toString().trim()
                    buffer.setLength(0)
                    // 行规程回显防线：PTY 若没关成 raw，这里读到的是我们自己刚写出的帧。
                    // 不吞掉就会把宿主请求误判成引擎反向请求，回 -32601 打穿握手。
                    if (line.isNotEmpty() && !echoGuard.isEcho(line)) dispatchFrame(line)
                    start = newline + 1
                }
                if (buffer.length > maxFrameChars) {
                    buffer.setLength(0)
                    emitSystem("ACP 单帧超过 ${maxFrameChars} 字符上限，已丢弃")
                }
            }
        } catch (_: CancellationException) {
            throw CancellationException()
        } catch (t: Throwable) {
            emitSystem("ACP 读循环异常：${t.message ?: t::class.simpleName}")
        } finally {
            failAllPending("ACP 引擎进程已退出")
            closed = true
            val owner = sessionIndex.values.firstOrNull() ?: engineId
            events.tryEmit(AcpEvent.Exited(owner, "引擎进程已退出"))
        }
    }

    private suspend fun dispatchFrame(line: String) {
        val element = runCatching { json.parseToJsonElement(line) }.getOrNull() ?: run {
            emitSystem("ACP 输出了非 JSON 行，已忽略")
            return
        }
        val obj = element as? JsonObject ?: return

        val method = (obj["method"] as? JsonPrimitive)?.let { if (it.isString) it.content else null }
        val idElement = obj["id"]

        when {
            // Agent 主动发起的请求或通知（带 method）。
            method != null -> handleAgentFrame(method, idElement, obj["params"])

            // 我方请求的响应。
            idElement != null -> {
                val id = (idElement as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
                val parsed = runCatching {
                    json.decodeFromJsonElement(AcpResponse.serializer(), obj)
                }.getOrNull() ?: return
                pending.remove(id)?.complete(parsed)
            }

            else -> emitSystem("ACP 输出了无 method 也无 id 的帧，已忽略")
        }
    }

    private suspend fun handleAgentFrame(method: String, idElement: JsonElement?, params: JsonElement?) {
        val isRequest = idElement != null
        if (isRequest) {
            // 反向请求：目前只有权限审批需要宿主裁决，其余一律回错误让 Agent 走降级路径，
            // 而不是静默挂起——静默挂起会让引擎永久等待，表现为「会话卡死」。
            val inbound = runCatching {
                AcpInboundRequest(id = idElement, method = method, params = params)
            }.getOrNull() ?: return
            val permission = AcpPermissionRequest.from(inbound)
            if (permission != null && method == AcpProtocol.METHOD_REQUEST_PERMISSION) {
                events.tryEmit(AcpEvent.Permission(resolveSessionId(permission.sessionId), permission))
            } else {
                writeFrame(acpErrorResponse(idElement, METHOD_NOT_FOUND, "宿主不支持反向请求：$method"))
            }
            return
        }

        when (method) {
            AcpProtocol.METHOD_SESSION_UPDATE -> {
                val update = AcpSessionUpdate.from(params) ?: return
                val sessionId = resolveSessionId(updateSessionId(params))
                when {
                    update.isThought -> events.tryEmit(AcpEvent.Thought(sessionId, update.text))
                    update.isAssistantText && update.text.isNotEmpty() ->
                        events.tryEmit(AcpEvent.AssistantText(sessionId, update.text))
                    update.isToolCall -> events.tryEmit(AcpEvent.ToolCall(sessionId, update.kind, update.raw))
                    else -> Unit // 其余 update 类型（plan/available_commands 等）暂不上屏。
                }
            }
            else -> if (AcpProtocol.isExtension(method)) {
                emitSystem("引擎扩展通知：$method")
            }
        }
    }

    /** session/update 通知把会话 id 放在 params.sessionId。 */
    private fun updateSessionId(params: JsonElement?): String =
        ((params as? JsonObject)?.get("sessionId") as? JsonPrimitive)
            ?.takeIf { it.isString }?.content.orEmpty()

    private fun resolveSessionId(engineSessionId: String): String =
        sessionIndex[engineSessionId] ?: engineSessionId

    private fun emitSystem(message: String) {
        val owner = sessionIndex.values.firstOrNull() ?: engineId
        events.tryEmit(AcpEvent.AssistantText(owner, message))
    }

    /** 发起 initialize 握手，返回 Agent 自报的能力与身份。 */
    suspend fun initialize(): AcpInitializeResult {
        val params = AcpInitializeParams.toJsonElement(json)
        val response = request(AcpProtocol.METHOD_INITIALIZE, params, INIT_TIMEOUT_MS)
        val result = response.result ?: error("ACP initialize 未返回结果")
        return json.decodeFromJsonElement(AcpInitializeResult.serializer(), result)
    }

    /** 建立引擎侧会话并登记反查表。 */
    suspend fun newSession(
        tianyanSessionId: String,
        cwd: String,
        mcpServers: List<JsonObject> = emptyList(),
    ): String {
        // cwd 与 sessionId 是协议必填字段，这里显式构造：宿主 Json 关着 encodeDefaults，
        // 用序列化器写空 mcpServers 列表会把它整个丢掉，而部分引擎要求该字段存在。
        val params = buildJsonObject {
            put("cwd", cwd)
            put("mcpServers", json.encodeToJsonElement(JsonArray.serializer(), JsonArray(mcpServers)))
        }
        val response = request(AcpProtocol.METHOD_SESSION_NEW, params, SESSION_TIMEOUT_MS)
        val result = response.result ?: error("ACP session/new 未返回结果")
        val sessionId = json.decodeFromJsonElement(AcpNewSessionResult.serializer(), result).sessionId
        require(sessionId.isNotBlank()) { "ACP session/new 返回了空会话 id" }
        sessionIndex[sessionId] = tianyanSessionId
        return sessionId
    }

    /** 发送一轮用户输入；返回 Agent 报告的停止原因。 */
    suspend fun prompt(engineSessionId: String, text: String): AcpPromptResult {
        val params = buildJsonObject {
            put("sessionId", engineSessionId)
            put(
                "prompt",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        },
                    )
                },
            )
        }
        val response = request(AcpProtocol.METHOD_SESSION_PROMPT, params, PROMPT_TIMEOUT_MS)
        val result = response.result ?: return AcpPromptResult()
        return json.decodeFromJsonElement(AcpPromptResult.serializer(), result)
    }

    /** 取消当前回合。取消是通知，不等响应。 */
    suspend fun cancel(engineSessionId: String) {
        writeFrame(acpCancelNotification(engineSessionId))
    }

    /** 回复一条权限审批。 */
    suspend fun respondToPermission(request: AcpPermissionRequest, allow: Boolean) {
        val option = if (allow) {
            request.options.firstOrNull { it.isAllow }
        } else {
            request.options.firstOrNull { !it.isAllow }
        }
        writeFrame(acpPermissionResponse(request.requestId, option))
    }

    suspend fun close() = withContext(NonCancellable) {
        if (closed) return@withContext
        closed = true
        runCatching { session.close() }
        failAllPending("ACP 连接已关闭")
    }

    private suspend fun request(
        method: String,
        params: JsonElement,
        timeoutMs: Long,
    ): AcpResponse {
        check(isAlive) { "ACP 引擎进程不可用（$engineId）" }
        val id = UUID.randomUUID().toString()
        val deferred = kotlinx.coroutines.CompletableDeferred<AcpResponse>()
        pending[id] = deferred
        writeFrame(json.encodeToString(AcpRequest.serializer(), AcpRequest(id = id, method = method, params = params)))
        return try {
            withTimeout(timeoutMs.milliseconds) { deferred.await() }
        } catch (t: Throwable) {
            pending.remove(id)
            throw t
        }.also { response ->
            response.error?.let { error("ACP JSON-RPC ${it.code}：${it.message}") }
        }
    }

    private suspend fun writeFrame(payload: String) = writeMutex.withLock {
        if (!isAlive) error("ACP 引擎进程不可用（$engineId）")
        echoGuard.record(payload)
        session.write((payload + "\n").toByteArray(Charsets.UTF_8))
    }

    /** 反向响应与通知直接以 JsonObject 构造，这里统一编码，避免调用点重复 json.encodeToString。 */
    private suspend fun writeFrame(payload: JsonObject) = writeFrame(payload.toString())

    private fun failAllPending(reason: String) {
        val snapshot = pending.keys.toList()
        snapshot.forEach { id ->
            pending.remove(id)?.completeExceptionally(IllegalStateException(reason))
        }
    }

    private companion object {
        const val INIT_TIMEOUT_MS = 60_000L
        const val SESSION_TIMEOUT_MS = 60_000L
        const val PROMPT_TIMEOUT_MS = 30L * 60L * 1000L
        const val FRAME_BUFFER = 64
        const val EVENT_BUFFER = 256
        const val SUBSCRIBE_TIMEOUT_MS = 5_000L
        const val MAX_FRAME_CHARS = 1 * 1024 * 1024
        const val METHOD_NOT_FOUND = -32601
    }
}

/**
 * ACP 引擎连接的工厂：把声明式目录里的命令翻译成一条 PRoot PTY 会话。
 *
 * 与小万的 LocalAcpRuntime 同责：宿主不解析引擎内部，只负责「按目录发现命令 →
 * 在沙箱里拉起 → 按协议建会话」。命令是否装好由插件中心负责，这里只做拉起，
 * 失败向上抛，让会话层把「引擎未安装」作为可读原因呈现给用户。
 */
@Singleton
class AcpEngineConnectionFactory @Inject constructor(
    private val linuxRuntime: LinuxRuntime,
    private val json: Json,
    private val providerManager: ProviderManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun open(profile: AgentEngineProfile, workspace: String): AcpEngineConnection {
        check(linuxRuntime.state.value is RuntimeState.Ready) {
            "Linux 沙箱尚未就绪，无法拉起智能体引擎 ${profile.name}"
        }
        val session = linuxRuntime.startSession(
            SessionConfig(
                workingDirectory = workspace.ifBlank { "/workspace" },
                environment = AcpCommandBuilder.environment(profile) + providerManager.environment(),
                commandLine = AcpCommandBuilder.commandLine(profile),
                allowSttyResize = false,
                showBanner = false,
                // 按行分帧的 JSON-RPC：PTY 行规程的回显会把宿主请求帧读回来，
                // 读循环误判成引擎反向请求，initialize 直接回 -32601。必须 raw。
                rawTerminal = true,
            ),
        )
        return AcpEngineConnection(
            engineId = profile.id,
            session = session,
            json = json,
            scope = scope,
        )
    }
}

/**
 * 纯函数式的命令构造：与运行时解耦，便于单测断言「装好的 shim 优先、
 * 参数逐字 shell 引用、环境不夹带任何密钥」。
 */
object AcpCommandBuilder {
    /**
     * 命令入口优先用 /opt/tianyan/bin 下的 shim（插件中心安装时创建），
     * 回落到 PATH 里的原始命令名，与工具安装链路保持一致。
     */
    fun commandLine(profile: AgentEngineProfile): String {
        val args = profile.arguments.joinToString(" ") { shellQuote(it) }
        val command = shellQuote(profile.command)
        val suffix = if (args.isBlank()) "" else " $args"
        return "if [ -x /opt/tianyan/bin/${profile.command} ]; then " +
            "exec /opt/tianyan/bin/${profile.command}$suffix; " +
            "else exec $command$suffix; fi"
    }

    /**
     * 引擎只认自己的环境变量；API Key 由插件安装时写入的配置目录承载，
     * 不在此注入——把密钥塞进命令行或环境会让它出现在进程列表与日志里。
     */
    fun environment(profile: AgentEngineProfile): Map<String, String> = mapOf(
        "PATH" to "/opt/tianyan/bin:/usr/local/bin:/usr/bin:/bin",
        "TERM" to "dumb",
        "NO_COLOR" to "1",
    )

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
