package top.tianyan.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import top.tianyan.app.ui.components.RuntimeTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.chat.R
import top.tianyan.app.harness.mcp.McpWorkspaceRecommender

/** 工作区感知的 MCP 预设推荐横幅：每条推荐展示启用理由，可一键启用或忽略。 */
@Composable
internal fun McpRecommendationBanner(
    recommendations: List<McpWorkspaceRecommender.Recommendation>,
    onEnable: (String) -> Unit,
    onInstall: () -> Unit,
    onDismiss: (String) -> Unit,
) {
    if (recommendations.isEmpty()) return
    recommendations.forEach { recommendation ->
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        recommendation.presetName,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        recommendation.reason,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { if (recommendation.needsInstall) onInstall() else onEnable(recommendation.presetId) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(if (recommendation.needsInstall) R.string.chat_install_mcp else R.string.chat_enable), color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1)
                }
                TextButton(onClick = { onDismiss(recommendation.presetId) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.chat_ignore), color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1)
                }
            }
        }
    }
}
