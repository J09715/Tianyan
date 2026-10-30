package top.tianyan.app.harness.redteam

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.tianyan.app.core.database.HarnessSessionRepository

/**
 * 会话是否已开启红队模式。
 *
 * 工具路由据此决定放行还是拒绝 `red_team` 工具：未绑定红队的会话即使模型硬调，
 * 也会拿到明确的拒绝说明，而不是悄悄获得写演练事实、派发子智能体的能力。
 *
 * 写成独立函数而不是 [RedTeamCoordinator] 的成员：协调器本体已到行数棘轮上限，
 * 而这段只依赖公开的会话仓库。
 */
suspend fun isRedTeamSession(sessions: HarnessSessionRepository, sessionId: String): Boolean =
    withContext(Dispatchers.IO) { sessions.findById(sessionId)?.redTeamMode == "red_team" }
