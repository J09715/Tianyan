package top.tianyan.app.ui.bot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.database.AgentApprovalRequestEntity
import top.tianyan.app.feature.bot.R
import top.tianyan.app.harness.AskUserQuestions
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.RuntimeFilledTextField
import top.tianyan.app.ui.components.RuntimeTextButton

/** Decisions use the existing Harness broker; this view never changes execution authority. */
@Composable
internal fun GroupApprovalDialog(
    request: AgentApprovalRequestEntity,
    onDismiss: () -> Unit,
    onDecide: (Boolean) -> Unit,
    onAnswer: (String) -> Unit,
) {
    val isQuestion = request.toolName == AskUserQuestions.TOOL_NAME
    val questions = remember(request.id) {
        if (isQuestion) runCatching {
            AskUserQuestions.parseJson(request.argumentsJson)
        }.getOrDefault(emptyList()) else emptyList()
    }
    val answers = remember(request.id) { mutableStateMapOf<Int, String>() }
    val answered = questions.isNotEmpty() && questions.indices.all { !answers[it].isNullOrBlank() }
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isQuestion) R.string.fbot_question_title else R.string.fbot_approval_title)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isQuestion) {
                    Text(request.summary)
                    Text(request.reason)
                    Text(request.argumentsJson)
                } else if (questions.isEmpty()) {
                    Text(stringResource(R.string.fbot_action_parse_failed))
                } else questions.forEachIndexed { index, question ->
                    Text(question.question)
                    question.options.forEach { option ->
                        RuntimeTextButton(onClick = { answers[index] = option.label }) {
                            Text(option.label + option.description?.let { " · $it" }.orEmpty())
                        }
                    }
                    if (question.allowCustom) {
                        RuntimeFilledTextField(
                            value = answers[index].orEmpty(),
                            onValueChange = { answers[index] = it },
                            label = { Text(stringResource(R.string.fbot_answer_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else Text(answers[index].orEmpty())
                }
            }
        },
        confirmButton = {
            RuntimeButton(enabled = !isQuestion || answered, onClick = {
                if (!isQuestion) onDecide(true) else onAnswer(AskUserQuestions.answersJson(
                    questions.indices.map { answers[it].orEmpty() },
                ))
            }) { Text(stringResource(if (isQuestion) R.string.fbot_answer_action else R.string.fbot_approve_once)) }
        },
        dismissButton = {
            RuntimeTextButton(onClick = { onDecide(false) }) { Text(stringResource(R.string.fbot_reject_action)) }
        },
    )
}

@Composable
internal fun GroupPendingActions(viewModel: GroupChatViewModel) {
    val approvals by viewModel.pendingApprovals.collectAsStateWithLifecycle()
    var show by remember { mutableStateOf(false) }
    approvals.firstOrNull()?.let { request ->
        RuntimeTextButton(onClick = { show = true }) {
            Text(stringResource(R.string.fbot_pending_action, approvals.size))
        }
        if (show) GroupApprovalDialog(
            request = request,
            onDismiss = { show = false },
            onDecide = { allowed -> viewModel.resolveApproval(request.id, allowed); show = false },
            onAnswer = { answers -> viewModel.answerQuestion(request.id, answers); show = false },
        )
    }
}
