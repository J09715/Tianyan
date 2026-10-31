package top.tianyan.app.ui.workflow

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import top.tianyan.app.ui.components.RuntimeFilledTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.tianyan.app.ui.components.RuntimeTextButton

@Composable
internal fun WorkflowDelayFields(config: MutableMap<String, String>) {
    val milliseconds = "milliseconds" in config
    val key = if (milliseconds) "milliseconds" else "seconds"
    Column {
        RuntimeFilledTextField(
            value = config[key] ?: config["delaySeconds"] ?: "1",
            onValueChange = { config[key] = it },
            label = { Text(if (milliseconds) "等待毫秒数" else "等待秒数") },
            supportingText = { Text(if (milliseconds) "0–600000 整数，支持 \${VAR}" else "0–600 秒，可写小数或 \${VAR}") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        RuntimeTextButton(onClick = {
            val raw = config[key] ?: config["delaySeconds"] ?: "1"
            val converted = if (milliseconds) raw.toLongOrNull()?.let { (it / 1000.0).toString() }
                else raw.toDoubleOrNull()?.let { (it * 1000).toLong().toString() }
            config.remove(key)
            config.remove("delaySeconds")
            config[if (milliseconds) "seconds" else "milliseconds"] = converted ?: raw
        }, enabled = (config[key] ?: config["delaySeconds"] ?: "1").toDoubleOrNull()?.isFinite() == true) {
            Text(if (milliseconds) "改用秒（数值自动换算）" else "改用毫秒（数值自动换算）")
        }
    }
}
