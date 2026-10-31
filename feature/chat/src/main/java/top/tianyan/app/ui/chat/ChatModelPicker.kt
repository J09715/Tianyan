package top.tianyan.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.database.AiModelEntity
import top.tianyan.app.feature.chat.R
import top.tianyan.app.harness.model.ModelMetadataRegistry
import top.tianyan.app.ui.components.*

/** 发现列表与手动输入共用一个面板，避免列表、表单和多组按钮互相挤占高度。 */
@Composable
internal fun ProviderModelPickerDialog(
    profile: AiModelEntity,
    selectedModelVariant: String?,
    modelIds: List<String>,
    discovering: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var query by rememberSaveable(profile.id) { mutableStateOf("") }
    var manual by rememberSaveable(profile.id) { mutableStateOf(false) }
    var manualId by rememberSaveable(profile.id, selectedModelVariant) {
        mutableStateOf(selectedModelVariant ?: profile.model.substringBefore(',').trim())
    }
    val candidates = remember(modelIds, selectedModelVariant, query) {
        (listOfNotNull(selectedModelVariant) + modelIds).distinct()
            .filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }
    }
    RuntimeSelectionSheet(
        title = stringResource(R.string.chat_switch_model_title),
        subtitle = profile.name.ifBlank { profile.provider },
        onDismissRequest = onDismiss,
        actions = {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RuntimeTextButton(onClick = { manual = !manual }) {
                    Text(stringResource(if (manual) R.string.chat_model_list else R.string.chat_manual_model))
                }
                if (manual) {
                    RuntimeButton(onClick = { onSelect(manualId.trim()) }, enabled = manualId.isNotBlank()) {
                        Text(stringResource(R.string.chat_apply_model))
                    }
                } else {
                    RuntimeTextButton(onClick = onRefresh, enabled = !discovering) {
                        RuntimeIcon(RuntimeIconName.Refresh, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.chat_refresh_models))
                    }
                }
            }
        },
    ) {
        if (manual) {
            // 使用可滚动表单，横屏和大字体下仍可到达操作区。
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                item {
                    RuntimeFilledTextField(value = manualId, onValueChange = { manualId = it },
                        label = { Text(stringResource(R.string.chat_model_id)) },
                        supportingText = { Text(stringResource(R.string.chat_switch_model_hint)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
            }
        } else {
            RuntimeSearchBar(query, { query = it }, Modifier.fillMaxWidth(),
                placeholder = stringResource(R.string.chat_search_models))
            if (discovering) Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RuntimeCircularProgressIndicator(Modifier.size(24.dp))
                Text(stringResource(R.string.chat_model_discovering), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!error.isNullOrBlank()) item {
                    Text(error, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error)
                }
                if (candidates.isEmpty() && !discovering) item {
                    Text(stringResource(R.string.chat_no_matching_models), Modifier.padding(vertical = 24.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(candidates, key = { it }) { modelId ->
                    RuntimeSelectionRow(headline = modelId, selected = modelId == selectedModelVariant,
                        onClick = { onSelect(modelId) },
                        leadingContent = { AutoModelIcon(modelId, size = 32.dp) },
                        supportingContent = { ModelCapabilityTags(remember(modelId) { modelCapabilityUi(modelId) }) })
                }
            }
        }
    }
}

private fun modelCapabilityUi(modelId: String): ModelCapabilityUi {
    val capability = ModelMetadataRegistry.capabilityOf(modelId)
    if (!capability.known) return ModelCapabilityUi()
    return ModelCapabilityUi(
        vision = capability.visionInput,
        tool = capability.tool,
        reasoning = capability.reasoning,
        contextTokens = capability.contextTokens,
    )
}

/** 当前会话可使用供应商发现或手动填写的模型，即使它不在档案保存的候选列表里。 */
internal fun providerModelOptions(configuredModels: String, selectedVariant: String?): List<String> =
    (configuredModels.split(',') + listOfNotNull(selectedVariant))
        .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
