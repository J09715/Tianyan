package top.tianyan.app.runtime.bridge

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.io.OutputStream
import java.net.Socket

/** Shared framing for the legacy bridge and the scoped plugin route. */
internal object HostBridgeHttp {
    private const val MAX_BODY_BYTES = 4 * 1024 * 1024
    data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    data class HttpResponse(val status: Int, val body: String)

    fun parseHttpRequest(input: InputStream): HttpRequest? {
        var headerBytes = 0
        fun line(): String? {
            val bytes = java.io.ByteArrayOutputStream()
            while (bytes.size() <= 8192 && ++headerBytes <= 32768) {
                val next = input.read()
                if (next < 0) return null
                if (next == 10) {
                    val raw = bytes.toByteArray()
                    if (raw.isEmpty() || raw.last() != 13.toByte()) return null
                    return String(raw, 0, raw.size - 1, Charsets.US_ASCII)
                }
                if (next != 13 && next !in 32..126) return null
                bytes.write(next)
            }
            return null
        }
        val parts = (line() ?: return null).split(" ")
        if (parts.size != 3 || parts[2] !in setOf("HTTP/1.0", "HTTP/1.1")) return null
        val headers = mutableMapOf<String, String>()
        while (true) {
            val header = line() ?: return null
            if (header.isEmpty()) break
            val colon = header.indexOf(':')
            if (colon <= 0) return null
            val key = header.substring(0, colon).lowercase()
            if (!key.matches(Regex("[a-z0-9-]+")) || key in headers) return null
            headers[key] = header.substring(colon + 1).trim()
        }
        if ("transfer-encoding" in headers) return null
        val length = headers["content-length"]?.let { value ->
            if (!value.matches(Regex("[0-9]{1,10}"))) return null
            value.toIntOrNull() ?: return null
        } ?: 0
        val maximum = if (parts[1] == "/api/plugin/call") 4096 else MAX_BODY_BYTES
        if (length !in 0..maximum) return null
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(body, offset, length - offset)
            if (count <= 0) return null
            offset += count
        }
        val text = runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body)).toString()
        }.getOrNull() ?: return null
        return HttpRequest(parts[0], parts[1], headers, text)
    }

    fun writeResponse(client: Socket, status: Int, body: String) {
        val statusText = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            else -> "OK"
        }
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val output: OutputStream = client.getOutputStream()
        output.write("HTTP/1.1 $status $statusText\r\n".toByteArray(Charsets.US_ASCII))
        output.write("Content-Type: application/json; charset=utf-8\r\n".toByteArray(Charsets.US_ASCII))
        output.write("Content-Length: ${bodyBytes.size}\r\n".toByteArray(Charsets.US_ASCII))
        output.write("Connection: close\r\n".toByteArray(Charsets.US_ASCII))
        output.write("\r\n".toByteArray(Charsets.US_ASCII))
        output.write(bodyBytes)
        output.flush()
    }

}
