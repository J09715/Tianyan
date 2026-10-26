package top.tianyan.app.ui.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.workspace.R
import top.tianyan.app.template.InstalledProjectTemplate
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeIconButton as IconButton
import top.tianyan.app.ui.components.RuntimeTextButton as TextButton
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.RuntimeOutlinedButton

/** 项目模板管理弹窗：导入 / 导出 / 删除 / 规格说明。 */
@Composable
internal fun TemplateManagerDialog(
    templates: List<InstalledProjectTemplate>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onImport: () -> Unit,
    onExport: (InstalledProjectTemplate) -> Unit,
    onDelete: (InstalledProjectTemplate) -> Unit,
    onShowSpec: () -> Unit,
) {
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_menu_templates), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuntimeButton(onClick = onImport, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.workspace_template_import_zip))
                    }
                    RuntimeOutlinedButton(onClick = onShowSpec, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.workspace_template_spec))
                    }
                }
                templates.groupBy { it.manifest.projectType }.forEach { (type, typeTemplates) ->
                    Text(
                        type.name.lowercase().replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    typeTemplates.forEach { template ->
                        RuntimeCard(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    modifier = Modifier.size(56.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                ) {
                                    template.previewFile?.let { TemplatePreviewImage(it, Modifier.fillMaxSize()) }
                                }
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(template.manifest.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text(
                                        "${template.manifest.category.name} · ${if (template.isBundled) stringResource(R.string.workspace_template_bundled) else stringResource(R.string.workspace_template_user)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        template.manifest.id,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                TextButton(onClick = { onExport(template) }, enabled = !busy) { Text(stringResource(R.string.workspace_action_export)) }
                                if (!template.isBundled) {
                                    IconButton(
                                        onClick = { onDelete(template) },
                                        enabled = !busy,
                                        contentDescription = stringResource(R.string.workspace_cd_delete_template),
                                    ) {
                                        RuntimeIcon(RuntimeIconName.Trash, Modifier.size(18.dp), MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.workspace_action_done)) } },
    )
}

/** 项目模板规格说明弹窗（manifest 结构 / 变量 / 预览 / hooks / 校验）。 */
@Composable
internal fun ProjectTemplateSpecDialog(onDismiss: () -> Unit) {
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_spec_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.workspace_spec_intro))
                Text(stringResource(R.string.workspace_spec_min_structure), fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.workspace_spec_min_structure_body),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(stringResource(R.string.workspace_spec_manifest), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.workspace_spec_manifest_body), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.workspace_spec_variables), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.workspace_spec_variables_body), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.workspace_spec_preview), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.workspace_spec_preview_body), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.workspace_spec_hooks), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.workspace_spec_hooks_body), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.workspace_spec_validation), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.workspace_spec_validation_body), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.workspace_spec_footer), color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.workspace_spec_got_it)) } },
    )
}
