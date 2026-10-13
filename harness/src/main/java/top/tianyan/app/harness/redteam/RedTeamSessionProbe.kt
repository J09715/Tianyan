package top.tianyan.app.harness.redteam

/**
 * 会话连通性实测接缝，移植自上游 `probeSessions`。
 *
 * 上游把这件事放在 host 侧异步分发（`dispatchAsync`）里，因为智能体的沙箱连不出去，
 * 而「WebShell 还活着吗、隧道还通吗」必须真连一次才知道。本移植同样要真连，
 * 所以读盘/发包都留在这个窄接口后面——单测注入假实现，绝不真发网络请求。
 *
 * 返回 `status to note`：status 用上游枚举（WebShell `online`/`offline`，隧道 `active`/`down`）。
 */
interface RedTeamSessionProbe {

    /** HTTP GET，判据与上游一致：`status < 500` 视为在线（3xx/4xx 说明端口是活的）。 */
    fun http(url: String, timeoutMs: Int): Pair<String, String>

    /** TCP 连通性。 */
    fun tcp(host: String, port: Int, timeoutMs: Int): Pair<String, String>

    companion object {
        /** 真实网络实现。 */
        val Network: RedTeamSessionProbe = object : RedTeamSessionProbe {
            override fun http(url: String, timeoutMs: Int): Pair<String, String> = try {
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    // 上游 `redirect: 'manual'`：跟着跳转会把「302 到登录页」误判成在线服务。
                    instanceFollowRedirects = false
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    useCaches = false
                }
                try {
                    val code = conn.responseCode
                    (if (code < 500) "online" else "offline") to "HTTP $code"
                } finally {
                    runCatching { conn.disconnect() }
                }
            } catch (error: Throwable) {
                "offline" to describe(error, timeoutMs)
            }

            override fun tcp(host: String, port: Int, timeoutMs: Int): Pair<String, String> = try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
                    "active" to ""
                }
            } catch (error: Throwable) {
                "down" to describe(error, timeoutMs)
            }

            /** 上游只留 120~160 字符：整段堆栈塞进 payload 会把它撑爆，界面也没法看。 */
            private fun describe(error: Throwable, timeoutMs: Int): String = when {
                error is java.net.SocketTimeoutException -> "超时 >${timeoutMs}ms"
                error is java.net.UnknownHostException -> "域名解析失败：${error.message.orEmpty()}".take(120)
                error is java.net.ConnectException -> "连接被拒绝：${error.message.orEmpty()}".take(120)
                else -> (error.message ?: error.toString()).take(120)
            }
        }
    }
}
