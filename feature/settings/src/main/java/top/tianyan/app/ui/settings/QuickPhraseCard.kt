package top.tianyan.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.QuickPhrase
import top.tianyan.app.feature.settings.R
import top.tianyan.app.ui.components.*
import top.tianyan.app.ui.settings.LocalizedText as Text

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickPhraseCard(
    phrase: QuickPhrase,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onClick = onEdit,
        contentPadding = PaddingValues(SettingsDensity.cardPadding),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IconTile(parseIconName(phrase.iconName), size = 40.dp, color = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(phrase.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val typeLabel = when (phrase.targetProjectType) {
                        "ANDROID" -> "Android"
                        "FLUTTER" -> "Flutter"
                        "REVERSE" -> "逆向分析"
                        else -> "通用"
                    }
                    Text(typeLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (phrase.description.isNotBlank()) {
                Text(phrase.description, style = settingsSupportingStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(phrase.content, Modifier.fillMaxWidth().padding(16.dp),
                    style = settingsSupportingStyle().copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier.toggleable(phrase.isEnabled, role = Role.Switch, onValueChange = onToggle)
                        .heightIn(min = 48.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(if (phrase.isEnabled) R.string.personal_phrase_enabled else R.string.personal_phrase_disabled),
                        style = MaterialTheme.typography.labelLarge)
                    RuntimeSwitch(phrase.isEnabled, onCheckedChange = null,
                        thumbContent = if (phrase.isEnabled) {
                            { RuntimeIcon(RuntimeIconName.Check, Modifier.size(16.dp), MaterialTheme.colorScheme.onPrimaryContainer) }
                        } else null)
                }
                RuntimeFilledTonalButton(onClick = onEdit) { Text("编辑", maxLines = 1) }
                RuntimeTextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error, maxLines = 1) }
            }
        }
    }
}
