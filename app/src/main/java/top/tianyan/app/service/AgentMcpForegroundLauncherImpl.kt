package top.tianyan.app.service

import android.content.Context
import top.tianyan.app.harness.agent.AgentMcpForegroundLauncher

/** 壳层适配：harness 不依赖 app，MCP 被控端的常驻前台服务只能经该桥接接口拉起。 */
class AgentMcpForegroundLauncherImpl(
    private val context: Context,
) : AgentMcpForegroundLauncher {
    override fun start() = AgentMcpForegroundService.start(context)

    override fun stop() = AgentMcpForegroundService.stop(context)
}
