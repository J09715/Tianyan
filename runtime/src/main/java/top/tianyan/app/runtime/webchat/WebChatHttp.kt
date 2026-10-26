package top.tianyan.app.runtime.webchat

import java.net.URLDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * WebChat HTTP 层共享工具：桥接主服务器、终端桥与各路由文件共用的响应/解析函数。
 * 全部为纯函数（只依赖入参），internal 限定在 runtime 模块内。
 */
internal fun errorJson(message: String) = buildJsonObject { put("error", message) }

internal fun sendJson(exchange: AndroidHttpExchange, code: Int, payload: JsonElement) =
    sendResponse(exchange, code, "application/json; charset=utf-8", payload.toString().toByteArray())

internal fun sendText(exchange: AndroidHttpExchange, code: Int, text: String) =
    sendResponse(exchange, code, "text/plain; charset=utf-8", text.toByteArray())

internal fun sendResponse(exchange: AndroidHttpExchange, code: Int, contentType: String, bytes: ByteArray) {
    exchange.responseHeaders.add("Content-Type", contentType)
    exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
    exchange.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type, Authorization")
    exchange.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
    exchange.sendResponseHeaders(code, bytes.size.toLong())
    if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
    exchange.close()
}

internal fun requestJson(exchange: AndroidHttpExchange): JsonObject {
    val raw = exchange.requestBody.bufferedReader().readText()
    return if (raw.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(raw).jsonObject
}

internal fun getQueryParam(exchange: AndroidHttpExchange, key: String): String? {
    val raw = exchange.requestURI.query.orEmpty().split('&').firstOrNull { it.substringBefore('=') == key }
        ?.substringAfter('=', "") ?: return null
    return URLDecoder.decode(raw, Charsets.UTF_8.name())
}
