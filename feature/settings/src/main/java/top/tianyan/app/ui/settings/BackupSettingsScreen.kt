package top.tianyan.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeButton as Button
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeOutlinedButton as OutlinedButton
import top.tianyan.app.ui.components.RuntimeTopBar
import top.tianyan.app.ui.components.liquidGlassContent
import top.tianyan.app.ui.settings.LocalizedText as Text

/**
 * 数据备份与恢复。
 *
 * 导出走 SAF CreateDocument（用户自选目录与文件名），恢复走 OpenDocument 选 ZIP。
 * 恢复只解包暂存、真正落盘在下次启动——按钮旁必须写清这一点，
 * 否则用户会以为选完 ZIP 数据就已经换掉了。
 */
@Composable
fun BackupSettingsScreen(
    onBack: () -> Unit,
    viewModel: BackupSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmRestore by rememberSaveable { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let(viewModel::export) }

    val restorePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::restore) }

    if (confirmRestore) {
        RuntimeAlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("用备份覆盖当前数据？") },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("恢复会用备份里的数据库、偏好与附件整体替换本机当前数据，本机现有改动将无法找回。")
                    Text(
                        "已就绪的备份将在重启天衍后自动落盘；尚未选择备份包时本操作不产生任何改动。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.pendingSections.isNotEmpty()) {
                        Text(
                            "待恢复分区：${state.pendingSections.joinToString("、") { viewModel.sectionLabel(it) }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmRestore = false
                        restorePicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    },
                ) { Text("选择备份包") }
            },
            dismissButton = { OutlinedButton(onClick = { confirmRestore = false }) { Text("取消") } },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { RuntimeTopBar("数据备份与恢复", onBack) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .liquidGlassContent()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsSection("备份范围") {
                BackupSettingsRow(
                    icon = RuntimeIconName.Storage,
                    title = "应用数据库",
                    subtitle = "会话、模型档案、项目索引、红队事实等全部数据表",
                )
                Divider()
                BackupSettingsRow(
                    icon = RuntimeIconName.Tune,
                    title = "偏好设置",
                    subtitle = "主题、端点、工具参数与已加密的密钥密文",
                )
                Divider()
                BackupSettingsRow(
                    icon = RuntimeIconName.Document,
                    title = "会话大载荷",
                    subtitle = "会话里超出 SQLite 行上限的外置载荷文件",
                )
                Divider()
                BackupSettingsRow(
                    icon = RuntimeIconName.Folder,
                    title = "附件与工作区",
                    subtitle = "附件目录与工作区（技能包、上传文件、用户项目）",
                )
            }

            SettingsSection("本地备份副本") {
                BackupSettingsRow(
                    icon = RuntimeIconName.Save,
                    title = state.localBackupName ?: "尚无备份",
                    subtitle = "${viewModel.formatTime(state.localBackupTime)} · " +
                        "${viewModel.formatSize(state.localBackupSize)} · 仅保留最近 5 份",
                )
            }

            state.lastExport?.let { exported ->
                SettingsSection("最近一次备份明细") {
                    BackupSettingsRow(
                        icon = RuntimeIconName.Document,
                        title = exported.archiveName,
                        subtitle = exported.sections.joinToString("\n") {
                            "${viewModel.sectionLabel(it.section)} · ${viewModel.formatSize(it.bytes)} · ${it.entries} 个文件"
                        },
                    )
                }
            }

            if (state.pendingSections.isNotEmpty()) {
                SettingsSection("待恢复的备份") {
                    BackupSettingsRow(
                        icon = RuntimeIconName.Refresh,
                        title = "重启天衍后自动生效",
                        subtitle = "${state.pendingSections.joinToString("、") { viewModel.sectionLabel(it) }} · " +
                            "解包于 ${viewModel.formatTime(state.pendingAt)}",
                        value = "已就绪",
                    )
                    Divider()
                    BackupSettingsRow(
                        icon = RuntimeIconName.Trash,
                        title = "取消本次恢复",
                        subtitle = "丢弃已解包的暂存数据，保留当前数据",
                        onClick = viewModel::discardPending,
                    )
                }
            }

            SettingsSection("操作") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = { exportLauncher.launch(viewModel.suggestedArchiveName()) },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (state.busy) "处理中…" else "导出备份") }
                    OutlinedButton(
                        onClick = { confirmRestore = true },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("从备份恢复") }
                }
            }

            state.message?.let { message ->
                SettingsSection {
                    BackupSettingsRow(
                        icon = RuntimeIconName.Info,
                        title = message,
                        subtitle = "恢复类操作需重启天衍后才会替换数据；点击本行清除提示",
                        onClick = viewModel::clearMessage,
                    )
                }
            }

            Spacer(Modifier.height(96.dp))
        }
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        modifier = Modifier.padding(horizontal = 14.dp),
    )
}

/** 小节标题 + RuntimeCard 容器，避免每处手写 padding/间距。 */
@Composable
private fun SettingsSection(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        title?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        RuntimeCard(
            modifier = Modifier.fillMaxWidth(),
            borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
            contentPadding = PaddingValues(0.dp),
        ) { content() }
    }
}

/** 设置行：图标 + 标题/副标题，可选整行点击与右侧状态标签。 */
@Composable
private fun BackupSettingsRow(
    icon: RuntimeIconName,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    value: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            RuntimeIcon(icon, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (value != null) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(5.dp),
            ) {
                Text(
                    value,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        } else if (onClick != null) {
            RuntimeIcon(
                name = RuntimeIconName.ChevronRight,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}
