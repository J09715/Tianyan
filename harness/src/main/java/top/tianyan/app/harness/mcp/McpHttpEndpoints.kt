package top.tianyan.app.harness.mcp

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import top.tianyan.app.core.common.net.PrivateNetworkHosts
import top.tianyan.app.core.model.BuiltinMcpPresets

/**
 * 解析内置 browser server 的"实际"URL：仅当日标服务是内置 browser 且已绑定运行时端口时，
 * 用运行时端口替换静态预设 URL 里的首选端口；否则原样返回。纯函数，便于对端口顺延做回归测试。
 *
 * 这是"发现与调用分离"的分界：provider 可见工具 schema（[top.tianyan.app.harness.mcp.server.McpToolDispatcher.listTools]）
 * 不含任何端口，真实端口只在 tools/call 连接阶段经此解析注入，因此绑定端口变化不改变 provider 可见表面。
 */
internal fun resolveBrowserEffectiveUrl(serverUrl: String, serverId: String, runtimePort: Int?): String {
    if (runtimePort == null) return serverUrl
    if (serverId != BuiltinMcpPresets.BROWSER_BUILTIN_ID) return serverUrl
    return serverUrl.replace(Regex("^(https?://[^/:]+):\\d+"), "$1:$runtimePort")
}

/**
 * 明文 MCP 主机判定：保留用于提示，不再用于拦截。
 *
 * MCP 服务同样由用户自己配置（局域网、公网裸 IP、自建中转都有），因此
 * [validatedMcpHttpEndpoint] 不再按主机白名单拒绝明文，只校验地址合法性与同源约束。
 */
internal fun isAllowedCleartextMcpHost(host: String): Boolean =
    PrivateNetworkHosts.isPrivateOrLoopback(host)

internal fun validatedMcpHttpEndpoint(baseUrl: String): HttpUrl {
    val url = baseUrl.trim().toHttpUrl()
    require(url.username.isEmpty() && url.password.isEmpty()) {
        "MCP 端点不能内嵌用户名或密码：$baseUrl"
    }
    return url
}

internal fun validatedDerivedMcpEndpoint(base: HttpUrl, resolved: HttpUrl): HttpUrl {
    require(base.scheme == resolved.scheme && base.host == resolved.host && base.port == resolved.port) {
        "MCP SSE endpoint 必须与初始服务同源"
    }
    return validatedMcpHttpEndpoint(resolved.toString())
}
