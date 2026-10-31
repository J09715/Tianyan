package top.tianyan.app.runtime.webchat

import java.io.IOException
import java.net.URLDecoder
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.shell.LinuxSession
import top.tianyan.app.runtime.shell.SessionConfig
import top.tianyan.app.runtime.shell.TerminalOutput

/**
 * 局域网远程终端桥。
 *
 * 每个 Web 终端会话通过 [LinuxRuntime.startSession] 起一个独立的 PTY
 * （JNI forkpty 优先，自动回退 script 后端），与手机端 Termux 会话互不干扰：
 * 输出经每个 SSE 订阅者一个 [Channel] 扇出（慢客户端满缓冲即断开，避免无界积压），
 * 输入与 resize 走 POST。鉴权与主桥共用同一 PIN（[pinProvider]）。
 *
 * 生命周期：会话由服务端持有，浏览器断开只断订阅、shell 继续存活，重开页面
 * 可通过列表接口重新订阅同一会话；[shutdown]（服务停止）回收全部 PTY。
 */
internal class WebTerminalBridge(
    private val linuxRuntime: LinuxRuntime,
    private val logger: AppLogger,
    private val scope: CoroutineScope,
    private val pinProvider: () -> String,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val handles = ConcurrentHashMap<String, WebTerminalHandle>()

    private class WebTerminalHandle(
        val id: String,
        val label: String,
        val workingDirectory: String,
        val distroId: String,
        val session: LinuxSession,
    ) {
        val createdAt: Long = System.currentTimeMillis()
        @Volatile var columns: Int = DEFAULT_COLUMNS
        @Volatile var rows: Int = DEFAULT_ROWS
        val listeners = ConcurrentHashMap.newKeySet<Channel<TerminalOutput>>()
    }

    fun registerRoutes(server: AndroidHttpServer) {
        server.createContext("/webchat/api/terminal", TerminalRouteHandler())
    }

    /** 与主桥 [WebChatBridgeServer.stop] 一起调用；scope 此时仍存活，异步回收即可。 */
    fun shutdown() {
        val ids = handles.keys.toList()
        if (ids.isEmpty()) return
        scope.launch { ids.forEach { closeTerminal(it) } }
    }

    // ---------------------------------------------------------------- routes

    private inner class TerminalRouteHandler : AndroidHttpHandler {
        override fun handle(exchange: AndroidHttpExchange) {
            scope.launch {
                try {
                    route(exchange)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    logger.e("天衍智枢 Web 终端请求失败：${exchange.requestURI.path}", throwable)
                    if (!exchange.isResponseStarted) {
                        runCatching { sendJson(exchange, 500, errorJson(throwable.message ?: "终端请求失败")) }
                    }
                    runCatching { exchange.close() }
                } finally {
                    // 已由 route / SSE 消费循环负责关闭的 exchange.close() 幂等。
                }
            }
        }
    }

    private suspend fun route(exchange: AndroidHttpExchange) {
        if (exchange.requestMethod.equals("OPTIONS", ignoreCase = true)) {
            sendResponse(exchange, 204, "text/plain", ByteArray(0))
            return
        }
        if (!isAuthenticated(exchange)) {
            sendJson(exchange, 401, errorJson("请先使用配对码连接"))
            return
        }
        val suffix = exchange.requestURI.path.substringAfter("/terminal", "").trim('/')
        val parts = suffix.split('/').filter(String::isNotBlank)
        when {
            parts.isEmpty() && exchange.requestMethod == "GET" -> listTerminals(exchange)
            parts.isEmpty() && exchange.requestMethod == "POST" -> createTerminal(exchange)
            parts.size == 1 && exchange.requestMethod == "DELETE" -> {
                closeTerminal(parts[0])
                sendJson(exchange, 200, buildJsonObject { put("closed", true) })
            }
            parts.size == 2 && parts[1] == "stream" && exchange.requestMethod == "GET" ->
                streamTerminal(exchange, parts[0])
            parts.size == 2 && parts[1] == "input" && exchange.requestMethod == "POST" ->
                inputTerminal(exchange, parts[0])
            parts.size == 2 && parts[1] == "resize" && exchange.requestMethod == "POST" ->
                resizeTerminal(exchange, parts[0])
            else -> sendText(exchange, 404, "终端接口不存在")
        }
    }

    private suspend fun listTerminals(exchange: AndroidHttpExchange) {
        sendJson(exchange, 200, buildJsonObject {
            putJsonArray("terminals") {
                handles.values.sortedBy { it.createdAt }.forEach { handle ->
                    add(buildJsonObject {
                        put("id", handle.id)
                        put("label", handle.label)
                        put("workingDirectory", handle.workingDirectory)
                        put("distroId", handle.distroId)
                        put("columns", handle.columns)
                        put("rows", handle.rows)
                        put("createdAt", handle.createdAt)
                        put("alive", handle.session.isAlive)
                    })
                }
            }
        })
    }

    private suspend fun createTerminal(exchange: AndroidHttpExchange) {
        val body = requestJson(exchange)
        val columns = (body["columns"]?.jsonPrimitive?.content?.toIntOrNull() ?: DEFAULT_COLUMNS)
            .coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        val rows = (body["rows"]?.jsonPrimitive?.content?.toIntOrNull() ?: DEFAULT_ROWS)
            .coerceIn(MIN_ROWS, MAX_ROWS)
        val workingDirectory = body["workingDirectory"]?.jsonPrimitive?.content?.trim()
            ?.takeIf(String::isNotEmpty) ?: DEFAULT_CWD
        val label = body["label"]?.jsonPrimitive?.content?.trim()
            ?.takeIf(String::isNotEmpty) ?: "Web 终端 ${handles.size + 1}"
        val distroId = linuxRuntime.activeDistroId.value

        val session = try {
            linuxRuntime.startSession(
                config = SessionConfig(
                    columns = columns,
                    rows = rows,
                    workingDirectory = workingDirectory,
                    // 与手机端终端会话一致的 TIANYAN 横幅；Web xterm 渲染 ANSI 无碍。
                    showBanner = true,
                ),
                distroId = distroId,
            )
        } catch (throwable: Throwable) {
            logger.e("天衍智枢 Web 终端创建失败", throwable)
            throw IllegalArgumentException("终端启动失败：${throwable.message ?: "PTY 不可用"}")
        }

        val id = UUID.randomUUID().toString()
        val handle = WebTerminalHandle(id, label, workingDirectory, distroId, session).apply {
            this.columns = columns
            this.rows = rows
        }
        handles[id] = handle
        startPump(handle)
        logger.i("天衍智枢 Web 终端已创建：$id（$label，$columns×$rows，$distroId）")
        sendJson(exchange, 200, buildJsonObject {
            put("id", id)
            put("label", handle.label)
            put("workingDirectory", handle.workingDirectory)
            put("distroId", handle.distroId)
            put("columns", handle.columns)
            put("rows", handle.rows)
            put("createdAt", handle.createdAt)
            put("alive", true)
        })
    }

    private suspend fun inputTerminal(exchange: AndroidHttpExchange, terminalId: String) {
        val handle = requireTerminal(terminalId)
        val body = requestJson(exchange)
        val encoded = body["data"]?.jsonPrimitive?.content.orEmpty()
        require(encoded.isNotBlank()) { "缺少输入数据" }
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("输入数据不是有效的 base64")
        }
        require(bytes.size <= MAX_INPUT_BYTES) { "输入过大（上限 ${MAX_INPUT_BYTES / 1024} KB）" }
        handle.session.write(bytes)
        sendJson(exchange, 200, buildJsonObject { put("written", bytes.size) })
    }

    private suspend fun resizeTerminal(exchange: AndroidHttpExchange, terminalId: String) {
        val handle = requireTerminal(terminalId)
        val body = requestJson(exchange)
        val columns = body["columns"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: throw IllegalArgumentException("缺少 columns")
        val rows = body["rows"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: throw IllegalArgumentException("缺少 rows")
        handle.session.resize(columns, rows)
        handle.columns = columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        handle.rows = rows.coerceIn(MIN_ROWS, MAX_ROWS)
        sendJson(exchange, 200, buildJsonObject { put("resized", true) })
    }

    /**
     * 终端输出 SSE 流。handler 返回后连接保持打开，由 [scope] 上的消费协程
     * 推送 [Channel] 缓冲：客户端断开（write 失败）或会话关闭（channel close）
     * 都会在 finally 中完成清理，[AndroidHttpExchange.close] 幂等可重复调用。
     */
    private suspend fun streamTerminal(exchange: AndroidHttpExchange, terminalId: String) {
        val handle = handles[terminalId] ?: run {
            sendJson(exchange, 404, errorJson("终端会话不存在"))
            return
        }
        val channel = Channel<TerminalOutput>(LISTENER_BUFFER)
        handle.listeners.add(channel)
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.responseHeaders.add("Connection", "keep-alive")
        exchange.sendResponseHeaders(200, 0)
        exchange.responseBody.write(sseFrame("hello", terminalJson(handle).toString()))
        exchange.responseBody.flush()
        try {
            for (chunk in channel) {
                val payload = Base64.getEncoder().encodeToString(chunk.text.toByteArray(Charsets.UTF_8))
                exchange.responseBody.write(sseFrame("output", payload))
                exchange.responseBody.flush()
            }
            // channel 正常关闭：会话已结束
            exchange.responseBody.write(sseFrame("exit", "{}"))
            exchange.responseBody.flush()
        } catch (_: IOException) {
            // 客户端断开：静默清理
        } finally {
            handle.listeners.remove(channel)
            runCatching { channel.close() }
            runCatching { exchange.close() }
        }
    }

    // ------------------------------------------------------------- internals

    /** 单一消费者：PTY 输出 → 全部订阅者；流结束（shell 退出）时回收会话。 */
    private fun startPump(handle: WebTerminalHandle) {
        scope.launch {
            try {
                handle.session.output.collect { chunk -> dispatchChunk(handle, chunk) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                logger.e("天衍智枢 Web 终端输出转发异常：${handle.id}", throwable)
            }
            closeTerminal(handle.id)
        }
    }

    private fun dispatchChunk(handle: WebTerminalHandle, chunk: TerminalOutput) {
        val iterator = handle.listeners.iterator()
        while (iterator.hasNext()) {
            val listener = iterator.next()
            val accepted = runCatching { listener.trySend(chunk).isSuccess }.getOrDefault(false)
            if (!accepted) {
                // 缓冲满（慢客户端）或已关闭：断开订阅，避免无界积压阻塞全局转发。
                runCatching { listener.close() }
                iterator.remove()
            }
        }
    }

    private suspend fun closeTerminal(id: String) {
        val handle = handles.remove(id) ?: return
        handle.listeners.forEach { runCatching { it.close() } }
        handle.listeners.clear()
        runCatching { handle.session.close() }
        logger.i("天衍智枢 Web 终端已关闭：$id（${handle.label}）")
    }

    private suspend fun requireTerminal(id: String): WebTerminalHandle =
        handles[id] ?: throw IllegalArgumentException("终端会话不存在")

    private fun terminalJson(handle: WebTerminalHandle) = buildJsonObject {
        put("id", handle.id)
        put("label", handle.label)
        put("workingDirectory", handle.workingDirectory)
        put("distroId", handle.distroId)
        put("columns", handle.columns)
        put("rows", handle.rows)
        put("createdAt", handle.createdAt)
        put("alive", handle.session.isAlive)
    }

    // ------------------------------------------------------------------- io

    private fun isAuthenticated(exchange: AndroidHttpExchange): Boolean {
        val token = exchange.requestURI.query
            ?.split('&')
            ?.firstOrNull { it.substringBefore('=') == "token" }
            ?.substringAfter('=', "")
            ?.let { URLDecoder.decode(it, Charsets.UTF_8.name()) }
            ?: exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")
        return token != null && token == pinProvider()
    }

    private fun requestJson(exchange: AndroidHttpExchange): JsonObject {
        val raw = exchange.requestBody.bufferedReader().readText()
        return if (raw.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(raw).jsonObject
    }

    private fun errorJson(message: String) = buildJsonObject { put("error", message) }

    private fun sendJson(exchange: AndroidHttpExchange, code: Int, payload: JsonElement) =
        sendResponse(exchange, code, "application/json; charset=utf-8", payload.toString().toByteArray())

    private fun sendText(exchange: AndroidHttpExchange, code: Int, text: String) =
        sendResponse(exchange, code, "text/plain; charset=utf-8", text.toByteArray())

    private fun sendResponse(exchange: AndroidHttpExchange, code: Int, contentType: String, bytes: ByteArray) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
        exchange.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type, Authorization")
        exchange.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
        exchange.close()
    }

    private fun sseFrame(event: String, data: String): ByteArray =
        "event: $event\ndata: $data\n\n".toByteArray(Charsets.UTF_8)

    private companion object {
        const val DEFAULT_CWD = "/root"
        const val DEFAULT_COLUMNS = 80
        const val DEFAULT_ROWS = 24
        const val MIN_COLUMNS = 20
        const val MAX_COLUMNS = 400
        const val MIN_ROWS = 5
        const val MAX_ROWS = 200
        /** 单次输入上限：键盘输入与常规粘贴足够；超大文本分片发送。 */
        const val MAX_INPUT_BYTES = 256 * 1024
        /**
         * 每订阅者缓冲（chunk ≈ PTY 单次读取 ≤ 8KB）。2048 条 ≈ 16MB，覆盖
         * `cat` 大文件这类突发输出；仍溢出说明客户端跟不上，直接断开订阅。
         */
        const val LISTENER_BUFFER = 2048
    }
}
