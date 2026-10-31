package top.tianyan.app.ui.bot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import top.tianyan.app.core.database.AiModelEntity
import top.tianyan.app.feature.bot.R
import top.tianyan.app.ui.components.RuntimeDropdownMenu
import top.tianyan.app.ui.components.RuntimeDropdownMenuItem
import top.tianyan.app.ui.components.RuntimeOutlinedButton

@Composable
internal fun BotModelPicker(
    profiles: List<AiModelEntity>, profileId: String?, variant: String?, enabled: Boolean,
    onChange: (String?, String?) -> Unit,
) {
    var chooseProfile by remember { mutableStateOf(false) }
    var chooseModel by remember { mutableStateOf(false) }
    val profile = profiles.firstOrNull { it.id == profileId }
    Column {
        Text(stringResource(R.string.fbot_provider_profile), style = MaterialTheme.typography.labelMedium)
        Box {
            RuntimeOutlinedButton(onClick = { chooseProfile = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(profile?.let { "${it.name} · ${it.provider}" }
                    ?: stringResource(if (profileId == null) R.string.fbot_model_default else R.string.fbot_model_invalid),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            RuntimeDropdownMenu(expanded = chooseProfile, onDismissRequest = { chooseProfile = false }) {
                RuntimeDropdownMenuItem(text = { Text(stringResource(R.string.fbot_model_default)) },
                    onClick = { chooseProfile = false; onChange(null, null) })
                profiles.forEach { item ->
                    RuntimeDropdownMenuItem(text = { Text("${item.name} · ${item.provider}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = { chooseProfile = false; onChange(item.id, null) })
                }
            }
        }
        if (profile != null) {
            Text(stringResource(R.string.fbot_concrete_model), style = MaterialTheme.typography.labelMedium)
            Box {
                RuntimeOutlinedButton(onClick = { chooseModel = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(variant ?: stringResource(R.string.fbot_profile_default), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                RuntimeDropdownMenu(expanded = chooseModel, onDismissRequest = { chooseModel = false }) {
                    RuntimeDropdownMenuItem(text = { Text(stringResource(R.string.fbot_profile_default)) },
                        onClick = { chooseModel = false; onChange(profile.id, null) })
                    profile.model.split(',').map(String::trim).filter(String::isNotBlank).distinct().forEach { model ->
                        RuntimeDropdownMenuItem(text = { Text(model, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            onClick = { chooseModel = false; onChange(profile.id, model) })
                    }
                }
            }
        }
        Text(stringResource(R.string.fbot_model_scope_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
