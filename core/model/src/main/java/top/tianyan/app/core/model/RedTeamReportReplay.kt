package top.tianyan.app.core.model

/**
 * 报告复现工具，移植自上游 `report-replay.js`（纯函数，零依赖）。
 *
 * 目标：让报告里**每一条得分**都有「可照做」的复现入口，而不是只有一句「没有原始请求记录」。
 * 数据都在事实库里（攻击步骤的 tool、目标 URL、证据文本），这里整理成能重放的报文与能直接跑的命令。
 *
 * 两条底线（上游明确要求，移植时保持）：
 *   · **合成的东西必须标注来源**（[HttpRequest.synthesized]）—— 推断出来的请求与真实抓包
 *     在可信度上不是一回事，报告里要能一眼分辨，不能让验收人把推断当实证；
 *   · 只在信息足够时合成：有 URL 或有明确的 curl 命令才做，否则留空并给补录指引，**不编造**。
 */
object RedTeamReportReplay {

    /** target 解析结果。 */
    data class Target(
        val scheme: String?,
        val host: String,
        val port: Int?,
        val path: String?,
        val authority: String,
        val ipv6: Boolean,
    )

    /**
     * 从 target 解析 scheme / host / port / path。
     *
     * 必须显式处理 IPv6 方括号写法：`http://[2001:db8::1]:8080/x` 里
     * 「IPv6 地址内部的冒号」与「端口分隔冒号」混在一起，用一条正则一起抓会错位
     * （上游实测把 host 解析成 `2001:db8:`、path 变成 `:8080/x`，
     * 合成出来的报文首行成了 `GET :8080/x HTTP/1.1`）。
     * 所以分两步：先按方括号取 authority，再按「最后一个冒号」剥端口。
     */
    fun parseTarget(target: String?): Target? {
        val t = target?.trim().orEmpty()
        if (t.isEmpty()) return null
        val url = Regex("^([a-z][a-z0-9+.-]*)://([^/?#\\s]+)([^?#\\s]*)?", RegexOption.IGNORE_CASE).find(t)
        if (url != null) {
            val scheme = url.groupValues[1].lowercase()
            val authority = url.groupValues[2]
            val rawPath = url.groupValues[3]
            val path = if (rawPath.isEmpty()) "/" else rawPath
            Regex("^\\[([^\\]]+)\\](?::(\\d{1,5}))?$").find(authority)?.let { bracket ->
                val portText = bracket.groupValues[2]
                return Target(
                    scheme = scheme,
                    host = bracket.groupValues[1],
                    port = if (portText.isEmpty()) (if (scheme == "https") 443 else 80) else portText.toInt(),
                    path = path,
                    authority = authority,
                    ipv6 = true,
                )
            }
            val portMatch = Regex(":(\\d{1,5})$").find(authority)
            val host = if (portMatch == null) authority else authority.dropLast(portMatch.value.length)
            return Target(
                scheme = scheme,
                host = host,
                port = if (portMatch == null) (if (scheme == "https") 443 else 80) else portMatch.groupValues[1].toInt(),
                path = path,
                authority = authority,
                ipv6 = false,
            )
        }
        // 没写协议：host[:port]，同样要先认方括号。
        Regex("^\\[([^\\]]+)\\](?::(\\d{1,5}))?$").find(t)?.let { bracket ->
            val portText = bracket.groupValues[2]
            return Target(null, bracket.groupValues[1], portText.toIntOrNull(), null, t, ipv6 = true)
        }
        Regex("^([^\\s/?#:]+)(?::(\\d{1,5}))?$").find(t)?.let { bare ->
            return Target(null, bare.groupValues[1], bare.groupValues[2].toIntOrNull(), null, t, ipv6 = false)
        }
        val head = Regex("^([^\\s/?#]+)").find(t) ?: return null
        return Target(null, head.groupValues[1], null, null, t, ipv6 = false)
    }

