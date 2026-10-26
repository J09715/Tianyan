package top.tianyan.app.harness.mcp.server

import java.security.MessageDigest

/**
 * MCP server 侧 Token 校验。
 *
 * 原则：
 * - Token 恒为必填：Android 上 127.0.0.1 并不按 UID 隔离，任意应用都能直接 POST /mcp 驱动浏览器，
 *   loopback 模式同样必须携带 Bearer Token，不存在"仅进程内自用"的豁免；
 * - 比较使用常量时间算法（[MessageDigest.isEqual]），避免时序侧信道逐字节泄露；
 * - Token 不在响应日志/异常中露出。
 */
object McpAuthFilter {

    fun isAcceptable(
        authHeader: String?,
        configuredToken: String?,
    ): Boolean {
        if (configuredToken.isNullOrEmpty()) return false
        if (authHeader == null) return false
        return MessageDigest.isEqual(
            "Bearer $configuredToken".toByteArray(Charsets.UTF_8),
            authHeader.toByteArray(Charsets.UTF_8),
        )
    }
}

/**
 * 内置浏览器 MCP server 的运行时访问参数供给点（进程内单例）：
 *  - [token]：[top.tianyan.app.harness.browser.BrowserMcpBootstrap] 启动时生成并写入，
 *    [top.tianyan.app.harness.mcp.McpHttpTransport] 自环访问内置 server 时携带，通过 [McpAuthFilter] 校验；
 *  - [port]：实际绑定端口。首选端口（8787）被占用时 server 会顺延使用相邻端口，
 *    自环客户端须以本值为准替换静态预设 URL 中的端口。
 */
object BuiltinBrowserMcpAccess {
    @Volatile var token: String? = null
    @Volatile var port: Int? = null
}

/**
 * MCP 被控端（外部 AI 客户端控制本 App）的运行时访问参数供给点（进程内单例）。
 *
 * 与 [BuiltinBrowserMcpAccess] 的区别：被控端是**独立端点 + 持久化 token**，
 * 供外部客户端（Claude Desktop / Cursor 等）长期复用；token 由设置页管理，
 * 这里只暴露实际绑定端口与运行状态给设置页展示。
 */
object AgentMcpAccess {
    @Volatile var running: Boolean = false
    @Volatile var port: Int? = null
}
