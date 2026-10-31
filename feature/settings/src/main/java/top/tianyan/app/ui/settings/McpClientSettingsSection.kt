package top.tianyan.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import top.tianyan.app.core.model.McpIdlePolicy
import top.tianyan.app.feature.settings.R
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeFilterChip
import top.tianyan.app.ui.components.previewOrNull
import top.tianyan.app.ui.components.previewStateOf

@Composable
internal fun McpClientSettingsSection(viewModel: McpClientSettingsViewModel? = previewOrNull { koinViewModel() }) {
    val minutes by viewModel?.idleMinutes?.collectAsStateWithLifecycle() ?: previewStateOf(McpIdlePolicy.DEFAULT_MINUTES)
    val failed by viewModel?.saveFailed?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    RuntimeCard(modifier = Modifier.fillMaxWidth(), containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge,
        contentPadding = PaddingValues(SettingsDensity.cardPadding)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LocalizedText("Model Context Protocol (MCP)", style = MaterialTheme.typography.titleMedium)
            LocalizedText(stringResource(R.string.mcp_client_intro), style = settingsSupportingStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LocalizedText(stringResource(R.string.mcp_client_idle_title), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf(1, 3, 5, 10, 30, 60) + minutes).distinct().sorted().forEach { value ->
                    RuntimeFilterChip(shape = MaterialTheme.shapes.small, selected = minutes == value, onClick = { viewModel?.setIdleMinutes(value) },
                        label = { LocalizedText(stringResource(R.string.mcp_client_minutes, value), maxLines = 1,
                            overflow = TextOverflow.Ellipsis) })
                }
            }
            LocalizedText(stringResource(R.string.mcp_client_idle_hint), style = settingsSupportingStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (failed) LocalizedText(stringResource(R.string.mcp_client_save_failed),
                color = MaterialTheme.colorScheme.error, style = settingsSupportingStyle())
        }
    }
}
