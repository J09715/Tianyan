package top.tianyan.app.harness.mcp

import top.tianyan.app.core.model.BuiltinMcpPresets
import top.tianyan.app.core.model.McpAuthMode
import top.tianyan.app.core.model.McpServerConfig
import top.tianyan.app.core.model.validateMcpHttpHeaders
import top.tianyan.app.harness.mcp.oauth.McpOAuthTokenProvider
import top.tianyan.app.harness.mcp.server.BuiltinBrowserMcpAccess

internal suspend fun resolveMcpHttpHeaders(server: McpServerConfig, oauth: McpOAuthTokenProvider?): Map<String, String> {
    check(server.headersReadError == null) { server.headersReadError.orEmpty() }
    validateMcpHttpHeaders(server.headers)
    val headers = server.headers.toMutableMap()
    val authorization = headers.keys.firstOrNull { it.equals("Authorization", ignoreCase = true) }
    require(authorization == null || server.authMode != McpAuthMode.OAUTH) { "自定义 Authorization 与 OAuth 不能同时启用" }
    val token = server.authToken.takeIf { it.isNotBlank() }
        ?: if (server.id == BuiltinMcpPresets.BROWSER_BUILTIN_ID) BuiltinBrowserMcpAccess.token
        else if (authorization == null) oauth?.accessToken(server) else null
    if (!token.isNullOrBlank()) {
        authorization?.let(headers::remove)
        headers["Authorization"] = "Bearer $token"
    }
    validateMcpHttpHeaders(headers)
    return headers
}
