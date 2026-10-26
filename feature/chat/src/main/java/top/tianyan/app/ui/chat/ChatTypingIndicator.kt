package top.tianyan.app.ui.chat

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.chat.R
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.UserMessage

internal fun isWaitingForFirstToken(messages: List<HarnessMessage>, running: Boolean): Boolean {
    if (!running) return false
    for (index in messages.indices.reversed()) {
        when (val message = messages[index]) {
            is UserMessage -> return true
            is AssistantText -> if (message.text.isNotBlank() || !message.reasoning.isNullOrBlank()) return false
            is ToolCall -> return false
            else -> Unit
        }
    }
    return false
}

@Composable
internal fun ChatTypingIndicator() {
    val description = stringResource(R.string.chat_waiting_first_token)
    val transition = rememberInfiniteTransition(label = "chatTyping")
    val density = LocalDensity.current.density
    Row(Modifier.height(24.dp).padding(horizontal = 4.dp).semantics {
        contentDescription = description
        liveRegion = LiveRegionMode.Polite
    }, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val bounce by transition.animateFloat(0f, 0f,
                animationSpec = infiniteRepeatable(keyframes {
                    durationMillis = 1000
                    0f at index * 120
                    -3f at index * 120 + 150
                    0f at index * 120 + 300
                }), label = "typingDot$index")
            Box(Modifier.graphicsLayer {
                translationY = bounce * density
                alpha = 0.45f + (-bounce / 3f) * 0.55f
            }.size(5.dp).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
        }
    }
}
