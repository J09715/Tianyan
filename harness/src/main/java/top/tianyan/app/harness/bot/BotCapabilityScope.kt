package top.tianyan.app.harness.bot

/**
 * Bot 级能力白名单端口（DS Bot 能力库语义）：null = 不限制（团队共享库全量可用）；
 * 否则仅返回集合内（服务 id 或名称，小写）的 MCP 服务可被该会话挂载与调用。
 * 由用户在 Bot 详情中配置，成员工具没有修改入口（"使用中锁定"由配置入口唯一性满足）。
 */
fun interface BotCapabilityScope {
    suspend fun allowedMcpServers(sessionId: String): Set<String>?
}
