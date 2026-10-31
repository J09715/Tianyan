package top.tianyan.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.chat.R

/** 历史轮次中间过程折叠条：收起/展开整轮的执行记录。 */
@Composable
internal fun RoundCollapseButton(
    item: ChatRenderItem.CollapseButtonItem,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = if (item.isExpanded) {
        stringResource(R.string.chat_collapse)
    } else {
        val durationSuffix = if (item.hiddenDurationMs > 0) {
            " · " + formatChatDuration(item.hiddenDurationMs)
        } else {
            ""
        }
        stringResource(R.string.chat_expand_more, item.hiddenSteps, item.totalSteps, durationSuffix)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        )
    }
}
