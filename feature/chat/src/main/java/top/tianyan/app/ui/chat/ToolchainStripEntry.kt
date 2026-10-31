package top.tianyan.app.ui.chat

import top.tianyan.app.feature.chat.R
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import org.koin.compose.viewmodel.koinViewModel
import top.tianyan.app.ui.components.RuntimeIconName

/**
 * 沙箱工具链入口项 —— 从 [CollapsibleChatWorkbenchStrip] 抽出，单独成文件。
 *
 * 红点主动亮：入口组合时读取进程级缓存（约 10 分钟内不重复跑全量探针）。
 * 检测到缺口时 [hasGap] 置真、红点亮起，无需用户先打开面板。
 */
@Composable
internal fun ToolchainStripEntry(
    toolchainHighlight: Boolean,
    onOpenToolchain: () -> Unit,
) {
    val viewModel: ToolchainViewModel = koinViewModel()
    val hasGap by viewModel.hasGap.collectAsState()

    // 缓存未过期时不会重跑 15 项探针
    LaunchedEffect(Unit) { viewModel.refresh(force = false) }

    StatusDivider()
    WorkbenchStatusItem(
        icon = RuntimeIconName.Hammer,
        label = if (toolchainHighlight || hasGap) {
            stringResource(R.string.toolchain_entry_gap)
        } else {
            stringResource(R.string.toolchain_entry)
        },
        tint = if (toolchainHighlight || hasGap) Color(0xFFC62828) else MaterialTheme.colorScheme.onSurfaceVariant,
        highlight = toolchainHighlight || hasGap,
        onClick = onOpenToolchain,
    )
}
