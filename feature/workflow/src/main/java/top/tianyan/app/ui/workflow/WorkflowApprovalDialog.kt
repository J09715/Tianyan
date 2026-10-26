package top.tianyan.app.ui.workflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import top.tianyan.app.feature.workflow.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.workflow.WorkflowApprovalRequest
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.RuntimeOutlinedButton
import top.tianyan.app.ui.components.RuntimeSwitch

@Composable
internal fun ApprovalDialog(request: WorkflowApprovalRequest, onDecision: (Boolean, Map<String, String>) -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    LaunchedEffect(request.executionId, request.nodeId) {
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
    }
    val values = remember(request) { mutableStateMapOf<String, String>().apply { request.requestedVariables.forEach { put(it, "") } } }
    var reviewed by remember(request) { mutableStateOf(false) }
    RuntimeAlertDialog(
        onDismissRequest = { onDecision(false, emptyMap()) },
        title = { Text(request.title) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (request.description.isNotBlank()) Text(request.description, style = MaterialTheme.typography.bodyMedium)
                if (request.reviewToken != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuntimeSwitch(checked = reviewed, onCheckedChange = { reviewed = it })
                    Text(stringResource(R.string.workflow_review_configuration))
                }
                request.requestedVariables.forEach { key ->
                    OutlinedTextField(value = values[key].orEmpty(), onValueChange = { values[key] = it }, label = { Text(key, maxLines = 1, overflow = TextOverflow.Ellipsis) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
            }
        },
        dismissButton = { RuntimeOutlinedButton(onClick = { onDecision(false, emptyMap()) }) { Text("拒绝", maxLines = 1) } },
        confirmButton = { RuntimeButton(enabled = request.reviewToken == null || reviewed, onClick = { onDecision(true, values.toMap()) }) { Text("确认执行", maxLines = 1) } },
    )
}
