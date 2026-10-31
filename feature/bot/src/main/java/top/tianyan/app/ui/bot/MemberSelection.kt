package top.tianyan.app.ui.bot

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.BotMemberGroup
import top.tianyan.app.feature.bot.R
import top.tianyan.app.ui.components.RuntimeCheckbox
import top.tianyan.app.ui.components.RuntimeFilterChip
import top.tianyan.app.ui.components.RuntimeTextButton

/** 建群与分组编辑共用的成员选择：分组快捷全选 + 逐个勾选。 */
@Composable
internal fun MemberSelection(
    bots: List<Bot>,
    selectedIds: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    memberGroups: List<BotMemberGroup> = emptyList(),
    lockedBotId: String? = null,
) {
    val locked = if (lockedBotId == null) emptySet() else setOf(lockedBotId)
    fun apply(ids: Set<String>) = onSelectionChange(ids + locked)

    if (memberGroups.isNotEmpty() && bots.isNotEmpty()) {
        Text(
            stringResource(R.string.fbot_select_group_hint),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            memberGroups.forEach { group ->
                val groupIds = group.memberIds.filterTo(mutableSetOf()) { id -> bots.any { it.id == id } }
                if (groupIds.isEmpty()) return@forEach
                val allSelected = selectedIds.containsAll(groupIds)
                RuntimeFilterChip(
                    selected = allSelected,
                    onClick = { apply(if (allSelected) selectedIds - groupIds else selectedIds + groupIds) },
                    label = { Text(group.name) },
                )
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            stringResource(R.string.fbot_members_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row {
            RuntimeTextButton(onClick = { apply(bots.mapTo(mutableSetOf()) { it.id }) }) {
                Text(stringResource(R.string.fbot_select_all))
            }
            RuntimeTextButton(onClick = { apply(emptySet()) }) {
                Text(stringResource(R.string.fbot_clear_selection))
            }
        }
    }
    bots.forEach { bot ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RuntimeCheckbox(
                checked = bot.id in selectedIds,
                onCheckedChange = { checked ->
                    if (checked) apply(selectedIds + bot.id) else apply(selectedIds - bot.id)
                },
                enabled = bot.id != lockedBotId,
            )
            BotAvatar(bot, 28.dp)
            Column(Modifier.padding(start = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(bot.name, style = MaterialTheme.typography.bodyMedium)
                    if (bot.id == lockedBotId) {
                        Text(
                            stringResource(R.string.fbot_admin_member),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                Text(
                    bot.role,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
