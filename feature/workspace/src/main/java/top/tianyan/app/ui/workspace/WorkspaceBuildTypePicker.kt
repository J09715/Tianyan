package top.tianyan.app.ui.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.datastore.WorkshopKeystore
import top.tianyan.app.feature.workspace.R
import top.tianyan.app.runtime.WorkspaceProject
import top.tianyan.app.runtime.build.WorkshopBuildType
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeButton as Button
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeTextButton as TextButton

/** 构建类型选择：Debug 直接构建；Release 需选择已登记签名，没有签名则引导去创建。 */
@Composable
internal fun BuildTypePickerDialog(
    project: WorkspaceProject,
    keystores: List<WorkshopKeystore>,
    onDismiss: () -> Unit,
    onConfirm: (WorkshopBuildType, WorkshopKeystore?) -> Unit,
    onManageSigning: () -> Unit,
) {
    var selectedType by remember { mutableStateOf(WorkshopBuildType.DEBUG) }
    var selectedKeystoreId by remember { mutableStateOf(keystores.firstOrNull()?.id.orEmpty()) }
    val isRelease = selectedType == WorkshopBuildType.RELEASE
    val selectedKeystore = keystores.firstOrNull { it.id == selectedKeystoreId }

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_build_type_title, project.name), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BuildTypeOption(
                    title = stringResource(R.string.workspace_build_type_debug),
                    description = stringResource(R.string.workspace_build_type_debug_description),
                    selected = !isRelease,
                    onClick = { selectedType = WorkshopBuildType.DEBUG },
                )
                BuildTypeOption(
                    title = stringResource(R.string.workspace_build_type_release),
                    description = stringResource(R.string.workspace_build_type_release_description),
                    selected = isRelease,
                    onClick = { selectedType = WorkshopBuildType.RELEASE },
                )
                if (isRelease) {
                    if (keystores.isEmpty()) {
                        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.workspace_build_no_keystore), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                                Button(onClick = onManageSigning) { Text(stringResource(R.string.workspace_build_go_create_keystore)) }
                            }
                        }
                    } else {
                        Text(stringResource(R.string.workspace_build_pick_keystore), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        keystores.forEach { keystore ->
                            BuildTypeOption(
                                title = keystore.name,
                                description = "alias ${keystore.alias}",
                                selected = keystore.id == selectedKeystoreId,
                                onClick = { selectedKeystoreId = keystore.id },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selectedType, if (isRelease) selectedKeystore else null) },
                enabled = !isRelease || selectedKeystore != null,
            ) { Text(stringResource(R.string.workspace_build_start)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.workspace_cancel)) }
        },
    )
}

/** 构建类型 / 签名选择条目。 */
@Composable
private fun BuildTypeOption(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RuntimeIcon(
                if (selected) RuntimeIconName.Check else RuntimeIconName.Key,
                Modifier.size(18.dp),
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
