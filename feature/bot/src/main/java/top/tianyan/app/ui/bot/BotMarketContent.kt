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
import top.tianyan.app.ui.components.RuntimeFilledTextField
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
import top.tianyan.app.core.model.bot.MarketBotItem
import top.tianyan.app.feature.bot.R
import top.tianyan.app.ui.components.EmptyPanel
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeFilledTonalButton
import top.tianyan.app.ui.components.RuntimeFilterChip
import top.tianyan.app.ui.components.RuntimeIconName

import top.tianyan.app.harness.bot.market.GroupTeamTemplates

@Composable
internal fun MarketList(
    items: List<MarketBotItem>,
    installedNames: Set<String>,
    installingId: String?,
    onInstall: (MarketBotItem) -> Unit,
    onImportTeam: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var department by rememberSaveable { mutableStateOf("") }
    val templates = remember(query, department) {
        if (department.isNotBlank() && department != TEAM_DEPARTMENT) emptyList()
        else GroupTeamTemplates.all.filter { query.isBlank() || it.name.contains(query.trim(), true) || it.description.contains(query.trim(), true) }
    }
    val departments = remember(items) { items.mapNotNull { it.departmentName }.distinct() }
    val filtered = remember(items, query, department) {
        val term = query.trim()
        items.filter { item ->
            (department.isBlank() || item.departmentName == department) &&
                (term.isBlank() || item.name.contains(term, true) ||
                    item.role.contains(term, true) || item.description.contains(term, true))
        }
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RuntimeFilledTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.fbot_market_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    RuntimeFilterChip(
                        selected = department.isBlank(),
                        onClick = { department = "" },
                        label = { Text(stringResource(R.string.fbot_market_all)) },
                    )
                    RuntimeFilterChip(selected = department == TEAM_DEPARTMENT, onClick = { department = TEAM_DEPARTMENT },
                        label = { Text(stringResource(R.string.fbot_market_team_templates)) })
                    departments.forEach { name ->
                        RuntimeFilterChip(
                            selected = department == name,
                            onClick = { department = name },
                            label = { Text(name) },
                        )
                    }
                }
            }
        }
        if (templates.isNotEmpty()) item { Text(stringResource(R.string.fbot_market_team_templates), style = MaterialTheme.typography.titleSmall) }
        items(templates, key = { "team:${it.id}" }) { template ->
            RuntimeCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(template.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(template.description, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.fbot_market_team_meta, template.members.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    RuntimeFilledTonalButton(onClick = { onImportTeam(template.id) }, enabled = installingId == null) {
                        Text(stringResource(R.string.fbot_market_import_whole_team))
                    }
                }
            }
        }
        if (filtered.isNotEmpty()) item { Text(stringResource(R.string.fbot_market_individual_members), style = MaterialTheme.typography.titleSmall) }
        if (filtered.isEmpty() && templates.isEmpty()) {
            item {
                EmptyPanel(
                    icon = RuntimeIconName.Hub,
                    title = stringResource(R.string.fbot_tab_market),
                    description = stringResource(R.string.fbot_market_empty),
                )
            }
        }
        items(filtered, key = { it.id }) { item ->
            val installed = item.name in installedNames
            RuntimeCard(modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BotAvatar(seed = item.avatarSeed ?: item.name, colorHex = item.colorHex, size = 48.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(
                            item.role,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                        Text(
                            item.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (item.stars > 0 || item.downloadCount > 0) {
                            Text(
                                stringResource(R.string.fbot_market_stats, item.stars, item.downloadCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                item.author,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.fbot_market_install_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                    )
                    RuntimeFilledTonalButton(
                        onClick = { onInstall(item) },
                        enabled = installingId == null && !installed,
                    ) {
                        Text(
                            stringResource(
                                when {
                                    installed -> R.string.fbot_market_installed
                                    installingId == item.id -> R.string.fbot_installing
                                    else -> R.string.fbot_install
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}


private const val TEAM_DEPARTMENT = "__team_templates__"
