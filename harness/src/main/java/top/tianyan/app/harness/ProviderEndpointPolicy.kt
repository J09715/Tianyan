package top.tianyan.app.harness

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import top.tianyan.app.core.common.net.PrivateNetworkHosts

/**
 * Provider 端点的统一策略：不限制主机，也不限制 scheme —— Base URL 由用户自己填写，
 * 自建中转、公网裸 IP、局域网服务、localhost 一视同仁。
 *
 * 为什么不再强制 https：天衍的模型端点全部是用户自己的服务。很多自建中转（one-api、
 * new-api、vLLM）只监听 80 端口没有证书，公网裸 IP 更是根本申请不到证书；早先按
 * 「公网必须 https」卡一次，用户拿不到任何可用的填法。协议层能做的边界只有两条，
 * 都保留着：一是 302 不跟随（[ProviderTransport] 里 `followRedirects(false)`），
 * 避免 https 被降级到 http 时把 `Authorization` 头带去明文连接；二是 URL 必须是
 * 合法的 HTTP(S) 绝对地址、不得携带 userinfo。
 *
 * [isCleartextProviderHost] 仍然保留，用于需要区分「明文是否仅限内网」的调用方
 * （例如判断是否需要提示用户 Key 会明文出网）。
 */
internal fun secureProviderUrl(baseUrl: String, path: String): String {
    val normalized = baseUrl.trim().trimEnd('/')
    val base = "$normalized/".toHttpUrlOrNull()
        ?: throw IllegalArgumentException("模型 baseUrl 不是合法的 HTTP(S) 地址：$baseUrl")
    require(base.username.isEmpty() && base.password.isEmpty()) {
        "模型 baseUrl 不能内嵌用户名或密码：$baseUrl"
    }
    return base.resolve(path)?.toString()
        ?: throw IllegalArgumentException("无法拼接模型请求地址：$baseUrl/$path")
}

internal fun isLoopbackProviderHost(host: String): Boolean =
    host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"

/** 明文是否只落在内网/回环。用于 UI 提示，不再用于拦截。 */
internal fun isCleartextProviderHost(host: String): Boolean = PrivateNetworkHosts.isPrivateOrLoopback(host)
