package top.tianyan.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.tianyan.app.harness.model.ModelMetadataRegistry
import top.tianyan.app.ui.components.*

/** 长模型名称使用同一列表密度，多选状态与能力说明保持各自层级。 */
@Composable
internal fun ModelEditorCandidateList(models: List<String>, selectedModels: Set<String>, onToggle: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(models, key = { it }) { model ->
            RuntimeSelectionRow(headline = model, selected = model in selectedModels, multiple = true,
                onClick = { onToggle(model) },
                leadingContent = { AutoModelIcon(model, size = 32.dp) },
                supportingContent = {
                    val capability = remember(model) { ModelMetadataRegistry.capabilityOf(model) }
                    if (capability.known) ModelCapabilityTags(ModelCapabilityUi(
                        capability.visionInput, capability.tool, capability.reasoning, capability.contextTokens))
                })
        }
    }
}
