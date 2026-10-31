package top.tianyan.app.ui.chat

import top.tianyan.app.feature.chat.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.tianyan.app.core.model.BuiltinPluginBundles
import top.tianyan.app.core.model.RepairStrategy
import top.tianyan.app.core.model.ToolchainGroup
import top.tianyan.app.core.model.ToolchainProbeResult
import top.tianyan.app.core.model.ToolchainReport
import top.tianyan.app.core.model.ToolchainStatus
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeTextButton

/**
 * 🧰 沙箱工具链面板
 *
 * 纯 UI 层：文案全部来自 strings.xml（i18n），业务判定来自 [ToolchainReport]。
 */
@Composable
internal fun ToolchainPanel(
    viewModel: ToolchainViewModel,
    modifier: Modifier = Modifier,
) {
    val report by viewModel.report.collectAsState()
    val checkBusy by viewModel.checkBusy.collectAsState()
    val installBusy by viewModel.installBusy.collectAsState()
    val failed by viewModel.failed.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val installState by viewModel.installState.collectAsState()
    val debBased = viewModel.debBased
    val actionBusy = checkBusy || installBusy
    var showRepairConfirm by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------- 标题区 ----------
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RuntimeIcon(
                    name = RuntimeIconName.Hammer,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.toolchain_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.toolchain_subtitle),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val current = report

            // ---------- 状态区 ----------
            when {
                current == null && failed -> StatusBanner(
                    text = stringResource(R.string.toolchain_detect_failed),
                    color = Color(0xFFC62828),
                )

                current == null -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.toolchain_detecting), style = MaterialTheme.typography.bodySmall)
                }

                else -> SummaryRow(current)
            }

            // ---------- 一键补齐（仅 Debian 系发行版） ----------
            val repairables = current?.repairable.orEmpty()
            if (repairables.isNotEmpty() && debBased) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { showRepairConfirm = true }, enabled = !actionBusy) {
                        if (installBusy) {
                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.toolchain_repairing), fontSize = 12.sp)
                        } else {
                            RuntimeIcon(
                                name = RuntimeIconName.Download,
                                modifier = Modifier.size(13.dp),
                                tint = Color.White,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.toolchain_repair_all, repairables.size), fontSize = 12.sp)
                        }
                    }
                    if (!actionBusy) {
                        TextButton(onClick = { viewModel.refresh(force = true) }) {
                            Text(stringResource(R.string.toolchain_recheck), fontSize = 12.sp)
                        }
                    }
                }
            } else if (!actionBusy && (failed || (current != null && current.results.isNotEmpty()))) {
                TextButton(onClick = { viewModel.refresh(force = true) }) {
                    Text(stringResource(R.string.toolchain_recheck), fontSize = 12.sp)
                }
            }

            if (showRepairConfirm && current != null) {
                ToolchainRepairConfirmDialog(
                    report = current,
                    onConfirm = {
                        showRepairConfirm = false
                        viewModel.repairMissing()
                    },
                    onDismiss = { showRepairConfirm = false },
                )
            }

            // ---------- 无法自动补齐提示 ----------
            val manualOnly = current?.manualOnly.orEmpty()
            if (manualOnly.isNotEmpty()) {
                StatusBanner(
                    text = stringResource(
                        R.string.toolchain_manual_only,
                        manualOnly.joinToString(", ") { it.probe.displayName },
                    ),
                    color = Color(0xFFE65100),
                )
            }

            // ---------- root 依赖提示 ----------
            val rootTools = current?.rootRequired.orEmpty()
            if (rootTools.isNotEmpty()) {
                StatusBanner(
                    text = stringResource(
                        R.string.toolchain_root_banner,
                        rootTools.joinToString(", ") { it.probe.displayName },
                    ),
                    color = Color(0xFF6A1B9A),
                )
            }

            // ---------- 安装日志 ----------
            val allLogs = installState?.let { listOf(it) }.orEmpty() + logs
            if (allLogs.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        allLogs.takeLast(12).forEach {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // ---------- 分组列表 ----------
            if (current != null && current.results.isNotEmpty()) {
                ToolchainGroup.entries.forEach { group ->
                    val items = current.results.filter { it.probe.group == group }
                    if (items.isEmpty()) return@forEach

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = groupTitle(group),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        items.forEach { item -> ToolchainRow(item) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolchainRepairConfirmDialog(
    report: ToolchainReport,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val components = report.repairable
        .mapNotNull { it.probe.repair as? RepairStrategy.ByBundleComponents }
        .flatMap { it.componentIds }
        .distinct()
        .map { id ->
            BuiltinPluginBundles.bundles.flatMap { it.components }.firstOrNull { it.id == id }?.name ?: id
        }
    val packages = report.repairable
        .mapNotNull { it.probe.repair as? RepairStrategy.ByAptPackages }
        .flatMap { it.packages }
        .distinct()
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.toolchain_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (components.isNotEmpty()) {
                    Text(stringResource(R.string.toolchain_confirm_components, components.joinToString(", ")))
                    Text(stringResource(R.string.toolchain_confirm_large))
                }
                if (packages.isNotEmpty()) {
                    Text(stringResource(R.string.toolchain_confirm_packages, packages.joinToString(", ")))
                }
            }
        },
        confirmButton = {
            RuntimeTextButton(onClick = onConfirm) {
                Text(stringResource(R.string.toolchain_confirm_ok))
            }
        },
        dismissButton = {
            RuntimeTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.toolchain_confirm_cancel))
            }
        },
    )
}

