package top.tianyan.app.ui.settings
import top.tianyan.app.ui.components.previewOrNull
import top.tianyan.app.ui.components.previewStateOf
import org.koin.compose.viewmodel.koinViewModel
import top.tianyan.app.ui.settings.LocalizedText as Text
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.res.stringResource
import top.tianyan.app.feature.settings.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tianyan.app.runtime.privilege.PhantomProcessLimitState
import top.tianyan.app.runtime.privilege.PhantomProcessLimitStatus
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeButton as Button
import top.tianyan.app.ui.components.RuntimeCircularProgressIndicator as CircularProgressIndicator
import top.tianyan.app.ui.components.RuntimeIconButton as IconButton
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeOutlinedButton as OutlinedButton
import top.tianyan.app.ui.components.RuntimeTextButton as TextButton
/**
 * 二级子页 3：系统保活与开发者诊断
 */
@Composable
fun SystemDevSettingsScreen(
    onBack: () -> Unit,
    onOpenDeveloper: () -> Unit,
    onOpenAdbLogcat: () -> Unit = {},
    onOpenCustomIteration: () -> Unit = {},
    onOpenPermissionGuide: () -> Unit = {},
    viewModel: SettingsViewModel? = previewOrNull { koinViewModel() },
) {
    val developer by viewModel?.developerMode?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val qemuCompatibilityEnabled by viewModel?.qemuCompatibilityEnabled?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val qemuCompatibilityReady by viewModel?.qemuCompatibilityReady?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val qemuCompatibilityMessage by viewModel?.qemuCompatibilityMessage?.collectAsStateWithLifecycle() ?: previewStateOf(null)
    val phantomStatus by viewModel?.phantomProcessStatus?.collectAsStateWithLifecycle() ?: previewStateOf(null)
    val phantomBusy by viewModel?.phantomProcessBusy?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val phantomMessage by viewModel?.phantomProcessMessage?.collectAsStateWithLifecycle() ?: previewStateOf(null)
    val context = androidx.compose.ui.platform.LocalContext.current
    var showBatteryDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showPhantomProcessDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var batteryExempted by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    val isRestrictiveRom = remember { RomAutostartHelper.isKnownRestrictiveRom() }
    val romLabel = remember { RomAutostartHelper.romLabel() }

    LaunchedEffect(Unit) { viewModel?.refreshPhantomProcessLimit() }

    if (showBatteryDialog) {
        BatteryOptimizationDialog(
            exempted = batteryExempted,
            onRefresh = { batteryExempted = isIgnoringBatteryOptimizations(context) },
            onDismiss = { showBatteryDialog = false },
        )
    }
    if (showPhantomProcessDialog) {
        PhantomProcessLimitDialog(
            status = phantomStatus,
            busy = phantomBusy,
            message = phantomMessage,
            adbCommand = viewModel?.phantomProcessAdbCommand.orEmpty(),
            onRefresh = { viewModel?.refreshPhantomProcessLimit() },
            onRemove = { viewModel?.removePhantomProcessLimit() },
            onDismiss = {
                viewModel?.clearPhantomProcessMessage()
                showPhantomProcessDialog = false
            },
        )
    }

    PersonalizationScaffold(stringResource(R.string.personal_diagnostics_title), onBack) {
        item {
            Text(
                text = "进程保活与唤醒",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, bottom = 12.dp),
            )
            SettingsGroup {
                SettingsRow(
                    icon = RuntimeIconName.Shield,
                    title = "厂商后台防杀与权限向导",
                    subtitle = "自启动、电池无限制、多任务加锁等 OEM 专属配置指引",
                    value = "查看向导",
                    onClick = onOpenPermissionGuide,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                SettingsRow(
                    icon = RuntimeIconName.Battery,
                    title = "电池优化与后台保活",
                    subtitle = "豁免系统电池限制，防止 Agent 息屏被冻结",
                    value = if (batteryExempted) "已豁免" else "未豁免",
                    onClick = { showBatteryDialog = true },
                )
                if (isRestrictiveRom) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsRow(
                        icon = RuntimeIconName.Cpu,
                        title = "$romLabel 快捷自启动跳转",
                        subtitle = "一键直达厂商系统自启动管理设置项",
                        value = "前往开启",
                        onClick = {
                            runCatching { RomAutostartHelper.openAutostartSettings(context) }.onFailure {
                                Toast.makeText(context, "无法跳转厂商设置，请在系统设置的应用管理中手动配置", Toast.LENGTH_LONG).show()
                            }
                        },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                SettingsRow(
                    icon = RuntimeIconName.Speed,
                    title = "Android 12 子进程限制",
                    subtitle = "解除 Phantom Process 最多 32 个的后台限制",
                    value = when {
                        phantomBusy && phantomStatus == null -> "检测中"
                        phantomStatus?.state == PhantomProcessLimitState.REMOVED -> "已解除"
                        phantomStatus?.state == PhantomProcessLimitState.ACTIVE -> "未解除"
                        phantomStatus?.state == PhantomProcessLimitState.UNSUPPORTED -> "无需处理"
                        else -> "待检测"
                    },
                    onClick = {
                        showPhantomProcessDialog = true
                        viewModel?.refreshPhantomProcessLimit()
                    },
                )
            }
        }

        item {
            Text(
                text = "Android 系统调试与日志",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, bottom = 12.dp),
            )
            SettingsGroup {
                SettingsRow(
                    icon = RuntimeIconName.Terminal,
                    title = "无线 ADB 与日志抓取",
                    subtitle = "mDNS 自动配对发现、免配对自动重连与 Logcat 实时工作台",
                    value = "进入工作台",
                    onClick = onOpenAdbLogcat,
                )
            }
        }

        item {
            Text(
                text = "天衍自定义迭代与共建",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, bottom = 12.dp),
            )
            SettingsGroup {
                SettingsRow(
                    icon = RuntimeIconName.Code,
                    title = "自定义迭代（TianyanDev）",
                    subtitle = "在手机沙盒中调用 AI 开发天衍自身并云端构建 APK",
                    onClick = onOpenCustomIteration,
                )
            }
        }

        item {
            Text(
                text = "开发者调试与控制台",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, bottom = 12.dp),
            )
            SettingsGroup {
                DeveloperAccessSettings(developer, { viewModel?.setDeveloperMode(it) }, onOpenDeveloper, hostControls = viewModel != null)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ToggleRow(
                    icon = RuntimeIconName.Cpu,
                    title = "QEMU x86_64 兼容模式",
                    subtitle = if (qemuCompatibilityReady) {
                        "允许明确请求的会话使用 QEMU x86_64 user-mode；ARM64 会话不受影响"
                    } else {
                        "未检测到 QEMU x86_64 兼容环境，请先在插件中心安装 qemu-x86-64-compat 插件"
                    },
                    checked = qemuCompatibilityEnabled && qemuCompatibilityReady,
                    enabled = qemuCompatibilityReady,
                    change = { arg0 -> viewModel?.setQemuCompatibilityEnabled(arg0) },
                )
                Text(
                    text = qemuCompatibilityMessage ?: if (qemuCompatibilityEnabled) {
                        "已开启。兼容插件只提供 ARM64 QEMU user-mode 与最小 x86_64 RootFS。"
                    } else {
                        "默认关闭，不会下载或使用 x86_64 工具。开启后仅对明确选择兼容环境的第三方项目生效，不会改变 APK 的 arm64-v8a 默认 ABI。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (qemuCompatibilityReady) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }
        }
        }
}

@Composable
private fun BatteryOptimizationDialog(
    exempted: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 从系统授权页返回时刷新豁免状态
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { onRefresh() }

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RuntimeIcon(RuntimeIconName.Shield, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Text("电池优化与后台运行", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (exempted) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        },
                    ) {
                        Text(
                            if (exempted) "已豁免电池优化" else "未豁免 · 后台可能被冻结",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (exempted) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                Text(
                    "天衍在 Agent 执行期间会启动前台服务并持有 CPU 进程锁，但系统电池优化仍可能在息屏后" +
                        "冻结进程，表现为 Agent 推理或命令执行中途停住。建议开启以下两项：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }.onFailure {
                            Toast.makeText(context, "无法打开系统设置，请手动进入设置授予电池优化豁免", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("申请豁免电池优化")
                }
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }.onFailure {
                            Toast.makeText(context, "无法打开应用详情，请手动进入系统设置", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("打开应用详情（自启动/后台运行）")
                }
                Text(
                    "提示：小米/华为/OPPO 等厂商系统还需在应用详情中手动允许「自启动」与「后台运行」，" +
                        "否则厂商省电策略仍会终止进程。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun PhantomProcessLimitDialog(
    status: PhantomProcessLimitStatus?,
    busy: Boolean,
    message: String?,
    adbCommand: String,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { onRefresh() }

    val state = status?.state
    val statusText = when (state) {
        PhantomProcessLimitState.REMOVED -> "已解除限制"
        PhantomProcessLimitState.ACTIVE -> "限制仍生效"
        PhantomProcessLimitState.UNSUPPORTED -> "当前系统无需处理"
        PhantomProcessLimitState.UNAVAILABLE -> "暂时无法检测"
        null -> if (busy) "正在检测" else "尚未检测"
    }
    val healthy = state == PhantomProcessLimitState.REMOVED || state == PhantomProcessLimitState.UNSUPPORTED
    val statusContainer = if (healthy) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
    val statusContent = if (healthy) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RuntimeIcon(RuntimeIconName.Speed, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Android 12 子进程限制", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Surface(shape = RoundedCornerShape(6.dp), color = statusContainer) {
                        Text(
                            statusText,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = statusContent,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    IconButton(onClick = onRefresh, enabled = !busy) {
                        RuntimeIcon(RuntimeIconName.Refresh, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Text(
                    status?.details ?: "读取系统实际配置，确认幽灵进程限制是否仍在生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (state == PhantomProcessLimitState.REMOVED || state == PhantomProcessLimitState.ACTIVE) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "最大幽灵进程数：${status.maxPhantomProcesses ?: "系统默认（通常为 32）"}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text(
                                "幽灵进程监控：${when (status.monitoringEnabled) { true -> "开启"; false -> "关闭"; null -> "系统默认（开启）" }}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }

                Text(
                    "Android 12+ 会监控应用派生的子进程，超过系统上限后可能终止 PRoot、编译器或 Agent 任务。这里解除的是子进程限制，不是 Java/Kotlin 线程数。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Button(
                    onClick = onRemove,
                    enabled = !busy && state != PhantomProcessLimitState.REMOVED && state != PhantomProcessLimitState.UNSUPPORTED,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在处理")
                    } else {
                        Text("使用 Shizuku / Root 一键解除")
                    }
                }

                Text(
                    "也可以在已连接手机的电脑终端执行：",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            adbCommand,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clipboard?.setPrimaryClip(ClipData.newPlainText("Android 12 子进程限制命令", adbCommand))
                                Toast.makeText(context, "命令已复制", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            RuntimeIcon(RuntimeIconName.Copy, Modifier.size(15.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("复制命令")
                        }
                    }
                }

                if (!message.isNullOrBlank()) {
                    Text(
                        message,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state == PhantomProcessLimitState.REMOVED) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName) == true
