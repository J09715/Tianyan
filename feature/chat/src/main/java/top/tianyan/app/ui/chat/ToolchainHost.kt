package top.tianyan.app.ui.chat

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel

/**
 * 沙箱工具链面板宿主。
 *
 * 红点逻辑：ToolchainViewModel.hasGap 在检测完成后主动更新，
 * 即便用户没打开面板，只要后台/入口触发了检测，红点也会亮起。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolchainHost(visible: Boolean, onDismiss: () -> Unit, onGapChanged: (Boolean) -> Unit) {
    if (visible) {
        ToolchainSheet(onDismiss = onDismiss, onGapChanged = onGapChanged)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolchainSheet(
    onDismiss: () -> Unit,
    onGapChanged: (Boolean) -> Unit,
) {
    val viewModel: ToolchainViewModel = koinViewModel()
    val hasGap by viewModel.hasGap.collectAsState()

    // 打开面板只在缓存过期时重测；用户点「重新检测」才会强制刷新
    LaunchedEffect(Unit) { viewModel.refresh(force = false) }
    LaunchedEffect(hasGap) { onGapChanged(hasGap) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Box(modifier = Modifier.padding(bottom = 24.dp)) {
            ToolchainPanel(viewModel = viewModel)
        }
    }
}
