package top.tianyan.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import top.tianyan.app.core.model.McpCatalog
import top.tianyan.app.core.model.McpUpdateStatus
import top.tianyan.app.core.model.PluginBundle
import top.tianyan.app.core.model.PluginComponent
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeTextButton as TextButton
import top.tianyan.app.ui.settings.LocalizedText as Text

/**
 * 装配弹窗的「跟着上游走」外壳。
 *
 * 单独一层而不是直接改 [BundleComponentSetupDialog] 的调用点，是为了不把 MCP 能力仓库的
 * 版本逻辑塞进通用装配流程：弹窗本身对「更新」一无所知，只多接几个可选参数；
 * 非 catalog 套件在这里把 `onCheckUpdates` 传成 null，更新相关的行与按钮就整块不渲染。
 */
@Composable
internal fun BundleSetupDialogWithUpdates(
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
    catalogViewModel: McpCatalogViewModel = koinViewModel(),
) {
    val isCatalog = bundle.id == McpCatalog.BUNDLE_ID
    val updateStatuses by catalogViewModel.updateStatuses.collectAsStateWithLifecycle()
    val checkingUpdates by catalogViewModel.checkingUpdates.collectAsStateWithLifecycle()
    val upgradingId by catalogViewModel.upgradingId.collectAsStateWithLifecycle()
    val updateNotice by catalogViewModel.updateNotice.collectAsStateWithLifecycle()

    // 打开仓库弹窗先自查一轮：这正是不内置的意义，用户不必记得去点「检查更新」。
    LaunchedEffect(bundle.id) {
        if (isCatalog && updateStatuses.isEmpty()) catalogViewModel.checkUpdates()
    }

    BundleComponentSetupDialog(
        bundle = bundle,
        installedComponentIds = installedComponentIds,
        selectedComponents = selectedComponents,
        isInstallingComponents = isInstallingComponents,
        componentInstallProgress = componentInstallProgress,
        onToggle = onToggle,
        onInstall = onInstall,
        onReinstall = onReinstall,
        onUninstall = onUninstall,
        uninstallBlockedReason = uninstallBlockedReason,
        onDismiss = {
            // 版本提示属于这次弹窗会话，关闭即清，避免下次打开时显示上一轮的陈旧结论。
            catalogViewModel.consumeUpdateNotice()
            onDismiss()
        },
        updateStatuses = if (isCatalog) updateStatuses else emptyMap(),
        checkingUpdates = checkingUpdates,
        upgradingComponentId = upgradingId,
        updateNotice = if (isCatalog) updateNotice else null,
        onCheckUpdates = if (isCatalog) catalogViewModel::checkUpdates else null,
        onUpgradeComponent = if (isCatalog) { component -> catalogViewModel.upgrade(component.id) } else null,
    )
}

/**
 * 分区标题；catalog 套件会在右侧多一个「检查更新」入口，其余套件退化为普通标题。
 */
@Composable
internal fun BundleSectionHeader(
    text: String,
    color: Color,
    checking: Boolean,
    enabled: Boolean,
    onCheck: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = color,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (onCheck != null) {
            TextButton(
                onClick = onCheck,
                enabled = enabled && !checking,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            ) {
                Text(
                    if (checking) "检查中..." else "检查更新",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/** 版本操作的一次性结论（升级成功 / 失败原因 / 上游不可达）。 */
@Composable
internal fun McpUpdateNoticeBanner(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/**
 * 单个已装配能力的版本行：结论文字 + （有更新时）一键升级。
 * [status] 为 null（未检查过）时整块不渲染，避免界面塞一行「未知」。
 */
@Composable
internal fun McpComponentUpdateRow(
    component: PluginComponent,
    status: McpUpdateStatus?,
    upgrading: Boolean,
    enabled: Boolean,
    onUpgrade: ((PluginComponent) -> Unit)?,
) {
    if (status == null || onUpgrade == null) return
    val accent = if (status.updateAvailable) warningStatusColor() else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RuntimeIcon(
            name = if (status.updateAvailable) RuntimeIconName.Update else RuntimeIconName.Refresh,
            modifier = Modifier.size(12.dp),
            tint = accent,
        )
        Text(
            text = status.summary,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        if (status.updateAvailable) {
            TextButton(
                onClick = { onUpgrade(component) },
                enabled = enabled && !upgrading,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            ) {
                Text(
                    if (upgrading) "升级中..." else "升级到 v${status.latestVersion}",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
