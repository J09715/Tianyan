package top.tianyan.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import top.tianyan.app.feature.settings.R
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.previewOrNull
import top.tianyan.app.ui.components.previewStateOf

/** Creative destinations and background services share the adaptive settings shell. */
@Composable
fun FeatureHubScreen(
    onBack: () -> Unit,
    onOpenCustomIteration: () -> Unit,
    onStartRoundtable: () -> Unit,
    onOpenA2uiPoc: () -> Unit,
    viewModel: FeatureHubViewModel? = previewOrNull { koinViewModel() },
) {
    val sentinelState by viewModel?.sentinelState?.collectAsStateWithLifecycle() ?: previewStateOf(top.tianyan.app.harness.workflow.SentinelState())
    val webChatStatus by viewModel?.webChatStatus?.collectAsStateWithLifecycle() ?: previewStateOf(top.tianyan.app.runtime.webchat.WebChatServerStatus())

    PersonalizationScaffold(stringResource(R.string.settings_feature_hub_title), onBack) {
        item("features-intro") {
            RuntimeCard(
                modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
                containerColor = MaterialTheme.colorScheme.primaryContainer, contentPadding = PaddingValues(24.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    RuntimeIcon(RuntimeIconName.Sparkles, Modifier.size(40.dp), MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(stringResource(R.string.settings_material_features_intro),
                        style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        item("creative-features") {
            PersonalizationSection(stringResource(R.string.settings_material_creation)) {
                FeatureLinkRow(RuntimeIconName.Code,
                    stringResource(R.string.settings_feature_custom_iteration_title),
                    stringResource(R.string.settings_feature_custom_iteration_subtitle),
                    first = true, last = false, onClick = onOpenCustomIteration)
                FeatureLinkRow(RuntimeIconName.Community,
                    stringResource(R.string.settings_feature_roundtable_title),
                    stringResource(R.string.settings_feature_roundtable_subtitle),
                    first = false, last = false, onClick = onStartRoundtable)
                FeatureLinkRow(RuntimeIconName.Palette,
                    stringResource(R.string.settings_feature_a2ui_title),
                    stringResource(R.string.settings_feature_a2ui_subtitle),
                    first = false, last = true, onClick = onOpenA2uiPoc)
            }
        }
        item("scheduled-service") {
            PersonalizationSection(stringResource(R.string.settings_material_automation)) {
                SentinelControlRow(state = sentinelState,
                    onEnable = { hour, minute -> viewModel?.enableSentinel(hour, minute) },
                    onDisable = { viewModel?.disableSentinel() })
            }
        }
        item("desktop-service") {
            PersonalizationSection(stringResource(R.string.settings_material_connection)) {
                WebChatControlRow(status = webChatStatus, onToggle = { viewModel?.toggleWebChat(it) })
            }
        }
    }
}
