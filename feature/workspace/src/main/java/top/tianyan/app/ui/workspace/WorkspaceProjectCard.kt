package top.tianyan.app.ui.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.tianyan.app.feature.workspace.R
import top.tianyan.app.runtime.ProjectType
import top.tianyan.app.runtime.WorkspaceProject
import top.tianyan.app.ui.components.IconTile
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeCircularProgressIndicator
import top.tianyan.app.ui.components.RuntimeFilledTonalButton as FilledTonalButton
import top.tianyan.app.ui.components.RuntimeIconButton as IconButton
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeLinearProgressIndicator as LinearProgressIndicator
import top.tianyan.app.ui.components.RuntimeTextButton as TextButton

/** 工坊项目卡片：类型徽章 + 路径/体积 + 快捷操作栏。 */
@Composable
internal fun ProjectCard(
    project: WorkspaceProject,
    busy: Boolean,
    isBuilding: Boolean,
    onOpenExplorer: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenWorkflows: () -> Unit,
    onRunProject: () -> Unit,
    onShowBuildLog: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var moreExpanded by remember { mutableStateOf(false) }

    // 颜色收敛：项目类型标识改用主题色轮替，不再引入主题外硬编码色
    val typeBadgeColor = when (project.projectType) {
        ProjectType.ANDROID -> MaterialTheme.colorScheme.primary
        ProjectType.FLUTTER -> MaterialTheme.colorScheme.secondary
        ProjectType.REVERSE -> MaterialTheme.colorScheme.tertiary
        ProjectType.GENERAL, ProjectType.TIANYAN_PLUGIN -> MaterialTheme.colorScheme.primary
    }

    val typeIcon = when (project.projectType) {
        ProjectType.ANDROID -> RuntimeIconName.Android
        ProjectType.FLUTTER -> RuntimeIconName.Flutter
        ProjectType.REVERSE -> RuntimeIconName.Reverse
        ProjectType.GENERAL, ProjectType.TIANYAN_PLUGIN -> RuntimeIconName.Code
    }

    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentPadding = PaddingValues(16.dp),
        // 减法：常态无边框，仅构建中保留主色描边作为状态提示
        borderColor = if (isBuilding) MaterialTheme.colorScheme.primary else Color.Transparent,
        onClick = onOpenExplorer,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                IconTile(typeIcon, size = 40.dp, color = typeBadgeColor)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = project.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Surface(
                            color = if (isBuilding) MaterialTheme.colorScheme.primaryContainer else typeBadgeColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                text = if (isBuilding) stringResource(R.string.workspace_building_badge) else project.projectType.displayName,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                color = if (isBuilding) MaterialTheme.colorScheme.primary else typeBadgeColor,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                    Text(
                        text = "${project.linuxPath} · ${project.sizeBytes.toReadableSize()}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Box {
                    IconButton(
                        onClick = { moreExpanded = true },
                        enabled = !busy,
                        modifier = Modifier
                            .size(48.dp)
                            .minimumInteractiveComponentSize(),
                        contentDescription = stringResource(R.string.workspace_cd_more),
                    ) {
                        RuntimeIcon(RuntimeIconName.More, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(
                        expanded = moreExpanded,
                        onDismissRequest = { moreExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.workspace_open_terminal)) },
                            leadingIcon = {
                                RuntimeIcon(RuntimeIconName.Terminal, Modifier.size(17.dp))
                            },
                            onClick = {
                                moreExpanded = false
                                onOpenTerminal()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("运行工作流") },
                            leadingIcon = {
                                RuntimeIcon(RuntimeIconName.Hub, Modifier.size(17.dp))
                            },
                            onClick = {
                                moreExpanded = false
                                onOpenWorkflows()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.workspace_export_project)) },
                            leadingIcon = {
                                RuntimeIcon(RuntimeIconName.Compress, Modifier.size(17.dp))
                            },
                            onClick = {
                                moreExpanded = false
                                onExport()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.workspace_delete_project), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                RuntimeIcon(RuntimeIconName.Trash, Modifier.size(17.dp), MaterialTheme.colorScheme.error)
                            },
                            onClick = {
                                moreExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }

            if (isBuilding) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // 快捷操作栏：精简去噪，只保留最核心的直达动作
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                // 插件工程直达打包与导入，Android / Flutter 仍进入构建配置。
                if (project.projectType == ProjectType.ANDROID ||
                    project.projectType == ProjectType.FLUTTER || project.projectType == ProjectType.TIANYAN_PLUGIN
                ) {
                    FilledTonalButton(
                        onClick = if (isBuilding) onShowBuildLog else onRunProject,
                        enabled = !busy || isBuilding,
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (isBuilding) {
                                RuntimeCircularProgressIndicator(
                                    modifier = Modifier.size(13.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    stringResource(R.string.workspace_compiling),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            } else {
                                RuntimeIcon(RuntimeIconName.Play, Modifier.size(14.dp), tint = typeBadgeColor)
                                Text(
                                    stringResource(if (project.projectType == ProjectType.TIANYAN_PLUGIN)
                                        R.string.workspace_plugin_debug_import else R.string.workspace_run_on_device),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = typeBadgeColor,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                } else {
                    TextButton(
                        onClick = onOpenTerminal,
                        enabled = !busy,
                        modifier = Modifier.padding(end = 4.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            RuntimeIcon(RuntimeIconName.Terminal, Modifier.size(15.dp), MaterialTheme.colorScheme.primary)
                            Text(
                                stringResource(R.string.workspace_terminal),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }

            }
        }
    }
}

private fun Long.toReadableSize(): String = when {
    this < 1024 -> "$this B"
    this < 1024 * 1024 -> "%.1f KB".format(this / 1024.0)
    this < 1024 * 1024 * 1024 -> "%.1f MB".format(this / (1024.0 * 1024))
    else -> "%.1f GB".format(this / (1024.0 * 1024 * 1024))
}
