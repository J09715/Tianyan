package top.tianyan.app.core.common.net

/**
 * 私有网络 / 回环主机判定。
 *
 * 天衍允许把模型端点指向局域网内的自建服务（Ollama、LM Studio、one-api、vLLM 等），
 * 这些服务通常只监听明文 HTTP。平台层的 network-security-config 没法表达
 * `192.168.0.0/16` 这类网段，所以明文放行必须按主机名逐个判定：
 * 回环与私有网段允许 http，公网主机一律要求 https。
 *
 * 判定口径与 [top.tianyan.app.core.tools.ProviderEndpointPolicy] 的 URL 归一化保持一致：
 * 这里返回 true 的主机，`normalizeUrl` 会补 `http://`，且端点策略会放行明文。
 */
object PrivateNetworkHosts {

    /** 回环：localhost、127.0.0.0/8、IPv6 ::1。 */
    fun isLoopback(host: String): Boolean {
        val h = normalize(host)
        if (h == "localhost" || h == "::1") return true
        val octets = h.split('.')
        return octets.size == 4 &&
            octets[0] == "127" &&
            octets.all { it.toIntOrNull()?.let { value -> value in 0..255 } == true }
    }

    /**
     * 私有网络、链路本地与运营商级 NAT：允许明文 HTTP。
     *
     * - 10.0.0.0/8
     * - 172.16.0.0/12
     * - 192.168.0.0/16
     * - 169.254.0.0/16（链路本地，含 Android 热点常见网段）
     * - 100.64.0.0/10（CGNAT，Tailscale 等虚拟网卡也落在这里）
     * - IPv6 ULA fc00::/7 与链路本地 fe80::/10
     * - mDNS / 内网专用后缀：.local、.lan、.internal、.home.arpa
     */
    fun isPrivateOrLoopback(host: String): Boolean {
        val h = normalize(host)
        if (h.isEmpty()) return false
        if (isLoopback(h)) return true
        if (h.endsWith(".local") || h.endsWith(".lan") ||
            h.endsWith(".internal") || h.endsWith(".home.arpa")
        ) {
            return true
        }
        if (isUniqueLocalIpv6(h) || isLinkLocalIpv6(h)) return true
        val octets = h.split('.')
        if (octets.size != 4) return false
        val parts = octets.map { it.toIntOrNull() ?: return false }
        if (parts.any { it !in 0..255 }) return false
        val (a, b) = parts
        return when {
            a == 10 -> true
            a == 192 && b == 168 -> true
            a == 172 && b in 16..31 -> true
            a == 169 && b == 254 -> true
            a == 100 && b in 64..127 -> true
            else -> false
        }
    }

    private fun isUniqueLocalIpv6(host: String): Boolean {
        val h = host.removePrefix("[").removeSuffix("]")
        if (!h.contains(':')) return false
        val first = h.substringBefore(':').lowercase()
        val value = first.toIntOrNull(16) ?: return false
        return value and 0xFE00 == 0xFC00
    }

    private fun isLinkLocalIpv6(host: String): Boolean {
        val h = host.removePrefix("[").removeSuffix("]")
        if (!h.contains(':')) return false
        val first = h.substringBefore(':').lowercase()
        val value = first.toIntOrNull(16) ?: return false
        return value and 0xFFC0 == 0xFE80
    }

    private fun normalize(host: String): String =
        host.trim().lowercase().removePrefix("[").removeSuffix("]")
}
