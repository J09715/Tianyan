package top.tianyan.app.harness.agent

/**
 * MCP 被控端常驻前台服务的拉起桥：Service 注册在壳层 app 模块，harness 不能反向依赖，
 * 通过该接口 + Koin 绑定解耦（实现在 app 的 AgentMcpForegroundLauncherImpl）。
 *
 * 服务只负责保活（前台通知 + 电源租约），MCP server 本身的启停仍由 [AgentMcpBootstrap] 掌管。
 */
interface AgentMcpForegroundLauncher {
    fun start()
    fun stop()
}
