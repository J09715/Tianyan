package top.tianyan.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.UserMessage
import top.tianyan.app.ui.components.LocalChatIdentity
import top.tianyan.app.ui.components.RuntimeChatIdentityAvatar

internal fun assistantTurnLabelKeys(items: List<ChatRenderItem>): Set<String> = buildSet {
    var needsLabel = true
    items.forEach { item ->
        val message = (item as? ChatRenderItem.MessageItem)?.message
        if (message is UserMessage) needsLabel = true
        else if (needsLabel && (message is AssistantText || message is ToolCall ||
                item is ToolStepsItem || item is ChatRenderItem.ReasoningItem)) {
            add(item.stableKey)
            needsLabel = false
        }
    }
}

@Composable
internal fun AssistantTurnLabel() {
    Row(Modifier.padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RuntimeChatIdentityAvatar(20.dp)
        Text(LocalChatIdentity.current.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface)
    }
}
