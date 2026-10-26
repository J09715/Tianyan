package top.tianyan.app.ui.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.workspace.R
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeFilledTonalButton
import top.tianyan.app.ui.components.RuntimeOutlinedButton
import top.tianyan.app.ui.components.TianyanSandboxIllustration

@Composable
internal fun WorkspaceEmptyState(
    enabled: Boolean,
    onCreate: () -> Unit,
    onImport: () -> Unit,
) {
    RuntimeCard(
        modifier = Modifier.padding(top = 24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        borderColor = Color.Transparent,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TianyanSandboxIllustration()
            Text(
                text = stringResource(R.string.workspace_no_projects),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.workspace_no_projects_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Row(
                modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RuntimeFilledTonalButton(onClick = onCreate, enabled = enabled) {
                    Text(stringResource(R.string.workspace_menu_create), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                RuntimeOutlinedButton(onClick = onImport, enabled = enabled) {
                    Text(stringResource(R.string.workspace_menu_import), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
