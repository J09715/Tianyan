package top.wanxiang.app.ui.settings

import top.wanxiang.app.ui.components.RuntimeAlertDialog

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import top.wanxiang.app.ui.components.RuntimeButton as Button
import androidx.compose.material3.ButtonDefaults
import top.wanxiang.app.ui.components.RuntimeCircularProgressIndicator as CircularProgressIndicator
import top.wanxiang.app.ui.components.RuntimeLinearProgressIndicator as LinearProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.HorizontalDivider
import top.wanxiang.app.ui.components.RuntimeIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import top.wanxiang.app.ui.components.RuntimeOutlinedButton as OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import top.wanxiang.app.ui.components.RuntimeSwitch as Switch
import androidx.compose.material3.SwitchDefaults
import top.wanxiang.app.ui.settings.LocalizedText as Text
import top.wanxiang.app.ui.components.RuntimeTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import top.wanxiang.app.core.database.AiModelEntity
import top.wanxiang.app.core.model.ExecutionMode
import top.wanxiang.app.core.tools.AgentProviderDefinition
import top.wanxiang.app.core.tools.ProviderEndpointPolicy
import top.wanxiang.app.runtime.privilege.PhantomProcessLimitState
import top.wanxiang.app.runtime.privilege.PhantomProcessLimitStatus
import top.wanxiang.app.ui.components.IconTile
import top.wanxiang.app.ui.components.MainDestination
import top.wanxiang.app.ui.components.RuntimeBottomBar
import top.wanxiang.app.ui.components.liquidGlassContent
import top.wanxiang.app.ui.components.RuntimeCard
import top.wanxiang.app.ui.components.RuntimeSwitch
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconName
import top.wanxiang.app.ui.components.RuntimeTopBar
import top.wanxiang.app.ui.components.SectionHeader
import top.wanxiang.app.ui.theme.LocalLiquidGlassBackdrop

/**
 * 万象 · 乾坤配置 (WanXiang Settings & Models)
 */
@Composable
fun SettingsScreen(
    onNavigate: (MainDestination) -> Unit,
    onOpenAgentEco: () -> Unit,
    onOpenLinuxEnv: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenSystemDev: () -> Unit,
    onOpenAboutWanxiang: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val models by viewModel.models.collectAsStateWithLifecycle()
    val developer by viewModel.developerMode.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val skills by viewModel.allSkills.collectAsStateWithLifecycle()
    val effectiveExecutionMode by viewModel.effectiveExecutionMode.collectAsStateWithLifecycle()
    val installedDistros by viewModel.installedDistros.collectAsStateWithLifecycle()
    val activeDistroId by viewModel.activeDistroId.collectAsStateWithLifecycle()
    val terminalFontSize by viewModel.terminalFontSize.collectAsStateWithLifecycle()

    val themeLabel = when (themeMode) {
        "light" -> "浅色"
        "dark" -> "曜石"
        else -> "跟随系统"
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val appVersionName = rememberAppVersion()

    val glassBackdrop = LocalLiquidGlassBackdrop.current
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            RuntimeTopBar(
                title = "万象 · 乾坤",
                statusText = "系统设置与控制中枢",
            )
        },
        bottomBar = {
            if (glassBackdrop == null) {
                RuntimeBottomBar(MainDestination.Settings, onNavigate)
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .liquidGlassContent()
                .padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = "系统与配置分类",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                )
            }

            // 1. 智能体与 AI 模型生态
            item {
                SettingsCategoryCard(
                    icon = RuntimeIconName.Brain,
                    iconTint = Color(0xFF6366F1),
                    iconBg = Color(0xFF6366F1).copy(alpha = 0.12f),
                    title = "智能体与 AI 模型",
                    subtitle = "模型档案 · 插件工具中心 · 技能与 MCP 生态",
                    badge = if (models.isEmpty()) "未配置模型" else "${models.size} 个模型 · ${skills.count { it.isEnabled }} 技能",
                    onClick = onOpenAgentEco,
                )
            }

            // 2. Linux 容器沙箱与存储
            item {
                SettingsCategoryCard(
                    icon = RuntimeIconName.Server,
                    iconTint = Color(0xFF10B981),
                    iconBg = Color(0xFF10B981).copy(alpha = 0.12f),
                    title = "Linux 容器与存储",
                    subtitle = "多发行版管理 · 宿主存储映射 · 运行特权模式",
                    badge = "${installedDistros.size} 套系统 · ${effectiveExecutionMode.shortLabel}",
                    onClick = onOpenLinuxEnv,
                )
            }

            // 3. 外观、字号与终端定制
            item {
                SettingsCategoryCard(
                    icon = RuntimeIconName.Palette,
                    iconTint = Color(0xFF8B5CF6),
                    iconBg = Color(0xFF8B5CF6).copy(alpha = 0.12f),
                    title = "外观、字号与终端定制",
                    subtitle = "深浅色主题 · 应用字号缩放 · 终端配色与字体",
                    badge = "$themeLabel · ${terminalFontSize}sp",
                    onClick = onOpenAppearance,
                )
            }

            // 4. 系统保活与开发者诊断
            item {
                SettingsCategoryCard(
                    icon = RuntimeIconName.Admin,
                    iconTint = Color(0xFFF59E0B),
                    iconBg = Color(0xFFF59E0B).copy(alpha = 0.12f),
                    title = "系统保活与开发者诊断",
                    subtitle = "后台电池优化白名单 · 调试监控 · PRoot 控制台",
                    badge = if (developer) "诊断模式已开启" else "运行平稳",
                    onClick = onOpenSystemDev,
                )
            }

            // 5. 关于万象
            item {
                SettingsCategoryCard(
                    icon = RuntimeIconName.Info,
                    iconTint = Color(0xFF3B82F6),
                    iconBg = Color(0xFF3B82F6).copy(alpha = 0.12f),
                    title = "关于万象",
                    subtitle = "应用版本 · 设备与运行时状态 · 沙箱网络代理",
                    badge = if (appVersionName == "unknown") "版本号未知 · 稳定版" else "v$appVersionName 稳定版",
                    onClick = onOpenAboutWanxiang,
                )
            }
        }
    }
}

