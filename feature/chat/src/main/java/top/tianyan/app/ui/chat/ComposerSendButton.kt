package top.tianyan.app.ui.chat

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.chat.R
import top.tianyan.app.ui.components.*

/** 输入区右侧控件共用视觉直径，外层仍保留 Material 的最小触控范围。 */
internal val ComposerActionSize = 30.dp

@Composable
internal fun ComposerSendButton(onClick: () -> Unit, enabled: Boolean, containerColor: Color, contentColor: Color) {
    RuntimeFilledIconButton(onClick, Modifier.minimumInteractiveComponentSize().size(ComposerActionSize), enabled, containerColor = containerColor, contentColor = contentColor,
        contentDescription = stringResource(R.string.chat_send)) {
        RuntimeIcon(RuntimeIconName.ArrowUp, Modifier.size(16.dp))
    }
}

@Composable
internal fun ComposerStopButton(onClick: () -> Unit) {
    RuntimeFilledIconButton(onClick, Modifier.minimumInteractiveComponentSize().size(ComposerActionSize),
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        contentDescription = stringResource(R.string.chat_stop_generation)) {
        RuntimeIcon(RuntimeIconName.Stop, Modifier.size(16.dp))
    }
}
