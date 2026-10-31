package top.tianyan.app.ui.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale
import top.tianyan.app.feature.workspace.R
import top.tianyan.app.runtime.WorkspaceFileInspection
import top.tianyan.app.ui.components.RuntimeFilledTonalButton
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeOutlinedButton

/**
 * 二进制产物的编辑器占位：不再把字节流当 UTF-8 文本塞进 BasicTextField
 * （乱码、卡顿，且保存会把原文件写坏），改用系统应用打开/分享。
 */
@Composable
internal fun BinaryFileNotice(
    inspection: WorkspaceFileInspection,
    onOpen: () -> Unit,
    onShare: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RuntimeIcon(RuntimeIconName.File, Modifier.size(40.dp), MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.workspace_binary_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.workspace_binary_message, describeBinaryArtifact(inspection)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RuntimeFilledTonalButton(onClick = onOpen) { Text(stringResource(R.string.workspace_binary_open)) }
            RuntimeOutlinedButton(onClick = onShare) { Text(stringResource(R.string.workspace_binary_share)) }
        }
    }
}

/** 形如 `APK · 12.4 MB`，用于二进制占位文案。 */
internal fun describeBinaryArtifact(inspection: WorkspaceFileInspection): String {
    val kind = inspection.extension.takeIf { it.isNotBlank() }?.uppercase(Locale.US) ?: "BIN"
    return "$kind · ${formatBinarySize(inspection.sizeBytes)}"
}

internal fun formatBinarySize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}