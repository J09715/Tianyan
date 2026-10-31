package top.tianyan.app.ui.bot

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import top.tianyan.app.ui.components.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.GroupAttachment
import top.tianyan.app.feature.bot.R
import top.tianyan.app.ui.components.RuntimeCircularProgressIndicator
import top.tianyan.app.ui.components.RuntimeFilterChip
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconButton
import top.tianyan.app.ui.components.RuntimeIconName

@Composable
internal fun GroupChatComposer(
    members: List<Bot>,
    value: TextFieldValue,
    attachments: List<GroupAttachment>,
    sending: Boolean,
    busy: Boolean,
    stopping: Boolean,
    onValueChange: (TextFieldValue) -> Unit,
    onMention: (String) -> Unit,
    onAttach: () -> Unit,
    onRemoveAttachment: (GroupAttachment) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val mention = activeGroupMention(value)
    val candidates = if (mention == null) emptyList() else members.filter {
        it.name.contains(mention.query, ignoreCase = true) || it.role.contains(mention.query, ignoreCase = true)
    }
    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(runtimeContentPadding(horizontal = 12.dp, top = 8.dp, bottom = 8.dp)),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (mention != null && candidates.isNotEmpty()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    candidates.forEach { bot ->
                        RuntimeFilterChip(
                            selected = false,
                            onClick = { onMention(bot.name) },
                            label = { Text("@${bot.name}", style = MaterialTheme.typography.labelMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { BotAvatar(bot, 18.dp) },
                        )
                    }
                }
            }
            if (attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    attachments.forEach { attachment ->
                        RuntimeFilterChip(
                            selected = false,
                            onClick = { onRemoveAttachment(attachment) },
                            label = {
                                Text(
                                    text = attachment.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                )
                            },
                            leadingIcon = {
                                RuntimeIcon(RuntimeIconName.Attach, Modifier.size(14.dp))
                            },
                            trailingIcon = {
                                RuntimeIcon(RuntimeIconName.Close, Modifier.size(14.dp))
                            },
                        )
                    }
                }
            }
            RuntimeCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                borderColor = MaterialTheme.colorScheme.outlineVariant, shape = RoundedCornerShape(28.dp),
                contentPadding = PaddingValues(12.dp)) {
                BasicTextField(value = value, onValueChange = onValueChange, maxLines = 5,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp, max = 160.dp).padding(horizontal = 8.dp, vertical = 6.dp),
                    decorationBox = { field ->
                        Box {
                            if (value.text.isEmpty()) Text(stringResource(R.string.fbot_input_hint),
                                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            field()
                        }
                    })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    RuntimeIconButton(onClick = onAttach, contentDescription = stringResource(R.string.fbot_attach)) {
                        RuntimeIcon(RuntimeIconName.Plus, Modifier.size(22.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    RuntimeTextButton(onClick = {
                        val start = value.selection.min
                        onValueChange(TextFieldValue(value.text.replaceRange(start, value.selection.max, "@"), TextRange(start + 1)))
                    }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(40.dp),
                        enabled = members.isNotEmpty()) { Text("@", style = MaterialTheme.typography.titleLarge) }
                    Text(stringResource(if (busy) R.string.fbot_process_composer_busy else R.string.fbot_process_composer_members, members.size),
                        Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val hasContent = value.text.isNotBlank() || attachments.isNotEmpty()
                    val canSend = hasContent && !sending && !stopping
                    val showStop = busy && !hasContent
                    val buttonEnabled = if (showStop) !stopping else canSend
                    val buttonColor = when {
                        showStop -> MaterialTheme.colorScheme.error
                        sending || canSend -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.surfaceContainerHigh
                    }
                    Surface(color = buttonColor, shape = CircleShape, modifier = Modifier.size(44.dp)) {
                        RuntimeIconButton(onClick = if (showStop) onStop else onSend, enabled = buttonEnabled,
                            modifier = Modifier.fillMaxSize(),
                            contentDescription = stringResource(if (showStop) R.string.fbot_stop else R.string.fbot_send)) {
                            when {
                                stopping || sending -> RuntimeCircularProgressIndicator(Modifier.size(18.dp),
                                    color = if (stopping) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary)
                                showStop -> RuntimeIcon(RuntimeIconName.Stop, Modifier.size(20.dp), MaterialTheme.colorScheme.onError)
                                else -> RuntimeIcon(RuntimeIconName.ArrowUp, Modifier.size(20.dp),
                                    if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
