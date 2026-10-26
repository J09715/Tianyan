package top.tianyan.app.ui.bot

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.tianyan.app.core.model.bot.*
import top.tianyan.app.feature.bot.R
import top.tianyan.app.harness.bot.market.GroupTeamTemplates
import top.tianyan.app.ui.components.*

@Composable
internal fun TeamTemplateDialog(onDismiss: () -> Unit,
    initialTemplateId: String? = null, onImport: suspend (String, String) -> Unit) {
    val initial = GroupTeamTemplates.all.firstOrNull { it.id == initialTemplateId } ?: GroupTeamTemplates.all.first()
    var selected by rememberSaveable { mutableStateOf(initial.id) }
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val template = GroupTeamTemplates.all.single { it.id == selected }
    RuntimeAlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.fbot_team_import)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.fbot_template_hint))
                GroupTeamTemplates.all.forEach { candidate ->
                    RuntimeCard(modifier = Modifier.fillMaxWidth(), onClick = {
                        if (!saving) { selected = candidate.id; name = candidate.name }
                    }) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text((if (selected == candidate.id) "✓ " else "") + candidate.name, style = MaterialTheme.typography.titleSmall)
                            Text(candidate.description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                RuntimeFilledTextField(value = name, onValueChange = { name = it.take(40) },
                    label = { Text("团队组名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !saving)
                val previewMembers = remember(template.id) { template.members.map { member ->
                    Bot(member.key, templateMemberName(member.key), member.role ?: "团队成员", "", member.key,
                        sessionId = "preview:${member.key}", createdAt = 0, updatedAt = 0)
                } }
                val previewTeam = GroupTeam(template.adminKey, template.members.map { it.key }.toSet(),
                    template.members.mapNotNull { member -> member.supervisorKey?.let { member.key to it } }.toMap())
                Text("模板架构预览", style = MaterialTheme.typography.titleSmall)
                TeamArchitectureGraph(previewTeam, GroupGovernance(template.rules), previewMembers)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { RuntimeButton(enabled = !saving && name.isNotBlank(), onClick = {
            saving = true
            scope.launch {
                try { onImport(selected, name); onDismiss() } catch (failure: Exception) { error = failure.message }
                finally { saving = false }
            }
        }) { Text(stringResource(if (saving) R.string.fbot_template_importing else R.string.fbot_team_import)) } },
        dismissButton = { RuntimeTextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.fbot_cancel)) } },
    )
}

private fun templateMemberName(key: String): String = mapOf("taizi" to "太子", "zhongshu" to "中书省", "menxia" to "门下省",
    "shangshu" to "尚书省", "libu-hr" to "吏部", "hubu" to "户部", "libu" to "礼部", "bingbu" to "兵部",
    "xingbu" to "刑部", "gongbu" to "工部", "chief" to "交付主控", "product" to "产品", "review" to "测试审议",
    "lead" to "研发协调", "frontend" to "前端", "backend" to "后端").getValue(key)
