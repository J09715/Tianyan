package top.tianyan.app.ui.workflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import top.tianyan.app.ui.components.RuntimeFilledTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.workflow.FailurePolicy
import top.tianyan.app.core.model.workflow.WorkflowNodeType
import top.tianyan.app.ui.components.RuntimeOutlinedButton

@Composable
internal fun WorkflowNodePolicyFields(type: WorkflowNodeType, timeout: String, onTimeout: (String) -> Unit,
    policy: FailurePolicy, onPolicy: (FailurePolicy) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (type != WorkflowNodeType.HUMAN_APPROVAL) RuntimeFilledTextField(
            value = timeout, onValueChange = { onTimeout(it.filter(Char::isDigit).take(4)) },
            label = { Text("超时（秒）") }, supportingText = { Text("1–3600 秒") },
            isError = timeout.toIntOrNull()?.let { it in 1..3600 } != true,
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        Column(Modifier.weight(1.2f)) {
            RuntimeOutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("策略：${policy.label()}", maxLines = 1)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                FailurePolicy.entries.forEach { item ->
                    DropdownMenuItem(text = { Text(item.label()) }, onClick = { onPolicy(item); expanded = false })
                }
            }
        }
    }
}

private fun FailurePolicy.label() = when (this) {
    FailurePolicy.ABORT -> "终止流程"
    FailurePolicy.CONTINUE -> "继续执行"
    FailurePolicy.RETRY_ONCE -> "重试一次"
    FailurePolicy.ASK_USER -> "询问用户"
}
