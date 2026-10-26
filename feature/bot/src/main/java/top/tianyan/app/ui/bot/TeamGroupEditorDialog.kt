package top.tianyan.app.ui.bot

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.tianyan.app.core.model.bot.*
import top.tianyan.app.ui.components.*

@Composable
internal fun TeamGroupEditorDialog(group: BotMemberGroup, bots: List<Bot>, onDismiss: () -> Unit,
    onSave: suspend (BotMemberGroup) -> Unit) {
    val baseRevision = rememberSaveable(group.id) { group.draftRevision }
    var name by rememberSaveable(group.id) { mutableStateOf(group.name) }
    var selected by rememberSaveable(group.id, stateSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })) {
        mutableStateOf(group.memberIds.toSet())
    }
    var root by rememberSaveable(group.id) { mutableStateOf(group.definition?.adminBotId ?: group.memberIds.firstOrNull().orEmpty()) }
    var supervisors by rememberSaveable(group.id, stateSaver = SupervisorMapSaver) { mutableStateOf(group.definition?.supervisors.orEmpty()) }
    var rules by rememberSaveable(group.id, stateSaver = ReviewRulesSaver) { mutableStateOf(group.definition?.governance?.rules.orEmpty()) }
    var selectingRoot by rememberSaveable { mutableStateOf(false) }
    var editingManagement by rememberSaveable { mutableStateOf(false) }
    var selectedNode by rememberSaveable { mutableStateOf<String?>(null) }
    var editingReview by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val members = bots.filter { it.id in selected }
    val definition = BotTeamDefinition(root, supervisors.filter { it.key in selected && it.value in selected && it.key != root }, GroupGovernance(rules))
    val team = definition.team(selected)
    RuntimeAlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("创作团队组") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RuntimeFilledTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("团队名称") }, enabled = !saving, modifier = Modifier.fillMaxWidth())
            Text("修改先保存为草稿，不影响引用群。回到团队列表，通过“发布与升级”选择版本和升级范围。", style = MaterialTheme.typography.bodySmall)
            MemberSelection(bots = bots, selectedIds = selected, onSelectionChange = {
                selected = it
                if (root !in selected) root = bots.firstOrNull { bot -> bot.id in selected }?.id.orEmpty()
            })
            RuntimeTextButton(enabled = !saving && members.isNotEmpty(), onClick = { selectingRoot = true }) {
                Text("团队主控：${members.firstOrNull { it.id == root }?.name ?: "请选择"}")
            }
            if (root in selected) {
                Text("组织与审议架构", style = MaterialTheme.typography.titleMedium)
                TeamArchitectureGraph(team, definition.governance, members, editable = !saving) { id ->
                    if (id == root) selectingRoot = true else { selectedNode = id; editingManagement = true }
                }
                Row {
                    RuntimeTextButton(enabled = !saving, onClick = { selectedNode = null; editingManagement = true }) { Text("编辑管理关系") }
                    RuntimeTextButton(enabled = !saving, onClick = { editingReview = true }) { Text("编辑审议规则") }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        RuntimeButton(enabled = !saving && name.isNotBlank() && root in selected, onClick = {
            saving = true
            scope.launch {
                try {
                    definition.validate(selected)
                    onSave(group.copy(draftRevision = baseRevision, name = name.trim(), memberIds = bots.filter { it.id in selected }.map { it.id }, definition = definition))
                } catch (failure: Exception) { error = failure.message }
                finally { saving = false }
            }
        }) { Text(if (saving) "保存中…" else "保存草稿") }
    }, dismissButton = { RuntimeTextButton(enabled = !saving, onClick = onDismiss) { Text("取消") } })
    if (selectingRoot) RuntimeAlertDialog(onDismissRequest = { selectingRoot = false }, title = { Text("选择团队主控") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            members.forEach { member -> RuntimeTextButton(onClick = { root = member.id; selectingRoot = false }) { Text(member.name) } }
        }
    }, confirmButton = { RuntimeTextButton(onClick = { selectingRoot = false }) { Text("取消") } })
    if (editingManagement) GroupTeamDialog(team, members, { editingManagement = false },
        governance = definition.governance, initialMemberId = selectedNode) { supervisors = it }
    if (editingReview) GroupGovernanceDialog(definition.governance, team, members, emptyList(), { editingReview = false }) { rules = it.rules }
}

internal val SupervisorMapSaver = listSaver<Map<String, String>, String>(
    save = { it.entries.flatMap { entry -> listOf(entry.key, entry.value) } },
    restore = { it.chunked(2).associate { entry -> entry[0] to entry[1] } },
)