@Composable
private fun groupTitle(group: ToolchainGroup): String = when (group) {
    ToolchainGroup.NATIVE_BUILD -> stringResource(R.string.toolchain_group_native)
    ToolchainGroup.REVERSE_ENGINEERING -> stringResource(R.string.toolchain_group_reverse)
    ToolchainGroup.DEBUG_INSPECT -> stringResource(R.string.toolchain_group_debug)
}

@Composable
private fun SummaryRow(report: ToolchainReport) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CountChip(stringResource(R.string.toolchain_chip_ready, report.readyCount), Color(0xFF2E7D32))
        if (report.missingCount > 0) CountChip(stringResource(R.string.toolchain_chip_missing, report.missingCount), Color(0xFFC62828))
        if (report.outdatedCount > 0) CountChip(stringResource(R.string.toolchain_chip_outdated, report.outdatedCount), Color(0xFFE65100))
        if (report.needsRootCount > 0) CountChip(stringResource(R.string.toolchain_chip_needs_root, report.needsRootCount), Color(0xFF6A1B9A))
        if (report.unknownCount > 0) CountChip(stringResource(R.string.toolchain_chip_unknown, report.unknownCount), Color(0xFF546E7A))
    }
}

@Composable
private fun CountChip(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(6.dp)) {
        Text(
            text = text,
            fontSize = 11.sp,
            color = color,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun StatusBanner(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.10f), shape = RoundedCornerShape(8.dp)) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ToolchainRow(item: ToolchainProbeResult) {
    val (dotColor, statusText) = statusPresentation(item)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(dotColor, CircleShape),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = item.probe.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = statusText,
                    fontSize = 10.sp,
                    color = dotColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = item.probe.purpose,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp,
            )
            // 落后项额外提示版本升级目标
            if (item.status == ToolchainStatus.OUTDATED && item.version != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "v${item.version} → ${item.probe.minVersion}",
                    style = MaterialTheme.typography.labelSmall,
                    color = dotColor.copy(alpha = 0.85f),
                )
            }
        }
    }
}

@Composable
private fun statusPresentation(item: ToolchainProbeResult): Pair<Color, String> = when (item.status) {
    ToolchainStatus.READY -> Color(0xFF2E7D32) to stringResource(R.string.toolchain_status_ready)
    ToolchainStatus.OUTDATED -> Color(0xFFE65100) to stringResource(R.string.toolchain_status_outdated)
    ToolchainStatus.MISSING -> Color(0xFFC62828) to stringResource(R.string.toolchain_status_missing)
    ToolchainStatus.NEEDS_ROOT -> Color(0xFF6A1B9A) to stringResource(R.string.toolchain_status_needs_root)
    ToolchainStatus.UNKNOWN -> Color(0xFF546E7A) to stringResource(R.string.toolchain_status_unknown)
}
