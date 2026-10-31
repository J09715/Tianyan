package top.tianyan.app.core.model

/** 校验时不把可能包含凭据的输入拼入异常信息。 */
fun validateMcpHttpHeaders(headers: Map<String, String>) {
    require(headers.keys.map { it.lowercase() }.distinct().size == headers.size) { "请求头名称不能重复（不区分大小写）" }
    headers.forEach { (name, value) ->
        require(name.isNotEmpty() && name.all { it in HTTP_TOKEN }) { "请求头名称格式无效" }
        require(name.lowercase() !in RESERVED_HEADERS) { "不能覆盖 HTTP/MCP 协议请求头" }
        require(value.all { it == '\t' || it.code in 32..126 }) { "请求头值仅支持可打印 ASCII 字符，不能包含换行" }
    }
}

private const val HTTP_TOKEN = "!#$%&'*+-.^_`|~0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
private val RESERVED_HEADERS = setOf(
    "host", "content-length", "content-type", "accept", "connection", "transfer-encoding",
    "mcp-session-id", "mcp-protocol-version", "proxy-authorization", "upgrade", "te", "trailer",
)
