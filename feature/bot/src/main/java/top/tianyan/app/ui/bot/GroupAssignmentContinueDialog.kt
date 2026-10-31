package top.tianyan.app.ui.bot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import top.tianyan.app.ui.components.RuntimeFilledTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import top.tianyan.app.core.model.bot.BotAssignment
import top.tianyan.app.feature.bot.R
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.RuntimeTextButton

@Composable
internal fun GroupAssignmentContinueDialog(task: BotAssignment, onDismiss: () -> Unit, onContinue: suspend (String, String, String) -> Unit) {
    var instruction by rememberSaveable(task.id) { mutableStateOf("") }
    val requestId = rememberSaveable(task.id, instruction) { java.util.UUID.randomUUID().toString() }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    RuntimeAlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text(stringResource(R.string.fbot_assignment_continue)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(task.title, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.fbot_assignment_continue_hint), style = MaterialTheme.typography.bodySmall)
                RuntimeFilledTextField(
                    value = instruction, onValueChange = { instruction = it }, enabled = !sending,
                    label = { Text(stringResource(R.string.fbot_assignment_instruction)) }, minLines = 3,
                    modifier = Modifier.fillMaxWidth(), isError = error != null,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            RuntimeButton(enabled = !sending && instruction.isNotBlank(), onClick = {
                sending = true
                scope.launch {
                    runCatching { onContinue(task.id, instruction, requestId) }
                        .onSuccess { onDismiss() }.onFailure { error = it.message }
                    sending = false
                }
            }) { Text(stringResource(R.string.fbot_assignment_submit)) }
        },
        dismissButton = { RuntimeTextButton(onClick = onDismiss, enabled = !sending) { Text(stringResource(R.string.fbot_close)) } },
    )
}
