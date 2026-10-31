package top.tianyan.app.harness

/**
 * 红队工具里**纯读取**的动作名。
 *
 * 这些动作不产生演练事实、不派发子智能体、不改评分，因此不该弹审批打断用户。
 * 其余动作（写资产/漏洞/评分、派活、改阶段）都会落库或启动后台工作，一律走 high 审批。
 *
 * 单独成文件：ApprovalPolicyEngine 已到行数棘轮上限，而这是一份纯常量清单。
 */
internal object RedTeamReadOnlyActions {
    val ACTIONS: Set<String> = setOf(
        "asset_list", "asset_get", "vuln_list", "vuln_get", "poc_list", "poc_get",
        "score_list", "session_info", "agent_slot", "skill_health", "graph_view",
    )
}
