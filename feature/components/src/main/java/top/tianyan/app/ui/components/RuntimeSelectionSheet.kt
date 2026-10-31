package top.tianyan.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Checkbox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.components.R

/** 搜索、列表和表单共享的选择面板。标题与操作固定，内容消费剩余的有界高度。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuntimeSelectionSheet(
    title: String,
    onDismissRequest: () -> Unit,
    subtitle: String? = null,
    actions: (@Composable () -> Unit)? = null,
    dismissEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val canDismiss by rememberUpdatedState(dismissEnabled)
    RuntimeModalBottomSheet(
        onDismissRequest = { if (canDismiss) onDismissRequest() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
            confirmValueChange = { canDismiss || it != androidx.compose.material3.SheetValue.Hidden }),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().padding(horizontal = 24.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                RuntimeIconButton(onDismissRequest, enabled = dismissEnabled, contentDescription = stringResource(R.string.components_close)) {
                    RuntimeIcon(RuntimeIconName.Close)
                }
            }
            Column(Modifier.fillMaxWidth().weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            if (actions != null) actions()
        }
    }
}

/** 模型、供应商和预设使用同一密度；只有选中行染色，次级信息不再逐个套色块。 */
@Composable
fun RuntimeSelectionRow(
    headline: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    multiple: Boolean = false,
) {
    RuntimeCard(
        modifier = modifier.fillMaxWidth().semantics {
            if (multiple) { toggleableState = ToggleableState(selected); role = Role.Checkbox }
            else { this.selected = selected; role = Role.RadioButton }
        },
        onClick = onClick,
        containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        shape = MaterialTheme.shapes.large,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (leadingContent != null) leadingContent()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(headline, style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!supportingText.isNullOrBlank()) Text(supportingText,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (supportingContent != null) supportingContent()
            }
            if (multiple) Checkbox(checked = selected, onCheckedChange = null)
            else if (selected) RuntimeIcon(RuntimeIconName.Check, Modifier.size(24.dp), MaterialTheme.colorScheme.primary)
            if (trailingContent != null) trailingContent()
        }
    }
}
