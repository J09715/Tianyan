package top.tianyan.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.chat.R
import top.tianyan.app.ui.components.*
import top.tianyan.app.ui.theme.semanticColors

/** Roomy search and compose actions above the conversation list. */
@Composable
internal fun SessionDrawerSearch(
    query: String,
    onQueryChange: (String) -> Unit,
    onNew: () -> Unit,
    onCollapse: (() -> Unit)? = null,
) {
    val searchLabel = stringResource(R.string.chat_drawer_search)
    val newTaskLabel = stringResource(R.string.chat_drawer_new_task)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RuntimeChatIdentityAvatar(size = 32.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(LocalChatIdentity.current.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.chat_sidebar_subtitle), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onCollapse != null) RuntimeIconButton(onClick = onCollapse, modifier = Modifier.size(40.dp),
                contentDescription = stringResource(top.tianyan.app.feature.components.R.string.components_collapse_sidebar)) {
                RuntimeIcon(RuntimeIconName.PanelLeftClose, Modifier.size(20.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f)) {
                Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuntimeIcon(RuntimeIconName.Search, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    BasicTextField(value = query, onValueChange = onQueryChange, singleLine = true,
                        modifier = Modifier.weight(1f).semantics { contentDescription = searchLabel },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { field -> Box {
                            if (query.isEmpty()) Text(searchLabel, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            field()
                        } },
                    )
                }
            }
            Surface(onClick = onNew, shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(44.dp).semantics { contentDescription = newTaskLabel }) {
                Box(contentAlignment = Alignment.Center) {
                    RuntimeIcon(RuntimeIconName.Plus, Modifier.size(22.dp), MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
}

/** Keep app destinations and plugin contributions reachable from a fixed footer. */
@Composable
internal fun SessionDrawerFooter(
    totalSessions: Int,
    onSettings: () -> Unit,
    navigationContent: @Composable () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.fillMaxWidth().heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
                navigationContent()
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.weight(1f).clip(MaterialTheme.shapes.medium).clickable(onClick = onSettings)
                .padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RuntimeIcon(RuntimeIconName.Settings, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(top.tianyan.app.feature.components.R.string.components_drawer_settings),
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.chat_session_count, totalSessions), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            RuntimeTextButton(onClick = { expanded = !expanded }) {
                RuntimeIcon(if (expanded) RuntimeIconName.ChevronDown else RuntimeIconName.Menu, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.chat_sidebar_functions), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** 统一分区头：名称 + 待处理提示点 + 数量 + 展开/收起箭头，整行可点。 */@Composable
internal fun SessionSectionHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    alert: Boolean = false,
) {
    val toggleLabel = stringResource(
        if (expanded) R.string.chat_drawer_section_collapse else R.string.chat_drawer_section_expand,
        title,
    )
    val stateLabel = stringResource(
        if (expanded) R.string.chat_drawer_section_expanded else R.string.chat_drawer_section_collapsed,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onToggle)
            .semantics {
                contentDescription = toggleLabel
                stateDescription = stateLabel
            }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (alert) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(semanticColors().warning))
        }
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        RuntimeIcon(
            name = if (expanded) RuntimeIconName.ChevronDown else RuntimeIconName.ChevronRight,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 跨会话的待处理提示：审批等待或执行失败的会话置顶一条，点按直接跳过去。 */
@Composable
internal fun SessionAttentionBanner(
    count: Int,
    firstTitle: String,
    onOpen: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onOpen),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(semanticColors().warning))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = stringResource(R.string.chat_drawer_attention, count),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = firstTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            RuntimeIcon(
                RuntimeIconName.ChevronRight,
                Modifier.size(18.dp),
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
