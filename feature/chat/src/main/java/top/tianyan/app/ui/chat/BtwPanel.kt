package top.tianyan.app.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import top.tianyan.app.core.model.BtwQuery
import top.tianyan.app.core.model.BtwStatus
import top.tianyan.app.feature.chat.R
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeTextButton

/**
 * 旁路问答（/btw）卡片列：回答不打断当前回合、不进入模型上下文，
 * 只在这里给用户看。进行中的卡片显示流式回答并可取消；历史卡片默认折叠。
 */
@Composable
internal fun BtwPanel(
    queries: List<BtwQuery>,
    streaming: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (queries.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        queries.take(MAX_CARDS).forEach { query ->
            BtwCard(
                query = query,
                streaming = if (query.isRunning) streaming else "",
                onCancel = onCancel,
            )
        }
    }
}

@Composable
private fun BtwCard(query: BtwQuery, streaming: String, onCancel: () -> Unit) {
    var expanded by remember(query.id) { mutableStateOf(query.isRunning) }
    val time = remember(query.createdAt) {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(query.createdAt))
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .animateContentSize(),
        shape = RoundedCornerShape(10.dp),
        color = when (query.status) {
            BtwStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.secondaryContainer
        },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RuntimeIcon(
                    RuntimeIconName.Chat,
                    Modifier.size(16.dp),
                    onContainerColor(query).copy(alpha = 0.7f),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.chat_command_btw) + " · $time",
                    color = onContainerColor(query),
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.weight(1f))
                when (query.status) {
                    BtwStatus.RUNNING -> {
                        Text(
                            stringResource(R.string.chat_btw_answering),
                            color = onContainerColor(query),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        RuntimeTextButton(
                            onClick = onCancel,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Text(stringResource(R.string.chat_btw_cancel), color = onContainerColor(query), maxLines = 1)
                        }
                    }
                    BtwStatus.COMPLETED -> Text(
                        if (expanded) "▾" else "▸",
                        color = onContainerColor(query),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    else -> Text(
                        if (query.status == BtwStatus.CANCELLED) {
                            stringResource(R.string.chat_btw_cancelled)
                        } else {
                            query.error
                        },
                        color = onContainerColor(query),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }
            }
            Text(
                query.question,
                color = onContainerColor(query),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clickable(enabled = query.status == BtwStatus.COMPLETED) { expanded = !expanded },
            )
            when (query.status) {
                BtwStatus.RUNNING -> if (streaming.isNotBlank()) {
                    Text(
                        streaming,
                        color = onContainerColor(query).copy(alpha = 0.9f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                BtwStatus.COMPLETED -> if (expanded) {
                    Text(
                        query.answer,
                        color = onContainerColor(query).copy(alpha = 0.9f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        stringResource(R.string.chat_btw_note),
                        color = onContainerColor(query).copy(alpha = 0.55f),
                        style = MaterialTheme.typography.labelSmall,
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun onContainerColor(query: BtwQuery) = when (query.status) {
    BtwStatus.FAILED -> MaterialTheme.colorScheme.onErrorContainer
    else -> MaterialTheme.colorScheme.onSecondaryContainer
}

private const val MAX_CARDS = 3