    /** 这条命令看起来是什么工具（用于决定能不能直接当复现命令用）。 */
    fun commandKind(tool: String?): String? {
        val t = tool?.trim().orEmpty()
        if (t.isEmpty()) return null
        return when {
            Regex("^curl\\b", RegexOption.IGNORE_CASE).containsMatchIn(t) -> "curl"
            Regex("^http(ie| x)?\\b", RegexOption.IGNORE_CASE).containsMatchIn(t) ||
                Regex("^http\\s", RegexOption.IGNORE_CASE).containsMatchIn(t) -> "httpie"
            Regex("^(nuclei|ffuf|feroxbuster|gobuster|dirsearch|sqlmap|hydra|nmap|masscan|fscan|gogo)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(t) -> "scanner"
            Regex("^(msfconsole|use\\s|set\\s)", RegexOption.IGNORE_CASE).containsMatchIn(t) -> "msf"
            Regex("^(python|python3|java|go run|node)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t) -> "script"
            else -> "other"
        }
    }

    data class Curl(
        val url: String,
        val method: String,
        val data: String?,
        val headers: List<String>,
        val cookie: String?,
        val insecure: Boolean,
        val followRedirect: Boolean,
    )

    /** 从一条 curl 命令里抽出 URL 与方法。只做保守解析：抽不出返回 null，不猜。 */
    fun parseCurl(tool: String?): Curl? {
        val t = tool?.trim().orEmpty()
        if (!Regex("^curl\\b", RegexOption.IGNORE_CASE).containsMatchIn(t)) return null
        val url = Regex("(https?://[^\\s'\"]+)", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1) ?: return null
        val method = Regex("(?:-X|--request)\\s+([A-Z]+)", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)
        val dataMatch = Regex("(?:-d|--data(?:-raw|-binary|-urlencode)?)\\s+(?:'([^']*)'|\"([^\"]*)\"|(\\S+))", RegexOption.IGNORE_CASE).find(t)
        val data = dataMatch?.let { m ->
            listOf(m.groupValues[1], m.groupValues[2], m.groupValues[3]).firstOrNull { it.isNotEmpty() }
        }
        val headers = Regex("(?:-H|--header)\\s+(?:'([^']*)'|\"([^\"]*)\")", RegexOption.IGNORE_CASE)
            .findAll(t)
            .mapNotNull { m -> listOf(m.groupValues[1], m.groupValues[2]).firstOrNull { it.isNotEmpty() } }
            .filter { it.contains(':') }
            .toList()
        val cookieMatch = Regex("(?:-b|--cookie)\\s+(?:'([^']*)'|\"([^\"]*)\"|(\\S+))", RegexOption.IGNORE_CASE).find(t)
        val cookie = cookieMatch?.let { m ->
            listOf(m.groupValues[1], m.groupValues[2], m.groupValues[3]).firstOrNull { it.isNotEmpty() }
        }
        return Curl(
            url = url,
            method = method?.uppercase() ?: if (data == null) "GET" else "POST",
            data = data,
            headers = headers,
            cookie = cookie,
            insecure = Regex("(^|\\s)-k(\\s|$)|--insecure").containsMatchIn(t),
            followRedirect = Regex("(^|\\s)-L(\\s|$)|--location").containsMatchIn(t),
        )
    }

    /** 合成结果：`synthesized` 必须如实反映「这是推断的，不是抓到的」。 */
    data class HttpRequest(val text: String, val synthesized: Boolean)

    /** 合成一条可粘进 Yakit Repeater 的 HTTP 报文；信息不足返回 null。 */
    fun buildHttpRequest(
        url: String?,
        method: String? = null,
        data: String? = null,
        headers: List<String> = emptyList(),
        cookie: String? = null,
        path: String? = null,
    ): HttpRequest? {
        val target = parseTarget(url) ?: return null
        val scheme = target.scheme ?: "http"
        // IPv6 的 Host 头必须带方括号（RFC 3986），否则是非法头。
        val hostText = if (target.ipv6) "[${target.host}]" else target.host
        val defaultPort = if (scheme == "https") 443 else 80
        val hostHeader = if (target.port != null && target.port != defaultPort) "$hostText:${target.port}" else hostText
        val effectivePath = path?.takeIf { it.isNotEmpty() } ?: target.path ?: "/"
        val effectiveMethod = (method?.takeIf { it.isNotEmpty() } ?: if (data == null) "GET" else "POST").uppercase()

        val outHeaders = headers.toMutableList()
        fun hasHeader(name: String) = outHeaders.any { it.lowercase().startsWith("${name.lowercase()}:") }
        if (!hasHeader("User-Agent")) outHeaders += "User-Agent: Mozilla/5.0"
        if (!hasHeader("Accept")) outHeaders += "Accept: */*"
        if (!cookie.isNullOrEmpty() && !hasHeader("Cookie")) outHeaders += "Cookie: $cookie"
        val body = data.orEmpty()
        if (body.isNotEmpty() && !hasHeader("Content-Type")) {
            outHeaders += if (Regex("^\\{.*\\}$", RegexOption.DOT_MATCHES_ALL).matches(body.trim())) {
                "Content-Type: application/json"
            } else {
                "Content-Type: application/x-www-form-urlencoded"
            }
        }
        // Content-Length 按 UTF-8 字节数，不是字符数。
        if (body.isNotEmpty() && !hasHeader("Content-Length")) {
            outHeaders += "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}"
        }
        if (!hasHeader("Connection")) outHeaders += "Connection: close"

        val text = (listOf("$effectiveMethod $effectivePath HTTP/1.1", "Host: $hostHeader") + outHeaders + listOf("", body))
            .joinToString("\r\n")
        return HttpRequest(text, synthesized = true)
    }

    /**
     * 合成一条「可直接在终端跑」的复现命令。
     * 优先用真实的 curl 命令（原样保留，最可信）；否则按 URL/方法拼一条。
     */
    fun buildCurlCommand(
        tool: String?,
        url: String?,
        method: String? = null,
        data: String? = null,
        cookie: String? = null,
        path: String? = null,
        insecure: Boolean = false,
        followRedirect: Boolean = false,
    ): String? {
        val raw = tool?.trim().orEmpty()
        if (commandKind(raw) == "curl") return raw // 真实命令原样给出，别改。
        val target = parseTarget(url) ?: return null
        val scheme = target.scheme ?: "http"
        val hostText = if (target.ipv6) "[${target.host}]" else target.host
        val defaultPort = if (scheme == "https") 443 else 80
        val hostPart = if (target.port != null && target.port != defaultPort) "$hostText:${target.port}" else hostText
        val fullUrl = "$scheme://$hostPart${path?.takeIf { it.isNotEmpty() } ?: target.path ?: "/"}"
        val parts = mutableListOf("curl -i -s" + (if (insecure) " -k" else "") + (if (followRedirect) " -L" else ""))
        if (!method.isNullOrEmpty() && method.uppercase() != "GET") parts += "-X ${method.uppercase()}"
        if (!cookie.isNullOrEmpty()) parts += "-b '$cookie'"
        if (!data.isNullOrEmpty()) parts += "-d '${data.replace("'", "'\\''")}'"
        parts += "'$fullUrl'"
        return parts.joinToString(" ")
    }

    data class FilledTemplate(val cmd: String, val filled: List<String>, val remaining: List<String>)

    /**
     * 用本条得分**已经记录的信息**填充动作模板里的占位符。
     *
     * 数据库/终端/隧道这类得分项的模板长这样：`mysql -h <host> -u <user> -p -e "..."`，
     * 而这条得分自己就知道 host（target）与账号（挂载的凭据）。把已知部分填进去，
     * 一线拿到报告就能跑；剩下没填的占位符**保持原样并如实报告**，不猜。
     */
    fun fillTemplate(
        template: String?,
        host: String? = null,
        port: String? = null,
        user: String? = null,
        pass: String? = null,
        url: String? = null,
    ): FilledTemplate {
        var cmd = template.orEmpty()
        val filled = mutableListOf<String>()
        val table: List<Pair<String, String?>> = listOf(
            "<host>" to host, "<ip>" to host, "<port>" to port,
            "<user>" to user, "<username>" to user, "<账号>" to user, "<用户名>" to user,
            "<pass>" to pass, "<password>" to pass, "<口令>" to pass, "<密钥>" to pass, "<pwd>" to pass,
            "<url>" to url, "<域名>" to host, "<目标IP>" to host, "<内网IP>" to host,
            "<端口>" to port, "<listen端口>" to port, "<u>" to user, "<p>" to pass,
        )
        table.forEach { (placeholder, value) ->
            if (value.isNullOrEmpty()) return@forEach
            val regex = Regex(Regex.escape(placeholder), RegexOption.IGNORE_CASE)
            if (!regex.containsMatchIn(cmd)) return@forEach
            cmd = regex.replace(cmd, Regex.escapeReplacement(value))
            // 记录填了什么，报告里要如实说明：模板被改过，读者需要知道哪些是补进去的。
            filled += "${placeholder.trim('<', '>')} → $value"
        }
        val remaining = Regex("<[^>\\s]{1,24}>").findAll(cmd).map { it.value }.distinct().toList()
        return FilledTemplate(cmd, filled, remaining)
    }
}