package top.tianyan.app.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings as AndroidSettings
import android.net.Uri
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.tianyan.app.core.model.RuntimeState
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.TianyanBrandBadge
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeTopBar
import top.tianyan.app.ui.components.SectionHeader
import top.tianyan.app.ui.components.liquidGlassContent
import top.tianyan.app.ui.settings.LocalizedText as Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import java.io.File

/**
 * 关于天衍（本地信息页）
 * 替代原「关于、更新与官方社区」页：版本/设备/运行时均为本地只读数据，无任何网络请求。
 */
@Composable
fun AboutTianyanScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val runtimeState by viewModel.runtimeState.collectAsStateWithLifecycle()
    val activeDistroId by viewModel.activeDistroId.collectAsStateWithLifecycle()
    val installedDistros by viewModel.installedDistros.collectAsStateWithLifecycle()
    val sandboxProxy by viewModel.sandboxHttpProxy.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val appVersion = rememberAppVersion()
    val deviceInfo = rememberDeviceSnapshot(context)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .liquidGlassContent()
            .padding(top = 0.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            RuntimeTopBar("关于天衍", onBack = onBack)
        }
        item {
            SectionHeader("应用信息")
            RuntimeCard(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TianyanBrandBadge(size = 52.dp)
                    Column {
                        Text("天衍 · Tianyan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("黑白观象，知行相衍", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(Modifier.padding(vertical = 4.dp)) {
                    SettingsRowLocal(
                        icon = RuntimeIconName.Info,
                        title = "天衍 · Tianyan",
                        value = "Android 原生 Linux PRoot 沙箱与 AI 结对中枢",
                    )
                    SettingsRowLocal(
                        icon = RuntimeIconName.Package,
                        title = "版本",
                        value = "v$appVersion",
                    )
                    SettingsRowLocal(
                        icon = RuntimeIconName.Package,
                        title = "包名",
                        value = context.packageName,
                    )
                    SettingsRowLocal(
                        icon = RuntimeIconName.Cpu,
                        title = "运行时",
                        value = when (val s = runtimeState) {
                            is RuntimeState.Ready -> "PRoot 就绪 · ${activeDistroId ?: "未选定"}"
                            is RuntimeState.Initializing -> s.step
                            is RuntimeState.Error -> "异常 · ${s.throwable.message}"
                            else -> "未启动"
                        },
                    )
                    SettingsRowLocal(
                        icon = RuntimeIconName.Server,
                        title = "已安装发行版",
                        value = if (installedDistros.isEmpty()) "无" else "${installedDistros.size} 套 · 当前 ${activeDistroId ?: "未选定"}",
                    )
                }
            }
        }
        item {
            SectionHeader("设备信息")
            RuntimeCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    SettingsRowLocal(icon = RuntimeIconName.Cpu, title = "设备型号", value = deviceInfo.deviceModel)
                    SettingsRowLocal(icon = RuntimeIconName.Info, title = "Android 版本", value = "API ${Build.VERSION.SDK_INT}")
                    SettingsRowLocal(icon = RuntimeIconName.Speed, title = "CPU 核心数", value = deviceInfo.cpuCores)
                    SettingsRowLocal(icon = RuntimeIconName.Speed, title = "运行内存", value = "${deviceInfo.totalRamGib} GiB（可用 ${deviceInfo.freeRamGib} GiB）")
                    SettingsRowLocal(icon = RuntimeIconName.FolderOpen, title = "内部存储", value = "${deviceInfo.totalStorageGib} GiB（可用 ${deviceInfo.freeStorageGib} GiB）")
                }
            }
        }
        item {
            SectionHeader("沙箱网络")
            RuntimeCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(
                        "沙箱内置 HTTP 代理：让沙箱内的 git / curl / apt 走指定代理。留空表示不启用，回落到系统全局网络。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    SettingsRowLocal(
                        icon = RuntimeIconName.Network,
                        title = "沙箱 HTTP 代理",
                        value = sandboxProxy.ifBlank { "未设置" },
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "当前值：${sandboxProxy.ifBlank { "（空）" }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            SectionHeader("引导与权限")
            RuntimeCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    SettingsRowLocal(
                        icon = RuntimeIconName.Play,
                        title = "重看功能引导",
                        value = "清除首次使用标记",
                        onClick = { viewModel.replayFirstUseGuides() },
                    )
                    SettingsRowLocal(
                        icon = RuntimeIconName.Admin,
                        title = "系统设置与电池优化",
                        value = "跳转到系统设置",
                        onClick = {
                            val intent = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:" + context.packageName)
                            }
                            context.startActivity(intent)
                        },
                    )
                }
            }
        }
    }
}

/** 本地版 SettingsRow：与 SettingsScreen.SettingsRow 同构，但无网络/社区依赖。 */
@Composable
private fun SettingsRowLocal(
    icon: RuntimeIconName,
    title: String,
    value: String,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RuntimeIcon(icon, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 设备快照：仅读 Build / Runtime / StatFs，无权限、无网络。 */
data class DeviceSnapshot(
    val deviceModel: String,
    val cpuCores: String,
    val totalRamGib: Int,
    val freeRamGib: Int,
    val totalStorageGib: Int,
    val freeStorageGib: Int,
)

@Composable
private fun rememberDeviceSnapshot(context: Context): DeviceSnapshot {
    return androidx.compose.runtime.remember {
        try {
            val model = Build.MANUFACTURER + " " + Build.MODEL
            val cores = (Runtime.getRuntime().availableProcessors() ?: 0).toString()
            val totalRam = (Runtime.getRuntime().totalMemory() / 1024 / 1024 / 1024).toInt()
            val freeRam = (Runtime.getRuntime().freeMemory() / 1024 / 1024 / 1024).toInt()
            val statfs = runCatching { StatFs(Environment.getDataDirectory().absolutePath) }.getOrNull()
            val totalStore = (statfs?.totalBytes?.div(1024L * 1024L * 1024L)?.toInt() ?: 0)
            val freeStore = (statfs?.availableBytes?.div(1024L * 1024L * 1024L)?.toInt() ?: 0)
            DeviceSnapshot(model, cores, totalRam, freeRam, totalStore, freeStore)
        } catch (_: Exception) {
            DeviceSnapshot("未知", "0", 0, 0, 0, 0)
        }
    }
}
