package top.tianyan.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.database.AiModelEntity
import top.tianyan.app.feature.chat.R
import top.tianyan.app.ui.components.*

@Composable
internal fun ModelDialog(
    models: List<AiModelEntity>,
    selectedProfileId: String?,
    selectedModelVariant: String?,
    providerModelIds: List<String>,
    discoveringProviderModels: Boolean,
    providerModelDiscoveryError: String?,
    modelPickerProfileId: String?,
    onDismiss: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onSelectSubModel: (profileId: String, subModel: String) -> Unit,
    onOpenModelPicker: (String) -> Unit,
    onCloseModelPicker: () -> Unit,
    onRefreshModels: (String) -> Unit,
    onSwitchModel: (profileId: String, modelId: String) -> Unit,
    onCreate: () -> Unit,
    onDelete: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var deleteTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    val pickingProfile = models.firstOrNull { it.id == modelPickerProfileId }
    if (pickingProfile != null) {
        ProviderModelPickerDialog(
            profile = pickingProfile,
            selectedModelVariant = selectedModelVariant.takeIf { pickingProfile.id == selectedProfileId },
            modelIds = providerModelIds, discovering = discoveringProviderModels, error = providerModelDiscoveryError,
            onDismiss = onCloseModelPicker, onRefresh = { onRefreshModels(pickingProfile.id) },
            onSelect = { onSwitchModel(pickingProfile.id, it) },
        )
        return
    }
    val filtered = remember(models, query, selectedProfileId, selectedModelVariant) {
        models.filter { query.isBlank() || listOfNotNull(it.name, it.provider, it.model, selectedModelVariant.takeIf { _ -> it.id == selectedProfileId }).any { value -> value.contains(query.trim(), true) } }
    }
    RuntimeSelectionSheet(
        title = stringResource(R.string.chat_select_model),
        subtitle = stringResource(R.string.chat_select_provider_hint),
        onDismissRequest = onDismiss,
        actions = {
            RuntimeFilledTonalButton(onClick = { onDismiss(); onCreate() }, modifier = Modifier.fillMaxWidth()) {
                RuntimeIcon(RuntimeIconName.Plus, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.chat_add_model))
            }
        },
    ) {
        RuntimeSearchBar(query, { query = it }, Modifier.fillMaxWidth(),
            placeholder = stringResource(R.string.chat_search_provider))
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (filtered.isEmpty()) item {
                Text(stringResource(if (models.isEmpty()) R.string.chat_no_models else R.string.chat_no_matching_models),
                    Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(filtered, key = { it.id }) { profile ->
                val current = profile.id == selectedProfileId
                val options = providerModelOptions(profile.model, selectedModelVariant.takeIf { current })
                Column {
                    RuntimeSelectionRow(
                        headline = profile.name.ifBlank { profile.provider }, selected = current,
                        supportingText = profile.provider.takeUnless { it.equals(profile.name, ignoreCase = true) },
                        onClick = { onSelectProfile(profile.id) },
                        leadingContent = {
                            ModelBadge(modelId = selectedModelVariant.takeIf { current } ?: profile.model,
                                providerIdOrName = profile.provider, size = 32.dp)
                        },
                        trailingContent = {
                            RuntimeIconButton({ deleteTargetId = profile.id },
                                contentDescription = stringResource(R.string.chat_delete_model_profile)) {
                                RuntimeIcon(RuntimeIconName.Trash, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                    )
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (options.size > 1) {
                            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(options) { modelId ->
                                    RuntimeFilterChip(selected = current && modelId == selectedModelVariant,
                                        onClick = { onSelectSubModel(profile.id, modelId); onDismiss() },
                                        shape = MaterialTheme.shapes.small,
                                        label = { Text(modelId, Modifier.widthIn(max = 200.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) })
                                }
                            }
                        } else Text(options.firstOrNull().orEmpty().ifBlank { stringResource(R.string.chat_model_not_set) },
                            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        RuntimeTextButton({ onOpenModelPicker(profile.id) }) { Text(stringResource(R.string.chat_switch_model)) }
                    }
                }
            }
        }
    }
    deleteTargetId?.let { targetId ->
        RuntimeAlertDialog(
            onDismissRequest = { deleteTargetId = null },
            title = { Text(stringResource(R.string.chat_delete_model_title)) },
            text = { Text(stringResource(R.string.chat_delete_model_message)) },
            confirmButton = {
                RuntimeTextButton(onClick = { deleteTargetId = null; onDelete(targetId) }) {
                    Text(stringResource(R.string.chat_confirm_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { RuntimeTextButton({ deleteTargetId = null }) { Text(stringResource(R.string.chat_cancel)) } },
        )
    }
}
