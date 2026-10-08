package top.tianyan.app.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import top.tianyan.app.ui.components.RuntimeIconButton
import top.tianyan.app.ui.components.RuntimeTopBar
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
    onDismiss: () -> Unit,
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
    onSetAgentsMax: (Int) -> Unit,
    /** 自动绑定提示；界面显示一次后回调清掉。 */
    autoBindNotice: String? = null,
    onConsumeAutoBind: () -> Unit = {},
    skillHealth: RedTeamPreflightReport? = null,
    onRefreshSkillHealth: (() -> Unit)? = null,
) {
    // 整页而不是叠在对话上：和 Git 面板同一套语义（fillMaxSize + RuntimeTopBar + BackHandler）。
    // 原来它是个 fillMaxWidth 的卡片堆，和 Scaffold 里的对话内容并存——
    // 对话正文、快捷开始、输入框会从半透明卡片下面透出来，看着就是「两页糊在一起」。
    BackHandler(onBack = onDismiss)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        RuntimeTopBar(
            title = "RedTeam 控制台",
            onBack = onDismiss,
            actions = {
                RuntimeIconButton(onClick = onRefresh, enabled = !state.loading) {
                    RuntimeIcon(RuntimeIconName.Refresh, Modifier.size(18.dp))
                }
            },
        )
        ConsoleChrome(
            session = session,
            state = state,
            onSelectTab = onSelectTab,
        )

        when (state.tab) {
            RedTeamConsoleTab.OVERVIEW -> {
                // 自动绑定提示：用户根本没进面板就绑上了目标，不提示一下会以为是系统乱改。
                autoBindNotice?.let { notice ->
                    androidx.compose.runtime.LaunchedEffect(notice) { onConsumeAutoBind() }
                    RuntimeCard(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(notice, style = MaterialTheme.typography.labelMedium)
                    }
                }
                RedTeamAgentsCard(state = state, onSetMax = onSetAgentsMax)
                RedTeamPanel(
                    session = session,
                    facts = facts,
                    onBindTarget = onBindTarget,
                    skillHealth = skillHealth,
                    onRefreshSkillHealth = onRefreshSkillHealth,
                )
            }

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

            RedTeamConsoleTab.SESSIONS,
            RedTeamConsoleTab.VULNS,
            RedTeamConsoleTab.CHAIN,
            RedTeamConsoleTab.SCORES,
            RedTeamConsoleTab.TARGETS,
            RedTeamConsoleTab.REPORT,
            RedTeamConsoleTab.KNOWLEDGE,
            -> RedTeamSectionTab(
                tab = state.tab,
                rows = state.sectionRows[state.tab.sectionId].orEmpty(),
                digest = state.sections.firstOrNull { it.id == state.tab.sectionId },
                loading = state.loading,
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

/**
 * 页签 → 分区 id。
 *
 * 面板只需要 id 去查已加载的行；`Section` 枚举本体在 core/model，
 * 这里不复制一份 kinds 归属，避免两处定义漂移。
 */
internal val RedTeamConsoleTab.sectionId: String
    get() = when (this) {
        RedTeamConsoleTab.SESSIONS -> "sessions"
        RedTeamConsoleTab.VULNS -> "vulns"
        RedTeamConsoleTab.CHAIN -> "chain"
        RedTeamConsoleTab.SCORES -> "scores"
        RedTeamConsoleTab.TARGETS -> "targets"
        RedTeamConsoleTab.REPORT -> "report"
        RedTeamConsoleTab.KNOWLEDGE -> "knowledge"
        else -> ""
    }

/**
 * 通用分区页：把一类事实按「标题 · 目标 · 状态」列出来。
 *
 * 这九个分区（会话隧道 / 漏洞战果 / 攻击链 / 得分 / 目标 / 报告 / 知识 …）
 * 是上游 `consoleDigest` 里就有的分类，之前本移植只在概览页签里混着显示，
 * 用户找不到对应入口，会以为「没有这些数据」。
 */
@Composable
private fun RedTeamSectionTab(
    tab: RedTeamConsoleTab,
    rows: List<RedTeamConsoleModel.Fact>,
    digest: RedTeamConsoleModel.SectionDigest?,
    loading: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RuntimeCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(10.dp)) {
            Text(
                buildString {
                    append(tab.label)
                    append(" · ${rows.size} 条")
                    digest?.latestAt?.let { append(" · 最近 ${formatEpochTime(it)}") }
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            if (rows.isEmpty()) {
                Text(
                    if (loading) "加载中…" else "这一类还没有记录。派发角色后由工具写回，或在上方对话里让智能体补录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                return@RuntimeCard
            }
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                rows.forEach { fact ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text(
                            fact.title.ifBlank { fact.id },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        fact.subtitle.takeIf { it.isNotBlank() }?.let { sub ->
                            Text(
                                sub,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        fact.severity?.takeIf { it.isNotBlank() }?.let { severity ->
                            Text(
                                severity,
                                style = MaterialTheme.typography.labelSmall,
                                color = when (severity.lowercase()) {
                                    "critical", "high" -> MaterialTheme.colorScheme.error
                                    "medium" -> Color(0xFFF59E0B)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 智能体并发上限（上游「智能体」页签的核心控件）。
 *
 * 上限来源要如实标出来：设置项 / 环境变量 / 默认值。用户改了设置却没生效时
 * （被 `REDTEAM_MAX_AGENTS` 压住），没有这行提示就会以为界面坏了。
 */
@Composable
private fun RedTeamAgentsCard(
    state: RedTeamConsoleState,
    onSetMax: (Int) -> Unit,
) {
    val agents = state.agents ?: return
    RuntimeCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RuntimeIcon(RuntimeIconName.Bot, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
            Column(Modifier.weight(1f)) {
                Text(
                    "并发执行智能体 ${agents.max} 个 · 已用 ${agents.used} / 空闲 ${agents.free}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(
                            when (agents.source) {
                                "settings" -> "来源：设置项"
                                "env" -> "来源：环境变量（会压住设置项）"
                                else -> "来源：默认值"
                            },
                        )
                        append(" · 上限 ${agents.limit}")
                        if (agents.running.isNotEmpty()) append(" · 在跑 ${agents.running.joinToString("、")}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            (1..agents.limit).forEach { value ->
                val selected = value == agents.max
                RuntimeTextButton(
                    onClick = { onSetMax(value) },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = if (selected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text(
                        value.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
            Text(
                "改完立即生效，不用重启",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
                maxLines = 1,
            )
        }

        state.agentsMessage?.let { message ->
            Text(message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** 顶栏 + 页签条。 */
@Composable
private fun ConsoleChrome(
    session: HarnessSessionEntity,
    state: RedTeamConsoleState,
    onSelectTab: (RedTeamConsoleTab) -> Unit,
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
        }

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
                    // 条数直接标在页签上：否则用户要点进每个页签才知道有没有数据，
                    // 空页签和「没实现的页签」看起来一模一样。
                    val count = state.sections.firstOrNull { it.id == tab.sectionId }?.count
                    Text(
                        if (count != null && count > 0) "${tab.label} $count" else tab.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
        }
    }
