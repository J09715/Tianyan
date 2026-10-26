package top.tianyan.app.ui.settings
import top.tianyan.app.ui.components.previewOrNull
import top.tianyan.app.ui.components.previewStateOf
import org.koin.compose.viewmodel.koinViewModel
import top.tianyan.app.ui.components.RuntimeAlertDialog
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import top.tianyan.app.ui.components.RuntimeButton as Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import top.tianyan.app.ui.components.RuntimeIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import top.tianyan.app.ui.components.RuntimeOutlinedButton as OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import top.tianyan.app.ui.components.RuntimeSwitch as Switch
import androidx.compose.material3.SwitchDefaults
import top.tianyan.app.ui.settings.LocalizedText as Text
import top.tianyan.app.ui.components.RuntimeTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tianyan.app.core.model.StorageMountBinding
import top.tianyan.app.ui.components.NoticeBanner
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeTopBar

/**
 * 宿主与沙箱存储挂载管理 (Storage Mounts & Bindings)
 * 基于 PRoot -b 机制，实现 Android 宿主目录与 Linux 容器内的无缝双向访问
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun StorageMountSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel? = previewOrNull { koinViewModel() },
) {
    val downloadEnabled by viewModel?.mountDownloadEnabled?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val documentsEnabled by viewModel?.mountDocumentsEnabled?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val sharedStorageEnabled by viewModel?.mountSharedStorageEnabled?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val customBindings by viewModel?.customMountBindings?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())

    // SSH 远程主系统下 PRoot 挂载不生效：挂载只在本地容器启动时通过 -b 注入，远程执行环境没有对应机制。
    val activeServerId by (previewOrNull { koinViewModel<RemoteServerViewModel>() }?.activeId?.collectAsStateWithLifecycle() ?: previewStateOf<String?>(null))
    val localMountsOnly = activeServerId != null

    var showAddDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    // 待确认删除的挂载点 id（破坏性操作二次确认）
    var pendingDeleteBindingId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val pendingDeleteBinding = pendingDeleteBindingId?.let { id -> customBindings.firstOrNull { it.id == id } }

    val scrollBehavior = androidx.compose.material3.TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            RuntimeTopBar(
                title = "存储挂载与共享",
                onBack = onBack,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .then(top.tianyan.app.ui.components.runtimeScrollViewport(padding)),
            contentPadding = top.tianyan.app.ui.components.runtimeScrollContentPadding(padding, top.tianyan.app.ui.components.runtimeContentPadding(horizontal = 16.dp, top = 16.dp, bottom = 32.dp)),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                NoticeBanner(
                    text = "挂载仅作用于 Linux 沙箱内的进程（终端、智枢 Agent、构建任务与后台服务），不影响文件浏览器——文件浏览器始终直接访问宿主存储。挂载在会话启动时注入，修改后新建的终端 / 构建任务才会应用。完整读写还需在系统设置中授予「所有文件访问」权限。",
                )
            }

            if (localMountsOnly) {
                item {
                    NoticeBanner(
                        text = "当前主系统为 SSH 远程服务器，挂载仅对本地 PRoot 沙箱生效，不会同步到远程服务器。如需调整挂载，请先在「Linux 系统管理」切回本地系统。",
                        isError = true,
                    )
                }
            }

            // 1. 系统预置快捷挂载
            item {
                Text(
                    text = "系统预设快捷挂载",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
                RuntimeCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.extraLarge,
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Column {
                        MountToggleRow(
                            icon = RuntimeIconName.FolderDownload,
                            title = "下载目录 (Download)",
                            hostPath = "/storage/emulated/0/Download",
                            guestPath = "/sdcard/Download",
                            checked = downloadEnabled,
                            onCheckedChange = { arg0 -> viewModel?.setMountDownloadEnabled(arg0) },
                            enabled = !localMountsOnly,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        MountToggleRow(
                            icon = RuntimeIconName.Document,
                            title = "文档目录 (Documents)",
                            hostPath = "/storage/emulated/0/Documents",
                            guestPath = "/sdcard/Documents",
                            checked = documentsEnabled,
                            onCheckedChange = { arg0 -> viewModel?.setMountDocumentsEnabled(arg0) },
                            enabled = !localMountsOnly,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        MountToggleRow(
                            icon = RuntimeIconName.SdCard,
                            title = "完整共享存储 (/sdcard)",
                            hostPath = "/storage/emulated/0",
                            guestPath = "/sdcard",
                            checked = sharedStorageEnabled,
                            onCheckedChange = { arg0 -> viewModel?.setMountSharedStorageEnabled(arg0) },
                            enabled = !localMountsOnly,
                        )
                    }
                }
            }

            // 2. 自定义映射绑定
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "自定义映射绑定",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                    OutlinedButton(
                        onClick = { showAddDialog = true },
                        shape = RoundedCornerShape(50),
                        enabled = !localMountsOnly,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        RuntimeIcon(RuntimeIconName.Plus, Modifier.size(14.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("新增挂载", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (customBindings.isEmpty()) {
                item {
                    RuntimeCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.extraLarge,
                        borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        contentPadding = PaddingValues(24.dp),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "暂无自定义挂载点",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "点击上方“新增挂载”将 Android 目录映射进 Linux 沙箱（仅终端 / 智枢 / 构建可见）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                items(customBindings, key = { it.id }) { binding ->
                    RuntimeCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.extraLarge,
                        contentPadding = PaddingValues(20.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = binding.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "${binding.hostPath} ➔ ${binding.guestPath}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                    ),
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                // 历史版本可能存入越界路径：运行时会忽略该绑定，这里明确告知用户原因
                                val bindingError = remember(binding) { StorageMountBinding.validationError(binding) }
                                if (bindingError != null) {
                                    Text(
                                        text = "无效挂载：$bindingError（删除后重新添加）",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }

                            Switch(
                                checked = binding.enabled,
                                onCheckedChange = { enabled ->
                                    viewModel?.toggleCustomMountBinding(binding.id, enabled)
                                },
                                enabled = !localMountsOnly,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                ),
                            )

                            IconButton(
                                onClick = { pendingDeleteBindingId = binding.id },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .size(48.dp),
                                enabled = !localMountsOnly,
                                contentDescription = "删除挂载点 ${binding.name}",
                            ) {
                                RuntimeIcon(
                                    name = RuntimeIconName.Trash,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDeleteBinding?.let { binding ->
        RuntimeAlertDialog(
            onDismissRequest = { pendingDeleteBindingId = null },
            title = { Text("删除挂载点", fontWeight = FontWeight.Bold) },
            text = {
                Text("确定要删除挂载点「${binding.name}」（${binding.hostPath} ➔ ${binding.guestPath}）吗？删除后新建的终端 / 构建任务将不再映射该目录。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel?.removeCustomMountBinding(binding.id)
                        pendingDeleteBindingId = null
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteBindingId = null }) { Text("取消") }
            },
        )
    }

    if (showAddDialog) {
        AddMountDialog(
            existingGuestPaths = customBindings.map { it.guestPath }.toSet(),
            onDismiss = { showAddDialog = false },
            onConfirm = { name, host, guest ->
                viewModel?.addCustomMountBinding(name, host, guest)
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun MountToggleRow(
    icon: RuntimeIconName, title: String, hostPath: String, guestPath: String,
    checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true,
) = ToggleRow(icon, title, "$hostPath\n→ $guestPath", checked, onCheckedChange, enabled)
