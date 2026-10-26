package top.tianyan.app.ui.chat

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import top.tianyan.app.ui.components.RuntimeCard
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.chat.R
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconButton
import top.tianyan.app.ui.components.RuntimeIconName

@Composable
internal fun ComposerSendButton(onClick: () -> Unit, enabled: Boolean, tint: Color) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        RuntimeCard(shape = CircleShape, contentPadding = PaddingValues(0.dp),
            containerColor = if (enabled) tint.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHighest) {
            RuntimeIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(30.dp),
                contentDescription = stringResource(R.string.chat_send)) {
                RuntimeIcon(RuntimeIconName.ArrowUp, Modifier.size(22.dp),
                    if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f))
            }
        }
    }
}