/**
 * 现代高质感大类导航卡片（紧凑精致）
 */
@Composable
private fun SettingsCategoryCard(
    icon: RuntimeIconName,
    iconTint: Color,
    iconBg: Color,
    title: String,
    subtitle: String,
    badge: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RuntimeCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconBg)
                    .border(1.dp, iconTint.copy(alpha = 0.22f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                RuntimeIcon(icon, Modifier.size(18.dp), tint = iconTint)
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(5.dp),
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Medium),
                        color = iconTint,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.5.dp),
                    )
                }
            }

            RuntimeIcon(
                name = RuntimeIconName.ChevronRight,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}

/**
 * 二级子页 1：智能体与 AI 模型生态
 */
@Composable
fun AgentEcoSettingsScreen(
    onBack: () -> Unit,
    onOpenModelProfiles: () -> Unit,
    onOpenLocalLlm: () -> Unit,
    onOpenToolCenter: () -> Unit,
    onOpenAgentSettings: () -> Unit,
    onOpenSubagentSettings: () -> Unit,
    onOpenSkillSettings: () -> Unit,
    onOpenMcpSettings: () -> Unit,
    onOpenQuickPhrases: () -> Unit,
    onOpenStats: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val models by viewModel.models.collectAsStateWithLifecycle()
    val skills by viewModel.allSkills.collectAsStateWithLifecycle()
    val phrases by viewModel.quickPhrases.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { RuntimeTopBar("智能体与模型", onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    text = "模型档案与提供商",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
                SettingsGroup {
                    SettingsRow(
                        icon = RuntimeIconName.Model,
                        title = "模型档案管理",
                        subtitle = "配置 OpenAI / DeepSeek / Claude / 本地大模型密钥与端点",
                        value = if (models.isEmpty()) "未配置" else "${models.size} 个模型",
                        onClick = onOpenModelProfiles,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsRow(
                        icon = RuntimeIconName.Cpu,
                        title = "本地 LLM",
                        subtitle = "导入或下载 GGUF，在 ARM64 设备端通过 llama.cpp 离线推理",
                        onClick = onOpenLocalLlm,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsRow(
                        icon = RuntimeIconName.Chat,
                        title = "快捷短语与常用指令",
                        subtitle = "自定义智枢空白页快捷开始卡片与高频提示词模板",
                        value = "${phrases.count { it.isEnabled }} 条已启用",
                        onClick = onOpenQuickPhrases,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsRow(
                        icon = RuntimeIconName.Speed,
                        title = "数据统计与用量分析",
                        subtitle = "Token 消耗、活跃度热力图、模型与话题排行",
                        onClick = onOpenStats,
                    )
                }
            }

            item {
                Text(
                    text = "工具与插件生态",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
                SettingsGroup {
                    SettingsRow(
                        icon = RuntimeIconName.Wrench,
                        title = "插件与工具生态中心",
                        subtitle = "一键安装 Claude Code、OpenClaw 等 AI CLI 与开发环境",
                        onClick = onOpenToolCenter,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsRow(icon = RuntimeIconName.Bot, title = "Agent 执行与上下文", subtitle = "思考流、上下文压缩、工具调用限制与系统提示词", onClick = onOpenAgentSettings)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsRow(icon = RuntimeIconName.Bot, title = "子智能体角色", subtitle = "自动委派策略与可用的子智能体角色", onClick = onOpenSubagentSettings)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsRow(icon = RuntimeIconName.Sparkles, title = "Skills 与插件", subtitle = "管理 Skill 提示词、脚本包与运行时插件", value = "${skills.count { it.isEnabled }} 个技能", onClick = onOpenSkillSettings)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsRow(
                        icon = RuntimeIconName.Network,
                        title = "MCP 协议生态与服务",
                        subtitle = "管理 SQLite、Git、Fetch 等 Model Context Protocol 协议服务",
                        onClick = onOpenMcpSettings,
                    )
                }
            }
        }
    }
}

/**
 * 二级子页 2：Linux 容器沙箱与存储
 */
@Composable
fun LinuxEnvironmentSettingsScreen(
    onBack: () -> Unit,
    onOpenDistroManagement: () -> Unit,
    onOpenStorageMounts: () -> Unit,
    onOpenStorageUsage: () -> Unit,
    onOpenAppManagement: () -> Unit,
    onOpenEnvironmentVariables: () -> Unit,
    onOpenSshSettings: () -> Unit,
    onOpenFtpSettings: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val executionMode by viewModel.executionMode.collectAsStateWithLifecycle()
    val effectiveExecutionMode by viewModel.effectiveExecutionMode.collectAsStateWithLifecycle()
    val privilegeState by viewModel.privilegeState.collectAsStateWithLifecycle()
    val switchingMode by viewModel.switchingMode.collectAsStateWithLifecycle()
    val installedDistros by viewModel.installedDistros.collectAsStateWithLifecycle()
    val webChatStatus by viewModel.webChatStatus.collectAsStateWithLifecycle()

    var showExecutionModeDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showWebChatDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var privilegeResultMessage by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    // Web 开关切换进行中指示，防止重复点击
    var webChatToggling by remember { mutableStateOf(false) }
    LaunchedEffect(webChatStatus.isRunning) { webChatToggling = false }

    if (showExecutionModeDialog) {
        ExecutionModeDialog(
            currentMode = executionMode,
            switching = switchingMode,
            onSelectMode = { mode ->
                showExecutionModeDialog = false
                viewModel.switchExecutionMode(mode) { success, msg ->
                    privilegeResultMessage = if (success) null else msg
                }
            },
            onDismiss = { showExecutionModeDialog = false },
        )
    }

    privilegeResultMessage?.let { errorMsg ->
        RuntimeAlertDialog(
            onDismissRequest = { privilegeResultMessage = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuntimeIcon(RuntimeIconName.Alert, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.error)
                    Text("运行模式授权未通过")
                }
            },
            text = { Text(errorMsg, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { privilegeResultMessage = null }) {
                    Text("知道了")
                }
            },
        )
    }

    if (showWebChatDialog) {
        WebChatBridgeDialog(
            status = webChatStatus,
            toggling = webChatToggling,
            onToggle = { enabled ->
                webChatToggling = true
                viewModel.toggleWebChatServer(enabled)
            },
            onDismiss = { showWebChatDialog = false },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { RuntimeTopBar("Linux 容器与存储", onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    text = "容器系统与沙箱管理",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
                SettingsGroup {
                    SettingsRow(
                        icon = RuntimeIconName.Server,
                        title = "Linux 发行版管理",
                        subtitle = "多沙箱并存 · 镜像拉取 · 一键切换主系统",
                        value = "${installedDistros.size} 套系统",
                        onClick = onOpenDistroManagement,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsRow(
                        icon = RuntimeIconName.Storage,
                        title = "存储管理",
                        subtitle = "按 Linux、插件、项目与 Skills 分析占用",
                        onClick = onOpenStorageUsage,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsRow(
                        icon = RuntimeIconName.SdCard,
                        title = "存储挂载与共享",
                        subtitle = "PRoot 宿主存储映射 (-b /sdcard)",
                        onClick = onOpenStorageMounts,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsRow(
                        icon = RuntimeIconName.Key,
                        title = "环境变量",
                        subtitle = "为终端、Agent 和工具注入用户变量",
                        onClick = onOpenEnvironmentVariables,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsRow(
                        icon = RuntimeIconName.Network,
                        title = "SSH 远程访问",
                        subtitle = "公钥认证 · 端口与局域网监听 · 随运行时启动",
                        onClick = onOpenSshSettings,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsRow(
                        icon = RuntimeIconName.FolderOpen,
                        title = "FTP 远程文件访问",
                        subtitle = "挂载 Linux 根目录 (/) · FileZilla / 资源管理器直连",
                        onClick = onOpenFtpSettings,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsRow(
                        icon = RuntimeIconName.Globe,
                        title = "万象智枢 Web 协作台",
                        subtitle = if (webChatStatus.isRunning) {
                            "运行中 · ${webChatStatus.accessUrl} (PIN: ${webChatStatus.pinCode})"
                        } else {
                            "在同一 Wi-Fi 下使用电脑浏览器访问万象 Agent 与工作区"
                        },
                        value = if (webChatStatus.isRunning) "已开启" else "未开启",
                        onClick = { showWebChatDialog = true },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsRow(
                        icon = RuntimeIconName.Package,
                        title = "应用管理",
                        subtitle = "同步系统/用户应用，查看禁用、冻结与后台联网限制状态",
                        onClick = onOpenAppManagement,
                    )
                }
            }

            item {
                Text(
                    text = "系统底层特权",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
                SettingsGroup {
                    SettingsRow(
                        icon = RuntimeIconName.Key,
                        title = "系统运行特权模式",
                        subtitle = if (executionMode != effectiveExecutionMode) {
                            "首选 ${executionMode.shortLabel} 暂不可用：${privilegeState.reason}"
                        } else {
                            "PRoot 用户态沙箱 · Shizuku · Root"
                        },
                        value = if (executionMode == effectiveExecutionMode) {
                            effectiveExecutionMode.shortLabel
                        } else {
                            "${effectiveExecutionMode.shortLabel}（已降级）"
                        },
                        onClick = { showExecutionModeDialog = true },
                    )
                }
            }
        }
    }
}

@Composable
fun EnvironmentVariableSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val entries by viewModel.environmentVariables.collectAsStateWithLifecycle()
    val values by viewModel.environmentValues.collectAsStateWithLifecycle()
    val effectiveEntries by viewModel.effectiveEnvironment.collectAsStateWithLifecycle()
    val privacyMode by viewModel.environmentPrivacyMode.collectAsStateWithLifecycle()
    val loading by viewModel.environmentLoading.collectAsStateWithLifecycle()
    val error by viewModel.environmentError.collectAsStateWithLifecycle()
    val activeDistroId by viewModel.activeDistroId.collectAsStateWithLifecycle()
    val runtimeState by viewModel.runtimeState.collectAsStateWithLifecycle()
    // 对象状态仅保存可 Bundle 化的字段，旋转后按 key/entry 恢复
    var showEditor by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var editingKey by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var editorInitialKey by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var editorInitialValue by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var deleteKey by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val editing = editingKey?.let { key -> entries.firstOrNull { it.id == key } }
    val showDelete = deleteKey?.let { key -> entries.firstOrNull { it.id == key } }

    fun openEnvironmentEditor(
        entry: top.wanxiang.app.core.model.EnvironmentVariable?,
        key: String,
        value: String,
    ) {
        viewModel.clearEnvironmentError()
        editingKey = entry?.id
        editorInitialKey = key
        editorInitialValue = value
        showEditor = true
    }

    if (showEditor) {
        EnvironmentVariableEditor(
            entry = editing,
            initialKey = editorInitialKey,
            currentValue = editorInitialValue,
            error = error,
            onDismiss = {
                viewModel.clearEnvironmentError()
                showEditor = false
                editingKey = null
            },
            onSave = { key, value, note ->
                if (editing == null) viewModel.addEnvironmentVariable(key, value, note) { if (it) { showEditor = false } }
                else viewModel.updateEnvironmentVariable(editing!!.id, key, value, note) { if (it) { showEditor = false; editingKey = null } }
            },
        )
    }
    showDelete?.let { entry ->
        RuntimeAlertDialog(
            onDismissRequest = { deleteKey = null },
            title = { Text("删除环境变量") },
            text = { Text("确定删除 ${entry.key}？") },
            confirmButton = { TextButton(onClick = { viewModel.deleteEnvironmentVariable(entry.id); deleteKey = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleteKey = null }) { Text("取消") } },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            RuntimeTopBar(
                "环境变量",
                onBack,
                actions = {
                    IconButton(onClick = { viewModel.refreshEnvironmentVariables() }, enabled = !loading && runtimeState is top.wanxiang.app.core.model.RuntimeState.Ready) {
                        RuntimeIcon(RuntimeIconName.Refresh)
                    }
                    IconButton(onClick = { openEnvironmentEditor(null, "", "") }, enabled = !loading && runtimeState is top.wanxiang.app.core.model.RuntimeState.Ready) {
                        RuntimeIcon(RuntimeIconName.Plus)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SettingsGroup {
                    SettingsRow(
                        icon = RuntimeIconName.Shield,
                        title = "Agent 隐私遮盖",
                        subtitle = "仅遮盖发送给 Agent 和写入对话的变量值；本页仍显示明文",
                        trailing = { Switch(checked = privacyMode, onCheckedChange = viewModel::setEnvironmentPrivacyMode) },
                    )
                }
            }
            item {
                RuntimeCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.72f),
                    borderColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f),
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        RuntimeIcon(RuntimeIconName.Alert, tint = MaterialTheme.colorScheme.tertiary)
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                "警告 · 修改环境变量可能导致运行异常",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            Text(
                                "错误覆盖 JAVA_HOME、GRADLE_HOME、LANG 等变量，可能使终端、构建工具或插件无法启动。请只修改你明确了解用途的变量；WanXiang 运行时关键变量会被强制保护。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    "用户变量保存在 $activeDistroId 的 Linux /etc/profile.d 中，并在下一次命令或终端会话启动时生效。值以受限文件权限保存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            error?.let { message ->
                item {
                    RuntimeCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            message,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            item {
                SectionHeader(
                    title = "用户变量",
                    subtitle = "可编辑的 WanXiang 用户配置",
                    trailing = { Text(entries.size.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            }
            if (loading && entries.isEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            } else if (entries.isEmpty()) {
                item { RuntimeCard(modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("暂无用户变量", style = MaterialTheme.typography.titleMedium); Text("点击右上角 + 添加", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
            } else {
                items(entries, key = { it.id }) { entry ->
                    RuntimeCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { openEnvironmentEditor(entry, entry.key, values[entry.key].orEmpty()) },
                    ) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.key, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                                if (entry.note.isNotBlank()) Text(entry.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    values[entry.key].orEmpty().ifEmpty { "（空值）" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            IconButton(onClick = { openEnvironmentEditor(entry, entry.key, values[entry.key].orEmpty()) }) { RuntimeIcon(RuntimeIconName.Edit) }
                            IconButton(onClick = { deleteKey = entry.id }) { RuntimeIcon(RuntimeIconName.Trash, tint = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
            item {
                SectionHeader(
                    title = "当前有效环境",
                    subtitle = "新命令实际可见的变量与值",
                    trailing = { Text(effectiveEntries.size.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            }
            if (loading && effectiveEntries.isEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            } else if (effectiveEntries.isEmpty()) {
                item {
                    RuntimeCard(modifier = Modifier.fillMaxWidth()) {
                        Text("暂无可读取的运行时环境", modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                item {
                    SettingsGroup {
                        effectiveEntries.forEachIndexed { index, entry ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            val managedEntry = entries.firstOrNull { it.key == entry.key }
                            SettingsRow(
                                icon = RuntimeIconName.Key,
                                title = entry.key,
                                subtitle = entry.value.ifEmpty { "（空值）" },
                                onClick = {
                                    if (managedEntry == null) {
                                        openEnvironmentEditor(null, entry.key, entry.value)
                                    } else {
                                        openEnvironmentEditor(managedEntry, managedEntry.key, values[managedEntry.key].orEmpty())
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EnvironmentVariableEditor(
    entry: top.wanxiang.app.core.model.EnvironmentVariable?,
    initialKey: String,
    currentValue: String,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    var key by remember(entry?.id, initialKey) { mutableStateOf(initialKey) }
    var value by remember(entry?.id, initialKey) { mutableStateOf(currentValue) }
    var note by remember(entry) { mutableStateOf(entry?.note.orEmpty()) }
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entry != null) "编辑环境变量" else if (initialKey.isNotBlank()) "配置环境变量" else "添加环境变量") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    RuntimeIcon(RuntimeIconName.Alert, Modifier.size(18.dp), MaterialTheme.colorScheme.tertiary)
                    Text(
                        if (entry == null && initialKey.isNotBlank()) {
                            "这会在用户配置中覆盖 Linux 当前值。错误配置可能导致相关命令无法运行。"
                        } else {
                            "修改后会影响新启动的命令与终端，请确认变量名称和值正确。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                OutlinedTextField(value = key, onValueChange = { key = it.uppercase() }, label = { Text("名称") }, singleLine = true)
                OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("值") }, singleLine = true)
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("备注（可选）") }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(key, value, note) }, enabled = key.isNotBlank() && (entry != null || value.isNotEmpty())) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

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
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val developer by viewModel.developerMode.collectAsStateWithLifecycle()
    val qemuCompatibilityEnabled by viewModel.qemuCompatibilityEnabled.collectAsStateWithLifecycle()
    val qemuCompatibilityReady by viewModel.qemuCompatibilityReady.collectAsStateWithLifecycle()
    val qemuCompatibilityMessage by viewModel.qemuCompatibilityMessage.collectAsStateWithLifecycle()
    val phantomStatus by viewModel.phantomProcessStatus.collectAsStateWithLifecycle()
    val phantomBusy by viewModel.phantomProcessBusy.collectAsStateWithLifecycle()
    val phantomMessage by viewModel.phantomProcessMessage.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var showBatteryDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showPhantomProcessDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var batteryExempted by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    val isRestrictiveRom = remember { RomAutostartHelper.isKnownRestrictiveRom() }
    val romLabel = remember { RomAutostartHelper.romLabel() }

    LaunchedEffect(Unit) { viewModel.refreshPhantomProcessLimit() }

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
            adbCommand = viewModel.phantomProcessAdbCommand,
            onRefresh = viewModel::refreshPhantomProcessLimit,
            onRemove = viewModel::removePhantomProcessLimit,
            onDismiss = {
                viewModel.clearPhantomProcessMessage()
                showPhantomProcessDialog = false
            },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { RuntimeTopBar("保活与诊断", onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    text = "进程保活与唤醒",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
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
                            viewModel.refreshPhantomProcessLimit()
                        },
                    )
                }
            }

            item {
                Text(
                    text = "Android 系统调试与日志",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
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
                    text = "万象自定义迭代与共建",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
                SettingsGroup {
                    SettingsRow(
                        icon = RuntimeIconName.Code,
                        title = "自定义迭代（WanXiangDev）",
                        subtitle = "在手机沙盒中调用 AI 开发万象自身并云端构建 APK",
                        onClick = onOpenCustomIteration,
                    )
                }
            }

            item {
                Text(
                    text = "开发者调试与控制台",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
                SettingsGroup {
                    ToggleRow(
                        icon = RuntimeIconName.Bug,
                        title = "开发者诊断模式",
                        subtitle = "开启底层健康监控与调试控制台",
                        checked = developer,
                        change = viewModel::setDeveloperMode,
                    )
                    if (developer) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        SettingsRow(
                            icon = RuntimeIconName.Terminal,
                            title = "开发者控制台",
                            subtitle = "实时查看 PRoot 进程与命令追踪",
                            onClick = onOpenDeveloper,
                        )
                    }
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
                        change = viewModel::setQemuCompatibilityEnabled,
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
}


private fun ExecutionModeDialog(
    currentMode: ExecutionMode,
    switching: Boolean,
    onSelectMode: (ExecutionMode) -> Unit,
    onDismiss: () -> Unit,
) {
    RuntimeAlertDialog(
        onDismissRequest = { if (!switching) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RuntimeIcon(RuntimeIconName.Shield, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Text("选择系统运行模式", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "切换特权模式将自动发起授权检测；授权成功后即刻释放对应的高级系统与硬件能力。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (switching) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(12.dp))
                        Text("正在进行特权探测与授权申请…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                ExecutionMode.entries.forEach { mode ->
                    ExecutionModeOptionItem(
                        mode = mode,
                        selected = currentMode == mode,
                        enabled = !switching,
                        onClick = { onSelectMode(mode) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !switching) {
                Text("关闭")
            }
        },
    )
}

@Composable
private fun ExecutionModeOptionItem(
    mode: ExecutionMode,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = mode.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary,
                    ) {
                        Text(
                            "当前激活",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Text(
                text = mode.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "要求: ${mode.requiredPrivilege}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
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
                    "万象在 Agent 执行期间会启动前台服务并持有 CPU 进程锁，但系统电池优化仍可能在息屏后" +
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
                            verticalArrangement = Arrangement.spacedBy(5.dp),
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

internal fun SettingsGroup(content: @Composable () -> Unit) {
    RuntimeCard(
        Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        contentPadding = PaddingValues(0.dp),
    ) {
        Column { content() }
    }
}

@Composable
internal fun SettingsRow(
    icon: RuntimeIconName,
    title: String,
    subtitle: String,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val rowModifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Row(
        rowModifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            RuntimeIcon(icon, Modifier.size(15.dp), MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        value?.let {
            Surface(
                shape = RoundedCornerShape(5.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
            ) {
                Text(
                    it,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.5.dp),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp,
                    ),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onClick != null) {
            RuntimeIcon(RuntimeIconName.ChevronRight, Modifier.size(14.dp), MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        }
        trailing()
    }
}

@Composable
internal fun ToggleRow(
    icon: RuntimeIconName,
    title: String,
    subtitle: String,
    checked: Boolean,
    change: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            RuntimeIcon(icon, Modifier.size(15.dp), MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RuntimeSwitch(
            checked = checked,
            onCheckedChange = change,
            enabled = enabled,
        )
    }
}

@Composable
fun WebChatBridgeDialog(
    status: top.wanxiang.app.runtime.webchat.WebChatServerStatus,
    toggling: Boolean = false,
    onToggle: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuntimeIcon(RuntimeIconName.Globe, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Text("万象智枢 Web 协作台")
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "在同一 Wi-Fi / 局域网下，通过电脑浏览器连接万象智枢，同步处理 Agent 任务、对话与 Linux 工作区文件。",
                    style = MaterialTheme.typography.bodyMedium,
                )

                RuntimeCard(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "协作服务状态",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (toggling) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                }
                                RuntimeSwitch(
                                    checked = status.isRunning,
                                    onCheckedChange = onToggle,
                                    enabled = !toggling,
                                )
                            }
                        }

                        if (status.isRunning) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("浏览器访问地址", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = status.accessUrl,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    TextButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("万象智枢协作地址", status.accessUrl))
                                            Toast.makeText(context, "已复制基础链接", Toast.LENGTH_SHORT).show()
                                        }
                                    ) { Text("复制") }
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("安全配对码 (PIN)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = status.pinCode,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.secondary,
                                    )
                                    TextButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("PIN", status.pinCode))
                                            Toast.makeText(context, "已复制配对码", Toast.LENGTH_SHORT).show()
                                        }
                                    ) { Text("复制") }
                                }
                            }

                            val directUrl = "${status.accessUrl}?token=${status.pinCode}"
                            Button(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("万象智枢直连地址", directUrl))
                                    Toast.makeText(context, "已复制免密直达链接，在电脑浏览器打开即可！", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    RuntimeIcon(RuntimeIconName.Copy, Modifier.size(16.dp))
                                    Text("一键复制免密直达链接 (免输PIN)")
                                }
                            }

                            Text(
                                text = "💡 提示：确保电脑与手机处于同一 Wi-Fi；当前在线设备：${status.activeConnections} 台",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成")
            }
        },
    )
}

/** 应用版本号；三处界面共用一份查询逻辑 */
@Composable
fun rememberAppVersion(): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0),
                ).versionName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }
        } catch (_: Exception) {
            null
        } ?: "unknown"
    }
}
