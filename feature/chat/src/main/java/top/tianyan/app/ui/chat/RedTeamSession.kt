package top.tianyan.app.ui.chat

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import top.tianyan.app.core.database.HarnessSessionRepository
import top.tianyan.app.core.database.updateRedTeamBinding
import top.tianyan.app.core.database.RedTeamFactEntity
import top.tianyan.app.core.model.RedTeamMode
import top.tianyan.app.core.model.RedTeamPhase

/**
 * 红队会话状态的实现（从 ChatViewModel 拆出）。
 *
 * 为什么拆：ChatViewModel 已接近文件行数棘轮上限，而红队状态与主对话装配无耦合——
 * 它只依赖「当前会话 id + 事实仓库 + 会话仓库」。
 */

/**
 * 当前会话的红队事实流（资产 / 端口 / 漏洞 / 得分）。
 *
 * 非红队会话返回空；数据源跟着会话切换（flatMapLatest），
 * 不会把上一个会话的事实带过来。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun ChatViewModel.redTeamFactStream(): Flow<List<RedTeamFactEntity>> =
    currentSessionId.flatMapLatest { id ->
        if (id.isBlank()) flowOf(emptyList()) else redTeamFactsSource.observeForSession(id)
    }

/**
 * 绑定红队目标与授权范围（用户在工作台面板里显式确认）。
 *
 * 一次性写入 mode/target/scope/phase 四列：分几次改会出现「目标已换、范围还是旧的」
 * 的中间态，而后续所有越界校验都挂在这个范围上。
 */
internal fun ChatViewModel.bindRedTeam(
    sessions: HarnessSessionRepository,
    target: String,
    scope: String,
) {
    val sessionId = currentSessionId.value.takeIf { it.isNotBlank() } ?: return
    val coordinator = redTeamCoordinatorRef
    viewModelScope.launch(Dispatchers.IO) {
        if (coordinator != null) {
            // 走协调器而不是直接写会话字段：协调器会同时落一条 engagement 事实，
            // 「目标」页签正是从这条事实渲染的。之前绕过它只改 harness_sessions，
            // 导致绑完目标在控制台里看不到 —— 会话字段有值、事实库却是空的。
            runCatching { coordinator.bind(sessionId, target.trim(), scope.trim()) }
                .onSuccess { return@launch }
            // 协调器拒绝（例如 scope 为空）时回落到只写会话字段，至少让绑定生效，
            // 不至于因为事实写入失败把用户的操作整个吞掉。
        }
        sessions.updateRedTeamBinding(
            id = sessionId,
            mode = RedTeamMode.RED_TEAM.id,
            target = target.trim().ifBlank { null },
            scope = scope,
            phase = RedTeamPhase.RECON.id,
            updatedAt = System.currentTimeMillis(),
        )
    }
}
