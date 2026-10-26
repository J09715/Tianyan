package top.tianyan.app.ui.bot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.tianyan.app.core.model.bot.TaskBudgetState
import top.tianyan.app.feature.bot.R
import top.tianyan.app.ui.components.*

@Composable
internal fun GroupBudgetDialog(defaultTokens: Long, budgets: List<TaskBudgetState>, onDismiss: () -> Unit,
    onSave: suspend (String?, Long) -> Unit) {
    var editing by remember { mutableStateOf<TaskBudgetState?>(null) }
    var editDefault by remember { mutableStateOf(false) }
    RuntimeAlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.fbot_budget_title)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.fbot_budget_hint), style = MaterialTheme.typography.bodySmall)
                RuntimeTextButton(onClick = { editDefault = true }) {
                    Text(stringResource(R.string.fbot_budget_default, defaultTokens))
                }
                budgets.forEach { state ->
                    RuntimeCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(state.title, style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.fbot_budget_state, state.limitTokens, state.usedTokens, state.heldTokens))
                            if (state.unknownRequests > 0) Text(stringResource(R.string.fbot_budget_unknown, state.unknownRequests), color = MaterialTheme.colorScheme.error)
                            RuntimeTextButton(onClick = { editing = state }) { Text(stringResource(R.string.fbot_budget_adjust)) }
                        }
                    }
                }
            }
        }, confirmButton = { RuntimeTextButton(onClick = onDismiss) { Text(stringResource(R.string.fbot_close)) } })
    if (editDefault || editing != null) BudgetLimitEditor(editing?.id, editing?.limitTokens ?: defaultTokens,
        { editDefault = false; editing = null }, onSave)
}

@Composable
private fun BudgetLimitEditor(id: String?, initial: Long, onDismiss: () -> Unit, onSave: suspend (String?, Long) -> Unit) {
    var value by remember(id) { mutableStateOf(initial.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    RuntimeAlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text(stringResource(R.string.fbot_budget_adjust)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RuntimeFilledTextField(value = value, onValueChange = { value = it.take(13) },
                    label = { Text(stringResource(R.string.fbot_budget_limit)) }, isError = error != null, singleLine = true)
                Text(stringResource(R.string.fbot_budget_edit_hint), style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = {
            RuntimeButton(enabled = !saving, onClick = {
                saving = true
                scope.launch {
                    runCatching {
                        val tokens = requireNotNull(value.toLongOrNull()) { "请输入整数 token 额度" }
                        require(tokens in 0..TaskBudgetState.MAX_LIMIT) { "额度必须介于 0 与 ${TaskBudgetState.MAX_LIMIT}" }
                        onSave(id, tokens)
                    }.onSuccess { onDismiss() }.onFailure { error = it.message }
                    saving = false
                }
            }) { Text(stringResource(R.string.fbot_save)) }
        }, dismissButton = { RuntimeTextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.fbot_cancel)) } })
}
