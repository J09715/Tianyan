package top.tianyan.app.core.model

/**
 * 从用户的一句话里认出演练目标。
 *
 * 红队会话不该逼用户去面板里手填目标：用户本来就会说「帮我测一下 example.com」，
 * 这句话里已经有目标了，再让人抄一遍到表单里是多余的仪式。
 *
 * 认得住的形态（按优先级）：URL、IPv4、CIDR、域名、IPv6。
 * 认不出来就返回 null —— 由调用方决定「不绑定」而不是瞎绑一个词，
 * 绑错目标比不绑危险得多：后续所有派活、范围校验都挂在它上面。
 */
object RedTeamTargetExtractor {

    /** 一个识别结果：目标本体 + 它是在哪一段文本里被认出来的（给界面高亮/提示用）。 */
    data class Hit(val target: String, val kind: Kind) {
        enum class Kind { URL, IPV4, CIDR, DOMAIN, IPV6 }
    }

    /**
     * 常见的一级域名后缀白名单。
     *
     * 不能只用「有点号就是域名」：`10.0.0.5` 是 IP，`v1.2.3` 是版本号，
     * `index.js` 是文件名。要求后缀是已知 TLD 或公司内网常见后缀，误判会显著下降。
     */
    private val KNOWN_TLDS = setOf(
        "com", "net", "org", "edu", "gov", "mil", "int", "io", "co", "cn", "jp", "kr", "ru",
        "de", "fr", "uk", "us", "hk", "tw", "sg", "au", "ca", "in", "br", "nl", "it", "es",
        "ch", "se", "no", "fi", "dk", "pl", "cz", "at", "be", "ie", "nz", "za", "mx", "ar",
        "top", "xyz", "info", "biz", "dev", "app", "cloud", "site", "online", "tech", "shop",
        "local", "internal", "lan", "corp", "test", "example",
    )

    private val URL_REGEX = Regex("""\b[a-z][a-z0-9+.-]*://[^\s，。、；：！？"'<>（）()\[\]{}]+""", RegexOption.IGNORE_CASE)
    private val CIDR_REGEX = Regex("""\b(\d{1,3}(?:\.\d{1,3}){3})/(\d{1,2})\b""")
    private val IPV4_REGEX = Regex("""\b(\d{1,3}(?:\.\d{1,3}){3})\b""")
    private val IPV6_REGEX = Regex("""\b((?:[0-9a-fA-F]{1,4}:){2,7}[0-9a-fA-F]{1,4})\b""")
    private val DOMAIN_REGEX = Regex("""\b((?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,24})\b""")

    /**
     * 认目标。按 URL → CIDR → IPv4 → 域名 → IPv6 的顺序找：
     * 先找信息量大的形态，避免 URL 里的主机名被域名规则先截走。
     */
    fun extract(text: String?): Hit? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return null

        URL_REGEX.find(raw)?.let { match ->
            val url = match.value.trimEnd('.', ',', ';', ':', '!', '?', '、', '。', '）', ')')
            // URL 里的 host 单独再确认一次，避免把 `see://not a url` 当目标。
            if (url.isNotEmpty()) return Hit(url, Hit.Kind.URL)
        }

        CIDR_REGEX.find(raw)?.let { match ->
            val ip = match.groupValues[1]
            val prefix = match.groupValues[2].toIntOrNull()
            if (isValidIpv4(ip) && prefix != null && prefix in 0..32) {
                return Hit("$ip/$prefix", Hit.Kind.CIDR)
            }
        }

        IPV4_REGEX.find(raw)?.let { match ->
            val ip = match.groupValues[1]
            if (isValidIpv4(ip)) return Hit(ip, Hit.Kind.IPV4)
        }

        // 域名要挑后缀合法的，否则 `1.2.3` 这类版本号会被当成目标。
        DOMAIN_REGEX.findAll(raw).forEach { match ->
            val candidate = match.value
            val tld = candidate.substringAfterLast('.').lowercase()
            if (tld in KNOWN_TLDS) return Hit(candidate.lowercase(), Hit.Kind.DOMAIN)
        }

        IPV6_REGEX.find(raw)?.let { match ->
            val candidate = match.value
            // 必须真的是 IPv6：`aa:bb` 这种普通文本不该被当成目标。
            if (RedTeamIpUtils.isIpv6(candidate)) return Hit(candidate, Hit.Kind.IPV6)
        }

        return null
    }

    /**
     * 从用户消息推出默认授权范围。
     *
     * 单个域名 → 该域名（含子域）；单个 IP → 它的 /24（IPv6 是 /64）；
     * CIDR → 原样。范围比目标宽一档是有意的：只授权一个 IP 的话，
     * 同段内的横向路径立刻全部越界，演练第一步就卡住。
     */
    fun defaultScope(hit: Hit): String = when (hit.kind) {
        Hit.Kind.URL -> RedTeamReportReplay.parseTarget(hit.target)?.host?.let { defaultScope(Hit(it, kindOf(it))) }
            ?: hit.target
        Hit.Kind.CIDR -> hit.target
        Hit.Kind.IPV4, Hit.Kind.IPV6 -> RedTeamIpUtils.cidrOf(hit.target)
        Hit.Kind.DOMAIN -> hit.target
    }

    private fun kindOf(host: String): Hit.Kind = when {
        RedTeamIpUtils.ipToInt(host) != null -> Hit.Kind.IPV4
        RedTeamIpUtils.isIpv6(host) -> Hit.Kind.IPV6
        else -> Hit.Kind.DOMAIN
    }

    private fun isValidIpv4(text: String): Boolean =
        text.split('.').let { parts ->
            parts.size == 4 && parts.all { part ->
                part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
                    part.toIntOrNull()?.let { it in 0..255 } == true
            }
        }
}
