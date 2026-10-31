package top.tianyan.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.tianyan.app.feature.chat.R
import top.tianyan.app.harness.TurnChangesEvent
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName

/**
 * 轮文件改动卡片（DSH deliverables 借鉴项）：一轮结束后自动展示本轮写工具
 * 触碰的文件清单（新建/修改/触碰），数据来自 checkpoint 快照对比。
 * 视觉沿用 [ModelSwitchCard] 的分隔线卡片语言。
 */
@Composable
internal fun TurnChangesCard(event: TurnChangesEvent) {
    val summary = buildList {
        if (event.created.isNotEmpty()) add(stringResource(R.string.chat_turn_changes_created_fmt, event.created.size))
        if (event.modified.isNotEmpty()) add(stringResource(R.string.chat_turn_changes_modified_fmt, event.modified.size))
        if (event.touched.isNotEmpty()) add(stringResource(R.string.chat_turn_changes_touched_fmt, event.touched.size))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                RuntimeIcon(
                    RuntimeIconName.File,
                    Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    summary.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
            )
        }
        Text(
            (event.created + event.modified + event.touched).joinToString("\n"),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.fillMaxWidth(),
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
