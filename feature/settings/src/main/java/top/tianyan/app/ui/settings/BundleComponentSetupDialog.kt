package top.tianyan.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.tianyan.app.core.model.McpUpdateStatus
import top.tianyan.app.core.model.PluginBundle
import top.tianyan.app.core.model.PluginComponent
import top.tianyan.app.feature.settings.R
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeButton as Button
import top.tianyan.app.ui.components.RuntimeCheckbox
import top.tianyan.app.ui.components.RuntimeCircularProgressIndicator
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeLinearProgressIndicator as LinearProgressIndicator
import top.tianyan.app.ui.components.RuntimeTextButton as TextButton
import top.tianyan.app.ui.settings.LocalizedText as Text

/**
 * 套件子组件装配弹窗。已装配的组件可以卸载，或清掉旧文件后重新安装。
 */
@Composable
internal fun BundleComponentSetupDialog(
    bundle: PluginBundle,
    installedComponentIds: Set<String>,
    selectedComponents: Set<String>,
    isInstallingComponents: Boolean,
    componentInstallProgress: String?,
    onToggle: (PluginComponent) -> Unit,
    onInstall: () -> Unit,
    onReinstall: (PluginComponent) -> Unit,
    onUninstall: (PluginComponent) -> Unit,
    uninstallBlockedReason: (PluginComponent) -> String?,
    onDismiss: () -> Unit,
    /** 版本相关参数：只有 MCP 能力仓库会传；其余套件保持默认值即可，更新相关的行整块不渲染。 */
    updateStatuses: Map<String, McpUpdateStatus> = emptyMap(),
    checkingUpdates: Boolean = false,
    upgradingComponentId: String? = null,
    updateNotice: String? = null,
    onCheckUpdates: (() -> Unit)? = null,
    onUpgradeComponent: ((PluginComponent) -> Unit)? = null,
) {
    var pendingUninstallId by rememberSaveable { mutableStateOf<String?>(null) }
    var blockedReason by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    val pendingUninstall = bundle.components.firstOrNull { it.id == pendingUninstallId }
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RuntimeIcon(
                    name = bundleIcon(bundle.iconName),
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text("装配 ${bundle.name}", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    bundle.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isInstallingComponents) {
                    InstallProgress(componentInstallProgress)
                }
                blockedReason?.let { reason ->
                    Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                val uninstalled = bundle.components.filter { it.id !in installedComponentIds }
                val installed = bundle.components.filter { it.id in installedComponentIds }
                if (uninstalled.isNotEmpty()) {
                    SectionLabel("待装配组件 (${uninstalled.size})：", MaterialTheme.colorScheme.primary)
                    uninstalled.forEach { component ->
                        UninstalledComponentRow(
                            component = component,
                            checked = component.isRequired || component.id in selectedComponents,
                            enabled = !isInstallingComponents,
                            onToggle = { onToggle(component) },
                        )
                    }
                }
                if (installed.isNotEmpty()) {
                    BundleSectionHeader(
                        text = "已装配就绪 (${installed.size})：",
                        color = successStatusColor(),
                        checking = checkingUpdates,
                        enabled = !isInstallingComponents,
                        onCheck = onCheckUpdates,
                    )
                    Text(
                        text = stringResource(R.string.settings_component_installed_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    updateNotice?.let { McpUpdateNoticeBanner(it) }
                    installed.forEach { component ->
                        InstalledComponentRow(
                            component = component,
                            enabled = !isInstallingComponents,
                            onReinstall = { onReinstall(component) },
                            onUninstall = {
                                val reason = uninstallBlockedReason(component)
                                if (reason == null) {
                                    blockedReason = null
                                    pendingUninstallId = component.id
                                } else {
                                    blockedReason = reason
                                }
                            },
                            updateStatus = updateStatuses[component.id],
                            upgrading = upgradingComponentId == component.id,
                            onUpgrade = onUpgradeComponent,
                        )
                    }
                }
            }
        },
        confirmButton = {
            val uninstalled = bundle.components.filter { it.id !in installedComponentIds }
            if (uninstalled.isEmpty()) {
                Button(onClick = onDismiss) { Text("完成") }
            } else {
                Button(
                    onClick = onInstall,
                    enabled = !isInstallingComponents && selectedComponents.isNotEmpty(),
                ) {
                    Text(if (selectedComponents.isEmpty()) "请勾选待装配组件" else "开始装配 (${selectedComponents.size})")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
    pendingUninstall?.let { component ->
        RuntimeAlertDialog(
            onDismissRequest = { pendingUninstallId = null },
            title = { Text(stringResource(R.string.settings_component_uninstall_title, component.name), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.settings_component_uninstall_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingUninstallId = null
                        onUninstall(component)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.settings_component_confirm_uninstall))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstallId = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun InstallProgress(progress: String?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuntimeCircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(progress ?: "正在执行批量原子装配流水线...", style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)))
        }
    }
}

@Composable
private fun SectionLabel(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        color = color,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun UninstalledComponentRow(
    component: PluginComponent,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val locked = component.isRequired
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled && !locked, onClick = onToggle),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            1.dp,
            if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
        color = if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RuntimeCheckbox(
                checked = checked,
                onCheckedChange = { if (!locked) onToggle() },
                enabled = enabled && !locked,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(component.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (locked) {
                        Badge("必选基座", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                    }
                }
                Text(component.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun InstalledComponentRow(
    component: PluginComponent,
    enabled: Boolean,
    onReinstall: () -> Unit,
    onUninstall: () -> Unit,
    updateStatus: McpUpdateStatus? = null,
    upgrading: Boolean = false,
    onUpgrade: ((PluginComponent) -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, successStatusColor().copy(alpha = 0.25f)),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier.size(20.dp).clip(CircleShape).background(successStatusColor().copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    RuntimeIcon(RuntimeIconName.Check, Modifier.size(12.dp), successStatusColor())
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(component.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(component.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            McpComponentUpdateRow(component, updateStatus, upgrading, enabled, onUpgrade)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onReinstall, enabled = enabled, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                    Text(stringResource(R.string.settings_component_reinstall), maxLines = 1, softWrap = false)
                }
                TextButton(onClick = onUninstall, enabled = enabled, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                    Text(
                        stringResource(R.string.settings_component_uninstall),
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, contentColor: androidx.compose.ui.graphics.Color, container: androidx.compose.ui.graphics.Color) {
    Surface(shape = RoundedCornerShape(4.dp), color = container) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = contentColor,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            maxLines = 1,
        )
    }
}

/** 开发套件 bundle 的图标名 → RuntimeIconName 映射（工具中心与安装弹窗共用）。 */
internal fun bundleIcon(iconName: String): RuntimeIconName = when (iconName) {
    "Android" -> RuntimeIconName.Android
    "Flutter" -> RuntimeIconName.Flutter
    "Globe" -> RuntimeIconName.Globe
    "Search" -> RuntimeIconName.Search
    "Extension" -> RuntimeIconName.Extension
    else -> RuntimeIconName.Code
}
