package top.tianyan.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.material3.Slider
import androidx.compose.material3.toPath
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.DpSize
import androidx.graphics.shapes.Morph
import kotlinx.coroutines.flow.drop
import kotlin.math.roundToInt
import androidx.compose.material3.ExperimentalMaterial3Api
import top.tianyan.app.ui.components.RuntimeIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchDefaults
import top.tianyan.app.ui.components.RuntimeTab
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Text
import top.tianyan.app.ui.components.RuntimeSwitch as Switch
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import top.tianyan.app.feature.chat.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.tianyan.app.core.model.ApprovalMode
import top.tianyan.app.core.model.RunMode
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.McpServerRow
import top.tianyan.app.ui.theme.semanticColors
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.core.model.McpConnectionState
import top.tianyan.app.core.model.AgentSkill
import top.tianyan.app.core.model.McpServerConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SkillsAndMcpSheet(
    allSkills: List<AgentSkill>,
    mcpServers: List<McpServerConfig>,
    mcpConnectionStates: Map<String, McpConnectionState>,
    mcpToolCounts: Map<String, Int> = emptyMap(),
    pinnedMentionIds: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onToggleSkill: (String, Boolean) -> Unit,
    onToggleMcpServer: (String, Boolean) -> Unit,
    onTogglePin: (String) -> Unit = {},
    onNavigateToSettings: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val activeSkillsCount = remember(allSkills) { allSkills.count { it.isEnabled } }
    val activeMcpCount = remember(mcpServers) { mcpServers.count { it.isEnabled } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 头部标题与统计
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.chat_capabilities),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.chat_capabilities_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text(
                        text = stringResource(R.string.chat_capabilities_enabled, activeSkillsCount + activeMcpCount),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }

            // 分段标签页
            SecondaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(MaterialTheme.shapes.large),
            ) {
                RuntimeTab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            stringResource(R.string.chat_skills_count, activeSkillsCount, allSkills.size),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                            ),
                        )
                    },
                )
                RuntimeTab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            stringResource(R.string.chat_mcp_count, activeMcpCount, mcpServers.size),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                            ),
                        )
                    },
                )
            }

            // 列表内容展示
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
            ) {
                if (selectedTab == 0) {
                    // Skills 列表
                    if (allSkills.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.chat_no_skills),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            items(allSkills.size, key = { allSkills[it].id }) { index ->
                                val skill = allSkills[index]
                                val isPinned = skill.id in pinnedMentionIds || skill.name.lowercase() in pinnedMentionIds
                                RuntimeCard(
                                    containerColor = if (skill.isEnabled) MaterialTheme.colorScheme.surfaceContainerHigh
                                    else MaterialTheme.colorScheme.surfaceContainer,
                                    shape = MaterialTheme.shapes.large, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            FlowRow(
                                                itemVerticalAlignment = Alignment.CenterVertically, verticalArrangement = Arrangement.spacedBy(8.dp),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                Text(
                                                    text = skill.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                    style = MaterialTheme.typography.titleMedium.copy(
                                                        fontWeight = FontWeight.SemiBold,
                                                    ),
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                )
                                                Surface(
                                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                                    shape = MaterialTheme.shapes.small,
                                                ) {
                                                    Text(
                                                        text = skill.category,
                                                        style = MaterialTheme.typography.labelMedium.copy(
                                                            fontWeight = FontWeight.Bold,
                                                        ),
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                                    )
                                                }
                                            }
                                            if (skill.description.isNotBlank()) {
                                                Text(
                                                    text = skill.description,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.padding(top = 2.dp),
                                                )
                                            }
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                        ) {
                                            Surface(
                                                shape = MaterialTheme.shapes.medium,
                                                color = if (isPinned) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                                                modifier = Modifier
                                                    .minimumInteractiveComponentSize()
                                                    .clickable { onTogglePin(skill.id) },
                                            ) {
                                                Text(
                                                    if (isPinned) stringResource(R.string.chat_pinned_on) else stringResource(R.string.chat_pinned_off),
                                                    style = MaterialTheme.typography.labelMedium.copy(
                                                        fontWeight = if (isPinned) FontWeight.Bold else FontWeight.Normal,
                                                    ),
                                                    color = if (isPinned) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                                )
                                            }

                                            Switch(
                                                checked = skill.isEnabled,
                                                onCheckedChange = { onToggleSkill(skill.id, it) },
                                                colors = SwitchDefaults.colors(
                                                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // MCP 插件列表
                    if (mcpServers.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.chat_no_mcp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            items(mcpServers.size, key = { mcpServers[it].id }) { index ->
                                val server = mcpServers[index]
                                val isPinned = server.id in pinnedMentionIds || server.name.lowercase() in pinnedMentionIds
                                val endpointDesc = if (server.command.isNotBlank()) server.command else server.serverUrl
                                McpServerRow(
                                    name = server.name,
                                    subtitle = listOf(server.transportType.name, endpointDesc)
                                        .filter { it.isNotBlank() }
                                        .joinToString(" · "),
                                    enabled = server.isEnabled,
                                    connectionState = mcpConnectionStates[server.id] ?: McpConnectionState.UNKNOWN,
                                    toolCount = mcpToolCounts[server.id],
                                    bottomActions = {
                                        Surface(
                                            shape = MaterialTheme.shapes.medium,
                                            color = if (isPinned) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .clickable { onTogglePin(server.id) },
                                        ) {
                                            Text(
                                                if (isPinned) stringResource(R.string.chat_pinned_on) else stringResource(R.string.chat_pinned_off),
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    fontWeight = if (isPinned) FontWeight.Bold else FontWeight.Normal,
                                                ),
                                                color = if (isPinned) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            )
                                        }

                                        Switch(
                                            checked = server.isEnabled,
                                            onCheckedChange = { onToggleMcpServer(server.id, it) },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // 底部操作区：直达管理
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onNavigateToSettings),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RuntimeIcon(
                            name = RuntimeIconName.Settings,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(R.string.chat_manage_capabilities),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    RuntimeIcon(
                        name = RuntimeIconName.ChevronRight,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** 审批模式选择底部弹层（完全访问 / 询问 / 每次审批）。 / */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ApprovalModeSheet(
    currentApprovalMode: ApprovalMode,
    onSelect: (ApprovalMode) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.chat_menu_approval_mode),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(
                    onClick = onDismiss,
                    contentDescription = stringResource(R.string.chat_close),
                ) {
                    RuntimeIcon(RuntimeIconName.Close, Modifier.size(20.dp))
                }
            }

            Text(
                text = stringResource(R.string.chat_approval_mode_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            listOf(
                Triple(ApprovalMode.REQUEST, stringResource(R.string.chat_approval_request_title), stringResource(R.string.chat_approval_request_description)),
                Triple(ApprovalMode.ASSISTED, stringResource(R.string.chat_approval_assisted_title), stringResource(R.string.chat_approval_assisted_description)),
                Triple(ApprovalMode.FULL_ACCESS, stringResource(R.string.chat_approval_full_access_title), stringResource(R.string.chat_approval_full_access_description)),
            ).forEach { (mode, title, desc) ->
                val isSelected = mode == currentApprovalMode
                Surface(
                    onClick = { onSelect(mode) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RuntimeIcon(
                            name = if (isSelected) RuntimeIconName.Check else RuntimeIconName.Shield,
                            modifier = Modifier.size(22.dp),
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 运行意图选择底部弹层（构建 / 规划）。 与审批权限模式正交：本层决定「这一轮动手还是只看」，审批模式决定「要不要问」。 / */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RunModeSheet(
    currentRunMode: RunMode,
    onSelect: (RunMode) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.chat_menu_run_mode),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(
                    onClick = onDismiss,
                    contentDescription = stringResource(R.string.chat_close),
                ) {
                    RuntimeIcon(RuntimeIconName.Close, Modifier.size(20.dp))
                }
            }

            Text(
                text = stringResource(R.string.chat_run_mode_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            listOf(
                Triple(RunMode.BUILD, stringResource(R.string.chat_run_mode_build_title), stringResource(R.string.chat_run_mode_build_description)),
                Triple(RunMode.PLAN, stringResource(R.string.chat_run_mode_plan_title), stringResource(R.string.chat_run_mode_plan_description)),
            ).forEach { (mode, title, desc) ->
                val isSelected = mode == currentRunMode
                Surface(
                    onClick = { onSelect(mode) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RuntimeIcon(
                            name = if (isSelected) RuntimeIconName.Check else runModeIcon(mode),
                            modifier = Modifier.size(22.dp),
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 运行意图图标：构建=写代码，规划=只读检索。 */
internal fun runModeIcon(mode: RunMode): RuntimeIconName = when (mode) {
    RunMode.BUILD -> RuntimeIconName.Code
    RunMode.PLAN -> RuntimeIconName.Search
}
