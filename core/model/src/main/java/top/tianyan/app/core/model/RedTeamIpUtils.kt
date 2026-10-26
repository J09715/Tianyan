package top.tianyan.app.core.model

/**
 * IP / 文本归一化，移植自上游 `ip-utils.js`。
 *
 * 这些是纯函数，不碰数据库，所以放在 `:core:model` 以便单测直接覆盖。
 * 归段错误会让资产测绘面板冒出假网段、资产归属也错，所以每条都有对照测试。
 */
object RedTeamIpUtils {

    /**
     * IPv4 → 32 位整数（用于自然排序）。
     * IPv6 走不了这条路（`split('.')` 只会得到一段），返回 null —— 让排序退回按文本比，
     * 不要编造一个 0 或把地址拼坏。
     */
    fun ipToInt(ip: String?): Long? {
        val text = ip?.trim().orEmpty()
        if (!Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(text)) return null
        return text.split('.').fold(0L) { acc, part -> (acc * 256 + (part.toIntOrNull() ?: return null)) and 0xFFFFFFFFL }
    }

    /** 是不是 IPv6 字面量（含 `::` 缩写与 IPv4-mapped 写法）。 */
    fun isIpv6(ip: String?): Boolean {
        val text = ip?.trim().orEmpty()
        if (!text.contains(':')) return false
        if (!Regex("^[0-9a-fA-F:]+(:\\d{1,3}(\\.\\d{1,3}){3})?$").matches(text)) return false
        return text.split(':').size <= 9
    }

    /** 把 IPv6 展开成 8 组十六进制（只用于取前 4 组算 /64，不追求完全严谨）。 */
    fun expandIpv6(ip: String?): List<String> {
        val text = ip?.trim().orEmpty().trim('[', ']')
        val parts = text.split("::", limit = 2)
        val head = parts[0]
        val tail = parts.getOrNull(1)
        val left = if (head.isEmpty()) emptyList() else head.split(':')
        val right = if (tail.isNullOrEmpty()) emptyList() else tail.split(':')
        val missing = 8 - left.size - right.size
        val groups = left + List(missing.coerceAtLeast(0)) { "0" } + right
        return groups.map { if (it.isEmpty()) "0" else it.lowercase() }
    }

    /**
     * 这个 IP 属于哪个「段」。IPv4 → 前三段 + `.0/24`；IPv6 → 前四组 + `::/64`；
     * 认不出来 → **返回原文**，绝不拼出 `2001:db8::1.0/24` 这种四不像——
     * 一律 `split('.')` 拼 `.0/24` 会让 IPv6 资产得到脏 CIDR，面板左侧多出假网段。
     */
    fun cidrOf(ip: String?): String {
        val text = ip?.trim().orEmpty()
        if (Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(text)) {
            return text.split('.').take(3).joinToString(".") + ".0/24"
        }
        if (isIpv6(text)) return expandIpv6(text).take(4).joinToString(":") + "::/64"
        return text
    }

    /** 内网 / 外网判定，面板按它分组「内网 C 段 / 外网 C 段」。 */
    fun scopeOfIp(ip: String?): String {
        val s = ip.orEmpty()
        // IPv4-mapped 的 ::ffff:a.b.c.d 剥壳后按 IPv4 判。
        Regex("^::ffff:(\\d{1,3}(\\.\\d{1,3}){3})$", RegexOption.IGNORE_CASE).find(s)?.let {
            return scopeOfIp(it.groupValues[1])
        }
        // IPv6：ULA（fc00::/7）与链路本地（fe80::/10）算内网。
        if (Regex("^f[cd][0-9a-f]{2}:", RegexOption.IGNORE_CASE).containsMatchIn(s) ||
            Regex("^fe[89ab][0-9a-f]:", RegexOption.IGNORE_CASE).containsMatchIn(s)
        ) return "internal"
        if (Regex("^(10\\.|192\\.168\\.|127\\.|169\\.254\\.)").containsMatchIn(s)) return "internal"
        Regex("^172\\.(\\d{1,3})\\.").find(s)?.let {
            val n = it.groupValues[1].toIntOrNull() ?: 0
            if (n in 16..31) return "internal"
        }
        Regex("^100\\.(\\d{1,3})\\.").find(s)?.let {
            val n = it.groupValues[1].toIntOrNull() ?: 0
            if (n in 64..127) return "internal"
        }
        return "external"
    }

    /**
     * 把任意名字归一化成 slug（全小写、非字母数字转短横线）。
     * `fallback` 由调用方注入（上游用当前时间），便于测试确定化。
     */
    fun slugify(name: String?, fallback: String = "engagement"): String {
        val s = name?.trim()?.lowercase().orEmpty()
            .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
            .trim('-')
        return if (s.isEmpty()) fallback else s
    }

    /**
     * 把目标（IP / URL / C 段）归一化成攻击文件目录名。
     * IP 或 ip:port 只取 IP（同一 IP 的多个端口归一个文件夹）；URL 只取 host；`10.0.0.0/24` → `10.0.0.0_24`。
     */
    fun slugTarget(target: String?): String {
        val raw = target?.trim().orEmpty()
        if (raw.isEmpty()) return "unknown"
        Regex("^(\\d{1,3}(\\.\\d{1,3}){3})(?::\\d+)?$").find(raw)?.let { return it.groupValues[1] }
        val urlMatch = Regex("^https?://([^/?#]+)", RegexOption.IGNORE_CASE).find(raw)
        val hostPort = urlMatch?.groupValues?.get(1) ?: raw
        val host = hostPort.replace(Regex(":(\\d+)$"), "")
        return host.replace(Regex("[^\\w.\\-]"), "_").trimEnd('_').ifEmpty { "unknown" }
    }

    /**
     * 知识库条目的稳定标识：组件 + 编号/标题，便于智能体直接引用。
     * 标题里往往已经写了 CVE，别再拼一遍（否则会变成 `xxx-cve-2023-21839-cve-2023-21839`）。
     */
    fun slugPoc(title: String?, cve: String?, fallback: String = "poc"): String {
        val t = title?.trim().orEmpty()
        val c = cve?.trim().orEmpty()
        fun flat(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")
        val base = if (c.isNotEmpty() && !flat(t).contains(flat(c))) "$c $t" else t
        val slug = base.lowercase()
            .replace(Regex("cve[-_ ]?(\\d{4})[-_ ]?(\\d+)"), "cve-$1-$2")
            .replace(Regex("[^\\w\\u4e00-\\u9fa5.\\-]+"), "-")
            .replace(Regex("-{2,}"), "-")
            .trim('-')
            .take(80)
            .trimEnd('-')
        return if (slug.isEmpty()) fallback else slug
    }

    /** 按语言给正文一个合适的文件名（智能体可以直接照着运行）。 */
    fun defaultPocFilename(kind: String?, language: String?): String {
        val ext = mapOf(
            "python" to "py", "py" to "py", "go" to "go", "java" to "java",
            "bash" to "sh", "sh" to "sh", "shell" to "sh",
            "js" to "js", "node" to "js", "php" to "php", "ruby" to "rb",
            "powershell" to "ps1", "http" to "http", "nuclei" to "yaml", "yaml" to "yaml",
        )[language?.lowercase().orEmpty()]
        if (ext != null) return "poc.$ext"
        if (kind == "template") return "poc.yaml"
        return "poc.txt"
    }
}