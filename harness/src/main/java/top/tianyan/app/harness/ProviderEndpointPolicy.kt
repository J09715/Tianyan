package top.tianyan.app.harness

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Provider 端点的统一安全策略：baseUrl 必须是 https，仅本机回环允许 http。
 *
 * 用户可自填 baseUrl（自建中转），若不校验 scheme，`Authorization: Bearer <Key>` 会明文出网；
 * 302 到 http 的降级已在 [ProviderTransport] 关闭重定向兜底。对齐 `McpHttpEndpoints` 的
 * `validatedMcpHttpEndpoint` 口径：禁止 https 之外的公网明文，只放行 loopback。
 */
internal fun secureProviderUrl(baseUrl: String, path: String): String {
    val normalized = baseUrl.trim().trimEnd('/')
    val base = "$normalized/".toHttpUrlOrNull()
        ?: throw IllegalArgumentException("模型 baseUrl 不是合法的 HTTP(S) 地址：$baseUrl")
    require(base.isHttps || isLoopbackProviderHost(base.host)) {
        "模型 baseUrl 必须使用 https（仅 localhost/127.0.0.1/::1 允许 http）：$baseUrl"
    }
    return base.resolve(path)?.toString()
        ?: throw IllegalArgumentException("无法拼接模型请求地址：$baseUrl/$path")
}

internal fun isLoopbackProviderHost(host: String): Boolean =
    host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
