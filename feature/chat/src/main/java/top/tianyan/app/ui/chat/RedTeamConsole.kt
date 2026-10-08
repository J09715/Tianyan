package top.tianyan.app.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.database.HarnessSessionEntity
import top.tianyan.app.core.database.RedTeamFactEntity
import top.tianyan.app.core.model.RedTeamConsoleModel
import top.tianyan.app.core.model.RedTeamPhase
import top.tianyan.app.core.model.RedTeamPreflightReport
import top.tianyan.app.harness.redteam.RedTeamSkillStore
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeTextButton

/**
 * RedTeam 控制台：多页签外壳。
 *
 * 页签顺序与上游 `client.js` 的三页签一致（资产测绘 / 智能体提示词 / 技能库），
 * 前面多留一个「概览」放本移植原有的靶标绑定、阶段推进、技能体检——
 * 这三块上游没有，删掉就是功能缩水，所以用页签并存而不是替换。
 *
 * 外壳只做「页签 + 页签内容」的编排，不吞任何页面自己的卡片：
 * `RuntimeCard` 里再套 `RuntimeCard` 会让玻璃背景叠两层，视觉上是一团糊。
 */
@Composable
internal fun RedTeamConsole(
    session: HarnessSessionEntity,
    facts: List<RedTeamFactEntity>,
    state: RedTeamConsoleState,
    onBindTarget: (String, String) -> Unit,
    onToggle: () -> Unit,
    onSelectTab: (RedTeamConsoleTab) -> Unit,
    onRefresh: () -> Unit,
    onSelectSegment: (String?) -> Unit,
    onQueryChange: (String) -> Unit,
    onServiceChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onSubmitQuery: () -> Unit,
    onProvenanceChange: (String?) -> Unit,
    onSortChange: (RedTeamConsoleModel.Sort) -> Unit,
    onViewChange: (RedTeamConsoleView) -> Unit,
    onToggleAsset: (String) -> Unit,
    onSelectRole: (String) -> Unit,
    onRoleDraftChange: (String) -> Unit,
    onSaveRole: () -> Unit,
    onResetRole: () -> Unit,
    onSelectSkill: (String) -> Unit,
    onNewSkill: () -> Unit,
    onSkillDraftChange: ((RedTeamSkillStore.Skill) -> RedTeamSkillStore.Skill) -> Unit,
    onSaveSkill: () -> Unit,
    onDeleteSkill: () -> Unit,
    skillHealth: RedTeamPreflightReport? = null,
    onRefreshSkillHealth: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ConsoleChrome(
            session = session,
            state = state,
            onToggle = onToggle,
            onSelectTab = onSelectTab,
            onRefresh = onRefresh,
        )

        when (state.tab) {
            RedTeamConsoleTab.OVERVIEW -> RedTeamPanel(
                session = session,
                facts = facts,
                onBindTarget = onBindTarget,
                skillHealth = skillHealth,
                onRefreshSkillHealth = onRefreshSkillHealth,
            )

            RedTeamConsoleTab.ASSETS -> RedTeamAssetsTab(
                state = state,
                onSelectSegment = onSelectSegment,
                onQueryChange = onQueryChange,
                onServiceChange = onServiceChange,
                onPortChange = onPortChange,
                onSubmitQuery = onSubmitQuery,
                onProvenanceChange = onProvenanceChange,
                onSortChange = onSortChange,
                onViewChange = onViewChange,
                onToggleAsset = onToggleAsset,
            )

            RedTeamConsoleTab.PROMPTS -> RedTeamPromptsTab(
                state = state,
                onSelectRole = onSelectRole,
                onDraftChange = onRoleDraftChange,
                onSave = onSaveRole,
                onReset = onResetRole,
            )

            RedTeamConsoleTab.SKILLS -> RedTeamSkillsTab(
                state = state,
                onSelectSkill = onSelectSkill,
                onNewSkill = onNewSkill,
                onDraftChange = onSkillDraftChange,
                onSave = onSaveSkill,
                onDelete = onDeleteSkill,
            )
        }
    }
}

/** 顶栏 + 页签条。 */
@Composable
private fun ConsoleChrome(
    session: HarnessSessionEntity,
    state: RedTeamConsoleState,
    onToggle: () -> Unit,
    onSelectTab: (RedTeamConsoleTab) -> Unit,
    onRefresh: () -> Unit,
) {
    val phase = RedTeamPhase.fromId(session.redTeamPhase)
    RuntimeCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.28f),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RuntimeIcon(RuntimeIconName.Shield, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
            Column(Modifier.weight(1f)) {
                Text(
                    "RedTeam 控制台",
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(session.redTeamTarget?.takeIf { it.isNotBlank() } ?: "未绑定靶标")
                        append(" · ")
                        append(phase.displayName)
                        if (state.stats.assets > 0) append(" · ${state.stats.assets} 资产")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            RuntimeTextButton(onClick = onRefresh, enabled = !state.loading) {
                RuntimeIcon(RuntimeIconName.Refresh, Modifier.size(16.dp))
            }
            RuntimeTextButton(onClick = onToggle) {
                Text(if (state.open) "收起" else "展开", style = MaterialTheme.typography.labelMedium)
            }
        }

        if (state.open) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RedTeamConsoleTab.entries.forEach { tab ->
                    val selected = tab == state.tab
                    RuntimeTextButton(
                        onClick = { onSelectTab(tab) },
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            contentColor = if (selected) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        ),
                    ) {
                        Text(
                            tab.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
