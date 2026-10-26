package top.tianyan.app.ui.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import top.tianyan.app.ui.components.RuntimeFilledTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.workspace.R
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeCircularProgressIndicator
import top.tianyan.app.ui.components.RuntimeTextButton
import top.tianyan.app.ui.components.RuntimeButton

@Composable
internal fun WorkspaceNameDialog(
    title: String,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    validationError: String?,
    operationError: String?,
    busy: Boolean,
    actionLabel: String,
    enabled: Boolean,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    RuntimeAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RuntimeFilledTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text(label) },
                    enabled = !busy,
                    isError = validationError != null,
                    supportingText = validationError?.let { error -> { Text(error) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                operationError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            RuntimeButton(onClick = onSubmit, enabled = enabled && !busy) {
                if (busy) RuntimeCircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                else Text(actionLabel)
            }
        },
        dismissButton = {
            RuntimeTextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.workspace_cancel)) }
        },
    )
}
