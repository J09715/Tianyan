package top.tianyan.app.ui.settings
import top.tianyan.app.ui.components.previewOrNull
import top.tianyan.app.ui.components.previewStateOf
import org.koin.compose.viewmodel.koinViewModel
import top.tianyan.app.ui.settings.LocalizedText as Text
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeButton as Button
import top.tianyan.app.ui.components.RuntimeCircularProgressIndicator as CircularProgressIndicator
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeLinearProgressIndicator as LinearProgressIndicator
import top.tianyan.app.ui.components.RuntimeOutlinedButton as OutlinedButton
import top.tianyan.app.ui.components.RuntimeTextButton as TextButton
import top.tianyan.app.ui.components.RuntimeSettingsScaffold
import top.tianyan.app.ui.components.MarkdownText
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import top.tianyan.app.core.model.AppUpdateInfo
import top.tianyan.app.core.model.UpdateCheckState
/**
 * 二级子页 4：关于、版本更新与官方社区
 */
@Composable
fun AboutCommunityScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel? = previewOrNull { koinViewModel() },
) {
    val autoCheckUpdates by viewModel?.autoCheckUpdates?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val updateCheckState by viewModel?.updateCheckState?.collectAsStateWithLifecycle() ?: previewStateOf(top.tianyan.app.core.model.UpdateCheckState.Idle)
    val downloadProgress by viewModel?.downloadProgress?.collectAsStateWithLifecycle() ?: previewStateOf(null)
    val isDownloading by viewModel?.isDownloading?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val context = androidx.compose.ui.platform.LocalContext.current
    var showAboutDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showReleaseNotesDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val currentReleaseNotes by viewModel?.currentReleaseNotes?.collectAsStateWithLifecycle() ?: previewStateOf(null)
    val isLoadingReleaseNotes by viewModel?.isLoadingReleaseNotes?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val currentVersion = rememberAppVersion()

    // 版本更新弹窗
    when (val state = updateCheckState) {
        is UpdateCheckState.Success -> {
            if (state.info.hasUpdate) {
                UpdateInfoDialog(
                    info = state.info,
                    downloadProgress = downloadProgress,
                    isDownloading = isDownloading,
                    onDownload = { state.info.apkDownloadUrl?.let { viewModel?.downloadAndInstall(it) } },
                    onOpenBrowser = { openBrowser(context, state.info.releaseUrl) },
                    onDismiss = { viewModel?.clearUpdateState() },
                )
            } else {
                RuntimeAlertDialog(
                    onDismissRequest = { viewModel?.clearUpdateState() },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RuntimeIcon(RuntimeIconName.Check, Modifier.size(22.dp), tint = successStatusColor())
                            Text("已是最新版本", fontWeight = FontWeight.Bold)
                        }
                    },
                    text = {
                        Text("当前天衍版本 v${state.info.currentVersion} 已是最新稳定版，无需更新。")
                    },
                    confirmButton = {
                        TextButton(onClick = { viewModel?.clearUpdateState() }) {
                            Text("确定")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            viewModel?.clearUpdateState()
                            viewModel?.loadCurrentReleaseNotes(currentVersion)
                            showReleaseNotesDialog = true
                        }) {
                            Text("本版日志")
                        }
                    },
                )
            }
        }
        is UpdateCheckState.Error -> {
            RuntimeAlertDialog(
                onDismissRequest = { viewModel?.clearUpdateState() },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RuntimeIcon(RuntimeIconName.Alert, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.error)
                        Text("检查更新失败")
                    }
                },
                text = { Text(state.message) },
                confirmButton = {
                    TextButton(onClick = { viewModel?.clearUpdateState() }) {
                        Text("知道了")
                    }
                },
            )
        }
        else -> Unit
    }

    if (showAboutDialog) {
        AboutAppDialog(
            onDismiss = { showAboutDialog = false },
            onOpenReleaseNotes = {
                showAboutDialog = false
                viewModel?.loadCurrentReleaseNotes(currentVersion)
                showReleaseNotesDialog = true
            },
        )
    }

    if (showReleaseNotesDialog) {
        VersionReleaseNotesDialog(
            version = currentVersion,
            releaseNotes = currentReleaseNotes,
            isLoading = isLoadingReleaseNotes,
            onDismiss = { showReleaseNotesDialog = false },
            onOpenHistory = { openBrowser(context, "https://github.com/J09715/Tianyan/releases") },
        )
    }

    RuntimeSettingsScaffold(
        title = "关于与社区", onBack = onBack,
    ) {
        item {
            Text(
                text = "应用版本与更新",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
            SettingsGroup {
                SettingsRow(
                    icon = RuntimeIconName.Update,
                    title = "检查新版本",
                    subtitle = "基于 GitHub Releases 自动检测与在线升级",
                    value = if (updateCheckState is UpdateCheckState.Checking) "检查中…" else "v$currentVersion",
                    onClick = {
                        // 检查进行中禁止重复触发
                        if (updateCheckState !is UpdateCheckState.Checking) {
                            viewModel?.checkForUpdates(currentVersion)
                        }
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                SettingsRow(
                    icon = RuntimeIconName.Document,
                    title = "版本更新日志",
                    subtitle = "查看当前版本 (v$currentVersion) 的更新说明与功能亮点",
                    onClick = {
                        viewModel?.loadCurrentReleaseNotes(currentVersion)
                        showReleaseNotesDialog = true
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ToggleRow(
                    icon = RuntimeIconName.Update,
                    title = "启动时自动检查更新",
                    subtitle = "应用启动时在后台静默检测新版本",
                    checked = autoCheckUpdates,
                    change = { arg0 -> viewModel?.setAutoCheckUpdates(arg0) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                SettingsRow(
                    icon = RuntimeIconName.Sparkles,
                    title = "重看功能引导",
                    subtitle = "重新展示插件中心、工作坊、多会话终端等首次使用引导",
                    onClick = {
                        viewModel?.replayFirstUseGuides()
                        Toast.makeText(
                            context,
                            "已重置功能引导，下次进入相应页面会重新展示",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
            }
        }

        item {
            Text(
                text = "官方社区与开源",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
            SettingsGroup {
                SettingsRow(
                    icon = RuntimeIconName.Github,
                    title = "GitHub 开源项目",
                    subtitle = "https://github.com/J09715/Tianyan · 欢迎 Star 支持",
                    onClick = { openBrowser(context, "https://github.com/J09715/Tianyan") },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                SettingsRow(
                    icon = RuntimeIconName.Info,
                    title = "关于天衍 · Tianyan",
                    subtitle = "Android 原生 Linux PRoot 沙箱与 AI 结对中枢",
                    onClick = { showAboutDialog = true },
                )
            }
        }
    }
}

@Composable
private fun AboutAppDialog(
    onDismiss: () -> Unit,
    onOpenReleaseNotes: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val appVersion = rememberAppVersion()
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RuntimeIcon(name = RuntimeIconName.Package, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Text("天衍 · Tianyan", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Android 原生 Linux PRoot 沙箱与 AI 结对编程中枢", style = MaterialTheme.typography.bodyMedium)
                Text("版本: v$appVersion (Material 3 Expressive)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("架构: aarch64 · chroot-less user-space virtualization", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("协议: Apache-2.0 License", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = onOpenReleaseNotes,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    RuntimeIcon(RuntimeIconName.Document, Modifier.size(16.dp), MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("本版更新日志")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("确定") }
        },
    )
}

@Composable
private fun UpdateInfoDialog(
    info: AppUpdateInfo,
    downloadProgress: Float?,
    isDownloading: Boolean,
    onDownload: () -> Unit,
    onOpenBrowser: () -> Unit,
    onDismiss: () -> Unit,
) {
    RuntimeAlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RuntimeIcon(RuntimeIconName.Refresh, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Text("发现新版本 v${info.latestVersion}", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = "当前版本: v${info.currentVersion}  ➔  最新版本: v${info.latestVersion}",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }

                if (info.releaseNotes.isNotBlank()) {
                    Text(
                        text = "更新日志：",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        MarkdownText(
                            markdown = info.releaseNotes,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }

                if (isDownloading) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("正在下载更新安装包...", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        if (downloadProgress != null) {
                            LinearProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp)),
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp)),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (info.apkDownloadUrl != null) {
                Button(
                    onClick = onDownload,
                    enabled = !isDownloading,
                ) {
                    Text(if (isDownloading) "正在下载…" else "应用内立即更新")
                }
            } else {
                Button(onClick = onOpenBrowser) {
                    Text("前往 GitHub 下载")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isDownloading) {
                Text("稍后再说")
            }
        },
    )
}

private fun joinQqGroup(context: Context, groupId: String = "964382207") {
    val uri = Uri.parse("mqqapi://card/show_pslcard?src_type=internal&version=1&uin=$groupId&card_type=group&source=qrcode")
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching {
        context.startActivity(intent)
    }.onFailure {
        // 剪贴板兜底
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("天衍官方交流群", groupId)
        clipboard?.setPrimaryClip(clip)
        Toast.makeText(context, "已复制 QQ 群号：$groupId，可打开 QQ 搜索加入", Toast.LENGTH_LONG).show()
    }
}

private fun openBrowser(context: Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("URL", url)
        clipboard?.setPrimaryClip(clip)
        Toast.makeText(context, "已复制链接：$url", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun VersionReleaseNotesDialog(
    version: String,
    releaseNotes: String?,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RuntimeIcon(RuntimeIconName.Document, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Text("天衍 v$version 更新说明", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (isLoading && releaseNotes.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(32.dp))
                    }
                } else if (!releaseNotes.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        MarkdownText(
                            markdown = releaseNotes,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                } else {
                    Text(androidx.compose.ui.res.stringResource(top.tianyan.app.feature.settings.R.string.settings_release_notes_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onOpenHistory) { Text("查看全部历史") }
        },
    )
}
