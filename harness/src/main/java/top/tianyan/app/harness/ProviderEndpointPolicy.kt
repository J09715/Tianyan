package top.tianyan.app.harness

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import top.tianyan.app.core.common.net.PrivateNetworkHosts

/**
 * Provider 端点的统一安全策略：公网必须 https；回环与私有网段（10/8、172.16/12、
 * 192.168/16、169.254/16、100.64/10、IPv6 ULA 与链路本地、.local/.lan/.internal）允许 http。
 *
 * 局域网里的自建推理服务（Ollama、LM Studio、vLLM、one-api、OpenWebUI）默认只监听明文 HTTP，
 * 早先「仅 loopback 允许 http」的口径会把它们全部挡掉，表现为模型列表拉不出来、
 * 对话请求直接抛 baseUrl 非法。放行范围严格限制在私有网段，公网主机仍然强制 https，
 * `Authorization: Bearer <Key>` 不会明文发到公网；302 到 http 的降级已在 [ProviderTransport]
 * 关闭重定向兜底。
 */
internal fun secureProviderUrl(baseUrl: String, path: String): String {
    val normalized = baseUrl.trim().trimEnd('/')
    val base = "$normalized/".toHttpUrlOrNull()
        ?: throw IllegalArgumentException("模型 baseUrl 不是合法的 HTTP(S) 地址：$baseUrl")
    require(base.isHttps || isCleartextProviderHost(base.host)) {
        "模型 baseUrl 必须使用 https（局域网私有地址与 localhost 允许 http）：$baseUrl"
    }
    return base.resolve(path)?.toString()
        ?: throw IllegalArgumentException("无法拼接模型请求地址：$baseUrl/$path")
}

internal fun isLoopbackProviderHost(host: String): Boolean =
    host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"

/** 允许明文 HTTP 的 Provider 主机：回环 + 私有网段。 */
internal fun isCleartextProviderHost(host: String): Boolean = PrivateNetworkHosts.isPrivateOrLoopback(host)
