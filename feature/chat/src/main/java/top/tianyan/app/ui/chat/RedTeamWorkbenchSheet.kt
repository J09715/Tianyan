package top.tianyan.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tianyan.app.core.database.HarnessSessionEntity

/**
 * 红队工作台整页弹层。
 *
 * 挂载的是**完整控制台**（[RedTeamConsole]：资产测绘 / 漏洞战果 / 得分目标 / 报告 /
 * 会话隧道 / 攻击链 / 目标 / 知识库 / 凭据文件 / 提示词 / 技能库），
 * 而不是原来的精简面板（[RedTeamPanel]，只有目标绑定 + 事实列表）。
 *
 * 为什么换：精简面板只回答「绑了谁、有几条事实」，而红队演练真正要看的
 * 资产分布、得分进度、报告回放、技能库都在控制台里。之前只挂精简面板，
 * 等于把 90% 的功能做完了却没入口 —— 用户看到的就只有一个绑定表单。
 *
 * 控制器（[RedTeamConsoleController]）挂在 ChatViewModel 上、随会话走：
 * 弹层关闭再打开不该重新拉一遍事实库。
 */
@Composable
internal fun RedTeamWorkbenchSheet(
    session: HarnessSessionEntity,
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
) {
    val controller = viewModel.redTeamConsoleController
    val state by controller.state.collectAsStateWithLifecycle()
    val facts by viewModel.redTeamFacts.collectAsStateWithLifecycle(initialValue = emptyList())
    val skillHealth by controller.skillHealth.collectAsStateWithLifecycle()

    // 首次进入拉一次快照 + 技能体检：技能能列出来 ≠ 能跑，缺环境变量要在开工前提示。
    LaunchedEffect(session.id) {
        controller.refresh()
        controller.refreshSkillHealth()
    }

    RedTeamConsole(
        session = session,
        facts = facts,
        state = state,
        onBindTarget = { target, scope ->
            viewModel.bindRedTeamTarget(target, scope)
            controller.onTargetBound()
        },
        onDismiss = onDismiss,
        onSelectTab = controller::selectTab,
        onRefresh = controller::refresh,
        onSelectSegment = controller::selectSegment,
        onQueryChange = controller::updateQuery,
        onServiceChange = controller::updateService,
        onPortChange = controller::updatePort,
        onSubmitQuery = controller::submitQuery,
        onProvenanceChange = controller::setProvenance,
        onSortChange = controller::setSort,
        onViewChange = controller::setView,
        onToggleAsset = controller::toggleAsset,
        onSelectRole = controller::selectRole,
        onRoleDraftChange = controller::updateRoleDraft,
        onSaveRole = controller::saveRole,
        onResetRole = controller::resetRole,
        onSelectSkill = controller::selectSkill,
        onNewSkill = controller::newSkill,
        onSkillDraftChange = controller::updateSkillDraft,
        onSaveSkill = controller::saveSkill,
        onDeleteSkill = controller::deleteSkill,
        onSetAgentsMax = controller::setAgentsMax,
        skillHealth = skillHealth,
        onRefreshSkillHealth = controller::refreshSkillHealth,
    )
}
