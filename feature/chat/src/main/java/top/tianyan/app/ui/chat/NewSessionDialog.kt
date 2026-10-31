package top.tianyan.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import top.tianyan.app.ui.components.RuntimeAlertDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import top.tianyan.app.ui.components.RuntimeButton as Button
import top.tianyan.app.ui.components.RuntimeCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import top.tianyan.app.ui.components.RuntimeFilledTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import top.tianyan.app.ui.components.RuntimeTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import top.tianyan.app.feature.chat.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.tianyan.app.runtime.WorkspaceProject
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.runtime.ProjectType

@Composable
internal fun NewSessionDialog(
    workspaces: List<WorkspaceProject>,
    catalog: top.tianyan.app.runtime.SessionWorkspaceCatalogState,
    onDismiss: () -> Unit,
    onCreate: (title: String, workspace: String, projectType: ProjectType) -> Unit,
) {
    val defaultTitle = stringResource(R.string.chat_new_session)
    // 表单状态旋转后保留；枚举以 name 字符串保存
    var title by rememberSaveable { mutableStateOf(defaultTitle) }
    var selected by rememberSaveable(catalog.selectionId, catalog.server) { mutableStateOf("") }
    var selectedTypeName by rememberSaveable(catalog.selectionId, catalog.server) { mutableStateOf(ProjectType.GENERAL.name) }
    val selectedType = ProjectType.valueOf(selectedTypeName)
    var typeMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val quickTags = listOf(
        defaultTitle,
        stringResource(R.string.chat_quick_bug),
        stringResource(R.string.chat_quick_feature),
        stringResource(R.string.chat_quick_environment),
        stringResource(R.string.chat_quick_refactor),
    )

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuntimeIcon(RuntimeIconName.Plus, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.chat_new_agent_session), fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.chat_session_title), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                RuntimeFilledTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = { Text(stringResource(R.string.chat_session_name_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                // 快捷预设标题标签
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    quickTags.forEach { tag ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (title == tag) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (title == tag) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else Color.Transparent,
                            ),
                            modifier = Modifier.clickable { title = tag },
                        ) {
                            Text(
                                tag,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (title == tag) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Text(
                    stringResource(R.string.chat_link_workspace),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                LazyColumn(
                    // A nested lazy list needs finite constraints inside the scrolling dialog form.
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item {
                        WorkspaceOption(stringResource(R.string.chat_no_workspace), catalog.server?.workspace ?: "/workspace", selected == "", onSelect = {
                            selected = ""
                            selectedTypeName = ProjectType.GENERAL.name
                        })
                    }
                    item { if (catalog.loading) CircularProgressIndicator(); catalog.error?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
                    items(workspaces.size, key = { workspaces[it].linuxPath }) { index ->
                        val ws = workspaces[index]
                        WorkspaceOption(ws.name, ws.linuxPath, selected == ws.linuxPath) {
                            selected = ws.linuxPath
                            selectedTypeName = ws.projectType.name
                        }
                    }
                }

                val selectedProject = workspaces.firstOrNull { it.linuxPath == selected }
                // Keep the detected type as the default, but allow an imported
                // repository to be assigned to a different specialist Agent.
                Text(
                    if (selectedProject == null || selectedProject.projectType == ProjectType.GENERAL) {
                        stringResource(R.string.chat_project_type_manual)
                    } else {
                        stringResource(R.string.chat_project_type_detected)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Box {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().clickable { typeMenuExpanded = true },
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                selectedType.displayName,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(stringResource(R.string.chat_select), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    DropdownMenu(expanded = typeMenuExpanded, onDismissRequest = { typeMenuExpanded = false }) {
                        ProjectType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.displayName) },
                                onClick = {
                                    selectedTypeName = type.name
                                    typeMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(title.ifBlank { defaultTitle }, selected, selectedType) },
                enabled = !catalog.loading && (selected.isBlank() || catalog.error == null),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(stringResource(R.string.chat_create_session))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel)) }
        },
    )
}
