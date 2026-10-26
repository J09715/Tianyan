package top.tianyan.app.ui.bot

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.BotMemberGroup
import top.tianyan.app.core.model.bot.GroupChat
import top.tianyan.app.core.model.bot.MarketBotItem
import top.tianyan.app.feature.bot.R
import top.tianyan.app.ui.components.EmptyPanel
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeFilledTonalButton
import top.tianyan.app.ui.components.RuntimeFilterChip
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconButton
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.StatusBadge

@Composable
internal fun BotList(
    bots: List<Bot>,
    modelProfiles: List<top.tianyan.app.core.database.AiModelEntity>?,
    memberGroups: List<BotMemberGroup>,
    selectedGroupId: String?,
    onSelectGroup: (String?) -> Unit,
    onManageGroups: () -> Unit,
    onOpenBot: (String) -> Unit,
) {
    val visibleBots = remember(bots, memberGroups, selectedGroupId) {
        val memberIds = selectedGroupId?.let { id -> memberGroups.firstOrNull { it.id == id } }
            ?.memberIds
            ?.toSet()
        if (memberIds == null) bots else bots.filter { it.id in memberIds }
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "member-group-filter") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RuntimeFilterChip(
                        selected = selectedGroupId == null,
                        onClick = { onSelectGroup(null) },
                        label = { Text(stringResource(R.string.fbot_market_all)) },
                    )
                    memberGroups.forEach { group ->
                        RuntimeFilterChip(
                            selected = selectedGroupId == group.id,
                            onClick = { onSelectGroup(group.id) },
                            label = { Text(group.name) },
                        )
                    }
                }
                RuntimeIconButton(
                    onClick = onManageGroups,
                    contentDescription = stringResource(R.string.fbot_manage_member_groups),
                ) {
                    RuntimeIcon(RuntimeIconName.Tune, Modifier.size(20.dp))
                }
            }
        }
        if (visibleBots.isEmpty()) {
            item {
                EmptyPanel(
                    icon = RuntimeIconName.Bot,
                    title = stringResource(
                        if (bots.isEmpty()) R.string.fbot_empty_bots_title else R.string.fbot_member_group_filter_empty,
                    ),
                    description = stringResource(
                        if (bots.isEmpty()) R.string.fbot_empty_bots_hint else R.string.fbot_member_group_filter_hint,
                    ),
                )
            }
        }
        items(visibleBots, key = { it.id }) { bot ->
            RuntimeCard(onClick = { onOpenBot(bot.id) }, modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BotAvatar(bot, 52.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                bot.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (bot.isMainBot) StatusBadge(stringResource(R.string.fbot_main_bot))
                        }
                        Text(
                            bot.role,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        BotModelSummary(bot, modelProfiles)
                    }
                    RuntimeIcon(
                        RuntimeIconName.ChevronRight,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun GroupList(
    groups: List<GroupChat>,
    adminName: (String) -> String,
    onOpenGroup: (String) -> Unit,
    onDeleteGroup: (GroupChat) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (groups.isEmpty()) {
            item {
                EmptyPanel(
                    icon = RuntimeIconName.Chat,
                    title = stringResource(R.string.fbot_empty_groups_title),
                    description = stringResource(R.string.fbot_empty_groups_hint),
                )
            }
        }
        items(groups, key = { it.id }) { group ->
            RuntimeCard(onClick = { onOpenGroup(group.id) }, modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BotGroupBadge(group.name)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            group.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(R.string.fbot_group_admin, adminName(group.adminBotId).ifBlank { "—" }),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (group.description.isNotBlank()) {
                            Text(
                                group.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    RuntimeIconButton(
                        onClick = { onDeleteGroup(group) },
                        contentDescription = stringResource(R.string.fbot_delete),
                    ) {
                        RuntimeIcon(
                            RuntimeIconName.Trash,
                            Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BotGroupBadge(name: String) {
    val color = MaterialTheme.colorScheme.primary
    RuntimeCard(
        containerColor = color.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(52.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            RuntimeIcon(RuntimeIconName.Chat, Modifier.size(20.dp), tint = color)
            Text(
                name.take(1),
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}
