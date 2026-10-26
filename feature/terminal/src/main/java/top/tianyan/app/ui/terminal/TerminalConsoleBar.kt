package top.tianyan.app.ui.terminal

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.terminal.R
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconButton
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.SpotlightAnchor
import top.tianyan.app.ui.components.spotlightAnchor

/**
 * 终端顶栏：只有 返回（或导航）与 会话 两个动作，底边用分割线收口，
 * 不铺任何背景面，终端底色直接穿到状态栏区域，把纵向空间全留给终端本体。
 */
@Composable
internal fun TerminalConsoleBar(
    onBack: (() -> Unit)?,
    onOpenNavigation: (() -> Unit)?,
    onOpenSessions: () -> Unit,
    sessionsAnchor: SpotlightAnchor?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            onBack != null -> ConsoleIconButton(
                RuntimeIconName.Back,
                stringResource(R.string.terminal_back),
                onClick = onBack,
            )
            onOpenNavigation != null -> ConsoleIconButton(
                RuntimeIconName.Menu,
                stringResource(R.string.terminal_open_navigation),
                onClick = onOpenNavigation,
            )
        }

        Spacer(Modifier.weight(1f))

        ConsoleIconButton(
            RuntimeIconName.Plus,
            stringResource(R.string.terminal_sessions),
            onClick = onOpenSessions,
            modifier = sessionsAnchor?.let { Modifier.spotlightAnchor(it) } ?: Modifier,
        )
    }
    HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
}

/** 顶栏小图标钮：36dp 触控面，顶栏总高停在 40dp 一线。 */
@Composable
private fun ConsoleIconButton(
    icon: RuntimeIconName,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
) {
    RuntimeIconButton(
        onClick = onClick,
        contentDescription = contentDescription,
        modifier = modifier.size(36.dp),
    ) {
        RuntimeIcon(icon, Modifier.size(18.dp), tint)
    }
}
