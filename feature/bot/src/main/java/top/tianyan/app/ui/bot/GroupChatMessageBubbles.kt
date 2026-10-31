package top.tianyan.app.ui.bot

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.GroupMessage
import top.tianyan.app.core.model.bot.GroupSenderType
import top.tianyan.app.feature.bot.R
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName

private val AvatarSize = 24.dp
private val GroupGap = 2.dp

@Composable
internal fun GroupMessageRow(
    message: GroupMessage,
    bot: Bot?,
    firstInGroup: Boolean,
    lastInGroup: Boolean,
    onOpenBot: (String) -> Unit,
) {
    when (message.senderType) {
        GroupSenderType.USER -> UserMessageBubble(message, firstInGroup, lastInGroup)
        GroupSenderType.BOT ->
            BotMessageBubble(message, bot, firstInGroup, lastInGroup, onOpenBot)
        GroupSenderType.SYSTEM -> SystemNotice(message)
    }
}

@Composable
internal fun TimeSeparator(label: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BotMessageBubble(
    message: GroupMessage,
    bot: Bot?,
    firstInGroup: Boolean,
    lastInGroup: Boolean,
    onOpenBot: (String) -> Unit,
) {
    val role = bot?.role.orEmpty()
    val botId = bot?.id
    Column(Modifier.fillMaxWidth().widthIn(max = 680.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (firstInGroup) Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BotAvatar(seed = bot?.avatarSeed ?: message.senderName, colorHex = bot?.colorHex ?: "#3D5AFE",
                size = AvatarSize, active = message.isStreaming,
                modifier = if (botId != null) Modifier.clickable { onOpenBot(botId) } else Modifier)
            Text(bot?.name ?: message.senderName, style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).then(if (botId != null) Modifier.clickable { onOpenBot(botId) } else Modifier))
            if (bot?.isMainBot == true) MainBadge()
            if (role.isNotBlank()) Text(role, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
            if (message.reasoning.isNotBlank() || (message.isStreaming && !message.contentText.hasText && message.steps.isEmpty())) {
                GroupReasoningPanel(message.id, message.reasoning,
                    message.isStreaming && !message.contentText.hasText && message.steps.none { it.success == null },
                    waiting = message.reasoning.isBlank(), startedAtMs = message.timestamp)
            }
            GroupToolCallPanel(message.id, message.steps, message.isStreaming)
            if (message.contentText.hasText || (!message.isStreaming && message.steps.isEmpty())) {
                MessageBubble(message, botShape(firstInGroup, lastInGroup))
            }
        }
    }
}

@Composable
private fun MainBadge() {
    Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), shape = CircleShape) {
        Text(
            text = stringResource(R.string.fbot_main_bot),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun MessageBubble(message: GroupMessage, shape: Shape) {
    val content = message.contentText
    when {
        content.startsWith("❌") -> Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = shape,
            modifier = Modifier.widthIn(max = 600.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ExpandableGroupText(
                    messageId = message.id, text = content.toString(), streaming = message.isStreaming, markdown = false,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        content.startsWith("⏳") -> Row(
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RuntimeIcon(RuntimeIconName.Sparkles, Modifier.size(14.dp), MaterialTheme.colorScheme.primary)
            Text(
                text = content.toString().removePrefix("⏳").trim(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        content.startsWith("💭") -> GroupReasoningPanel(
            message.id, content.toString().removePrefix("💭").trim(), message.isStreaming,
        )
        else -> Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ExpandableGroupText(message.id, content, message.isStreaming)
            AttachmentChips(message.attachments, MaterialTheme.colorScheme.onSurfaceVariant)
            if (message.isStreaming) TypingDots(MaterialTheme.colorScheme.onSurfaceVariant)
            else if (content.hasText) GroupMessageActions(message)
        }
    }
}

@Composable
private fun UserMessageBubble(
    message: GroupMessage,
    firstInGroup: Boolean,
    lastInGroup: Boolean,
) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(GroupGap),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = userShape(firstInGroup, lastInGroup),
                modifier = Modifier
                    .widthIn(max = maxWidth * 0.86f)
                    .pointerInput(message.content) {
                        detectTapGestures(
                            onLongPress = {
                                scope.launch {
                                    clipboard.setClipEntry(
                                        ClipEntry(ClipData.newPlainText("tianyan-group-message", message.content)),
                                    )
                                    Toast.makeText(context, context.getString(R.string.fbot_copied), Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                    },
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (message.content.isNotBlank()) {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    AttachmentChips(
                        attachments = message.attachments,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun SystemNotice(message: GroupMessage) {
    val content = message.content.trim()
    val warning = content.startsWith("⚠️") || content.startsWith("❌")
    val icon = if (warning) RuntimeIconName.Alert else RuntimeIconName.Info
    val tint = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RuntimeIcon(icon, Modifier.size(16.dp), tint)
            ExpandableGroupText(
                messageId = message.id,
                text = content.removePrefix("⚠️").removePrefix("❌").trimStart(),
                streaming = message.isStreaming,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 同组消息只保留外侧圆角，内部用小圆角拼接，贴近参考实现的气泡分组。 */
private fun botShape(first: Boolean, last: Boolean) = RoundedCornerShape(
    topStart = if (first) 12.dp else 4.dp,
    topEnd = 12.dp,
    bottomEnd = 12.dp,
    bottomStart = if (last) 12.dp else 4.dp,
)

private fun userShape(first: Boolean, last: Boolean) = RoundedCornerShape(
    topStart = 24.dp,
    topEnd = if (first) 24.dp else 6.dp,
    bottomEnd = if (last) 24.dp else 6.dp,
    bottomStart = 24.dp,
)
