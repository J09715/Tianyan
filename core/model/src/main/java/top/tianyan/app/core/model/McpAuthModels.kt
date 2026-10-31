package top.tianyan.app.core.model

/** MCP 服务的认证模式；运行时状态不进入 MCP 服务配置导出。 */
enum class McpAuthMode {
    NONE,
    STATIC_BEARER,
    OAUTH,
}

/** UI 与连接状态分离的 OAuth 状态。不得携带 access/refresh token 明文。 */
sealed interface McpAuthState {
    data object Unsupported : McpAuthState
    data object Unauthenticated : McpAuthState
    data object Authorizing : McpAuthState
    data class Authorized(val expiresAt: Long?, val scope: String? = null) : McpAuthState
    data class Error(val message: String) : McpAuthState
}
