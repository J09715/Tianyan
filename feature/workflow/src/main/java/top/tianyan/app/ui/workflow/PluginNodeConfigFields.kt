package top.tianyan.app.ui.workflow

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import top.tianyan.app.feature.workflow.R
import top.tianyan.app.ui.components.RuntimeFilledTextField

@Composable
internal fun PluginNodeConfigFields(config: MutableMap<String, String>) {
    listOf("plugin" to R.string.plugin_node_id, "extension" to R.string.plugin_node_extension, "arguments" to R.string.plugin_node_arguments).forEach { (key, label) ->
        RuntimeFilledTextField(value = config[key].orEmpty(), onValueChange = { config[key] = it },
            label = { Text(stringResource(label)) }, minLines = if (key == "arguments") 3 else 1, maxLines = 8, modifier = Modifier.fillMaxWidth())
    }
    Text(stringResource(R.string.plugin_node_scope), style = MaterialTheme.typography.bodySmall)
}
