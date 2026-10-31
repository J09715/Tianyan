package top.tianyan.app.ui.bot

import java.util.UUID
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.tianyan.app.core.model.bot.*
import top.tianyan.app.feature.bot.R
import top.tianyan.app.ui.components.*

@Composable
internal fun GroupGovernanceDialog(policy: GroupGovernance, team: GroupTeam, members: List<Bot>,
    proposals: List<GroupProposal>, onDismiss: () -> Unit, onSave: suspend (GroupGovernance) -> Unit) {
    var draft by rememberSaveable(stateSaver = ReviewRulesSaver) { mutableStateOf(policy.rules) }
    var selecting by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val names = members.associate { it.id to it.name }
    fun change(id: String, transform: (GroupReviewRule) -> GroupReviewRule) { draft = draft.map { if (it.id == id) transform(it) else it } }
    RuntimeAlertDialog(onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.fbot_governance_title)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.fbot_governance_hint), style = MaterialTheme.typography.bodySmall)
                draft.forEach { rule ->
                    RuntimeCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            RuntimeFilledTextField(value = rule.name, onValueChange = { value -> change(rule.id) { it.copy(name = value.take(60)) } },
                                label = { Text(stringResource(R.string.fbot_rule_name)) }, enabled = !saving, modifier = Modifier.fillMaxWidth())
                            listOf(rule.authorId, rule.reviewerId, rule.executorId).forEachIndexed { role, id ->
                                RuntimeTextButton(enabled = !saving, onClick = { selecting = rule.id to role }) {
                                    Text(stringResource(listOf(R.string.fbot_rule_author, R.string.fbot_rule_reviewer, R.string.fbot_rule_executor)[role], names[id] ?: "?"))
                                }
                            }
                            Row {
                                RuntimeTextButton(enabled = !saving && rule.maxRevisions > 1, onClick = { change(rule.id) { it.copy(maxRevisions = it.maxRevisions - 1) } }) { Text("−") }
                                Text(stringResource(R.string.fbot_rule_rounds, rule.maxRevisions), modifier = Modifier.weight(1f))
                                RuntimeTextButton(enabled = !saving && rule.maxRevisions < 10, onClick = { change(rule.id) { it.copy(maxRevisions = it.maxRevisions + 1) } }) { Text("+") }
                            }
                            RuntimeTextButton(enabled = !saving, onClick = { draft = draft.filter { it.id != rule.id } }) { Text(stringResource(R.string.fbot_delete)) }
                        }
                    }
                }
                RuntimeTextButton(enabled = !saving && members.size >= 3 && draft.size < 20, onClick = {
                    draft = draft + GroupReviewRule(UUID.randomUUID().toString(), "审议规则", members[0].id, members[1].id, members[2].id)
                }) { Text(stringResource(R.string.fbot_rule_add)) }
                if (proposals.isNotEmpty()) Text(stringResource(R.string.fbot_proposals_title), style = MaterialTheme.typography.titleSmall)
                proposals.take(40).forEach { proposal ->
                    RuntimeCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Text("${proposal.rule.name} · v${proposal.version} · ${proposal.decision}", style = MaterialTheme.typography.titleSmall)
                            Text(proposal.reason.ifBlank { proposal.content.take(500) }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { RuntimeButton(enabled = !saving, onClick = {
            saving = true
            scope.launch {
                try { val next = GroupGovernance(draft); next.validate(team); onSave(next); onDismiss() }
                catch (failure: Exception) { error = failure.message }
                finally { saving = false }
            }
        }) { Text(stringResource(R.string.fbot_save)) } },
        dismissButton = { RuntimeTextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.fbot_cancel)) } },
    )
    selecting?.let { (ruleId, role) ->
        RuntimeAlertDialog(onDismissRequest = { selecting = null }, title = { Text(stringResource(R.string.fbot_manage_members)) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    members.forEach { member -> RuntimeTextButton(onClick = {
                        change(ruleId) { when (role) { 0 -> it.copy(authorId = member.id); 1 -> it.copy(reviewerId = member.id); else -> it.copy(executorId = member.id) } }
                        selecting = null
                    }) { Text(member.name) } }
                }
            }, confirmButton = { RuntimeTextButton(onClick = { selecting = null }) { Text(stringResource(R.string.fbot_cancel)) } })
    }
}

internal val ReviewRulesSaver = listSaver<List<GroupReviewRule>, String>(
    save = { rules -> rules.flatMap { listOf(it.id, it.name, it.authorId, it.reviewerId, it.executorId, it.maxRevisions.toString()) } },
    restore = { rows -> rows.chunked(6).map { GroupReviewRule(it[0], it[1], it[2], it[3], it[4], it[5].toInt()) } },
)
