package top.tianyan.app.ui.home
import top.tianyan.app.ui.components.previewOrNull
import top.tianyan.app.ui.components.previewStateOf
import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import top.tianyan.app.ui.components.RuntimeButton as Button
import androidx.compose.material3.ButtonDefaults
import top.tianyan.app.ui.components.RuntimeCircularProgressIndicator
import top.tianyan.app.ui.components.RuntimeFilledTonalButton as FilledTonalButton
import top.tianyan.app.ui.components.RuntimeIconButton as IconButton
import top.tianyan.app.ui.components.RuntimeLinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import top.tianyan.app.ui.components.RuntimeOutlinedButton as OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import top.tianyan.app.ui.components.RuntimeTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import top.tianyan.app.ui.components.RuntimeSwitch
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import top.tianyan.app.feature.home.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tianyan.app.core.model.DoctorItem
import top.tianyan.app.core.model.DoctorReport
import top.tianyan.app.core.model.DoctorStatus
import top.tianyan.app.core.model.RepairProgress
import top.tianyan.app.core.model.RuntimeState
import top.tianyan.app.ui.components.MainDestination
import top.tianyan.app.ui.components.IconTile
import top.tianyan.app.ui.components.NoticeBanner
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeTopBar
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.distroIconFor
import top.tianyan.app.ui.components.StatusBadge
import android.os.SystemClock
import top.tianyan.app.core.model.InstalledDistro
/** 自动体检防抖间隔：ON_RESUME 触发时距上次不足该间隔则跳过。 */
private const val DOCTOR_CHECK_MIN_INTERVAL_MS = 30_000L

/**
 * 健康与警告色统一跟随主题的谐调语义色板。
 */
@Composable
private fun healthyStatusColor(): Color =
    top.tianyan.app.ui.theme.semanticColors().success

@Composable
private fun warningStatusColor(): Color =
    top.tianyan.app.ui.theme.semanticColors().warning

/**
 * 天衍 · 运行仪表盘 (Tianyan Linux Runtime Dashboard)
 * 集成沙箱引擎状态、运行环境体检自愈中心、实时内存/存储监控与规格信息
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun LocalHomeScreen(
    onNavigate: (MainDestination) -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenToolCenter: () -> Unit = {},
    viewModel: HomeViewModel? = previewOrNull { koinViewModel() }, onBack: () -> Unit = { onNavigate(MainDestination.Agent) },
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var lastAutoDoctorCheckAt by remember { mutableLongStateOf(0L) }

    val state by viewModel?.runtimeState?.collectAsStateWithLifecycle() ?: previewStateOf(top.tianyan.app.core.model.RuntimeState.Ready)
    val metrics by viewModel?.metrics?.collectAsStateWithLifecycle() ?: previewStateOf(SystemResourceMetrics())
    val doctorReport by viewModel?.doctorReport?.collectAsStateWithLifecycle() ?: previewStateOf(null)
    val isCheckingDoctor by viewModel?.isCheckingDoctor?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val repairProgress by viewModel?.repairProgress?.collectAsStateWithLifecycle() ?: previewStateOf(null)
    val isRepairing by viewModel?.isRepairing?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val installedDistros by viewModel?.installedDistros?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val activeDistroId by viewModel?.activeDistroId?.collectAsStateWithLifecycle() ?: previewStateOf("ubuntu")
    val switchingDistro by viewModel?.switchingDistro?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val modeStatus by viewModel?.executionModeStatus?.collectAsStateWithLifecycle() ?: previewStateOf(ExecutionModeStatus())
    val webChatStatus by viewModel?.webChatStatus?.collectAsStateWithLifecycle() ?: previewStateOf(top.tianyan.app.runtime.webchat.WebChatServerStatus())

    val allFilesPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel?.runDoctorCheck()
    }
    val legacyStoragePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        viewModel?.runDoctorCheck()
    }

    val requestAllFilesAccess: () -> Unit = remember(context) {
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    allFilesPermissionLauncher.launch(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        )
                    )
                }.onFailure {
                    runCatching {
                        allFilesPermissionLauncher.launch(
                            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        )
                    }.onFailure {
                        allFilesPermissionLauncher.launch(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )
                    }
                }
            } else {
                legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // 防抖：间隔过短（如切 Tab / 亮灭屏）时跳过自动体检，避免每次切回都闪检查态。
                // 用户点顶栏刷新仍无条件执行。
                val now = SystemClock.elapsedRealtime()
                if (now - lastAutoDoctorCheckAt > DOCTOR_CHECK_MIN_INTERVAL_MS) {
                    lastAutoDoctorCheckAt = now
                    viewModel?.runDoctorCheck()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val scrollBehavior = androidx.compose.material3.TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            RuntimeTopBar(
                title = stringResource(R.string.home_dashboard_title), onBack = onBack,
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(
                        onClick = {
                            viewModel?.refreshMetrics()
                            viewModel?.runDoctorCheck()
                            viewModel?.refreshExecutionModeStatus()
                        },
                        enabled = !isCheckingDoctor,
                        contentDescription = stringResource(R.string.home_refresh),
                    ) {
                        if (isCheckingDoctor) {
                            // 刷新进行中：显示加载指示并禁用，防止重复触发
                            RuntimeCircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            RuntimeIcon(
                                name = RuntimeIconName.Refresh,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(top.tianyan.app.ui.components.runtimeContentPadding(horizontal = 16.dp, top = 12.dp, bottom = 12.dp))
                .padding(bottom = innerPadding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // 1. 运行时引擎主状态卡片 (Status Banner)
            RuntimeEngineStatusCard(
                state = state,
                metrics = metrics,
                installedDistros = installedDistros,
                activeDistroId = activeDistroId,
                switchingDistro = switchingDistro,
                modeStatus = modeStatus,
                onSwitchDistro = { arg0 -> viewModel?.switchDistro(arg0) },
                onInitialize = { viewModel?.initializeRuntime() },
                onCancel = { viewModel?.cancelInitialization() },
                onOpenTerminal = onOpenTerminal,
                onOpenModeSettings = { onNavigate(MainDestination.Settings) },
            )

            // 2. 运行与开发环境体检自愈中心 (Tianyan Doctor & Auto-Fix)
            EnvironmentDoctorCard(
                report = doctorReport,
                isChecking = isCheckingDoctor,
                isRepairing = isRepairing,
                repairProgress = repairProgress,
                runtimeReady = state is RuntimeState.Ready,
                onStartAutoRepair = {
                    if (doctorReport?.items?.any { it.id == "host_all_files_access" && it.status != DoctorStatus.HEALTHY } == true) {
                        requestAllFilesAccess()
                    }
                    viewModel?.startAutoRepair()
                },
                onCancelRepair = { viewModel?.cancelAutoRepair() },
                onRequestAllFilesAccess = requestAllFilesAccess,
                onOpenToolCenter = onOpenToolCenter,
            )

            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                maxItemsInEachRow = 2,
            ) {
                // 内存指标
                ResourceMetricCard(
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(min = 144.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)),
                    title = stringResource(R.string.home_memory),
                    primaryValue = "${metrics.memoryUsedMb} MB",
                    secondaryValue = stringResource(R.string.home_memory_total, metrics.memoryTotalMb),
                    progress = (metrics.memoryUsagePercent / 100f).coerceIn(0f, 1f),
                    progressText = stringResource(R.string.home_memory_used, metrics.memoryUsagePercent),
                    extraInfo = stringResource(R.string.home_app_heap, metrics.appHeapUsedMb),
                    accentColor = MaterialTheme.colorScheme.primary,
                    icon = RuntimeIconName.Cpu,
                )

                // 存储指标
                ResourceMetricCard(
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(min = 144.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)),
                    title = stringResource(R.string.home_storage),
                    primaryValue = "${metrics.storageUsedGb} GB",
                    secondaryValue = stringResource(R.string.home_storage_total, metrics.storageTotalGb),
                    progress = (metrics.storageUsagePercent / 100f).coerceIn(0f, 1f),
                    progressText = stringResource(R.string.home_storage_used, metrics.storageUsagePercent),
                    extraInfo = stringResource(R.string.home_device_storage_note),
                    accentColor = MaterialTheme.colorScheme.secondary,
                    icon = RuntimeIconName.Storage,
                )
            }

            // 3. WebChat 电脑大屏协作卡片 (Dashboard Bridge Card)
            WebChatDashboardCard(
                status = webChatStatus,
                onToggle = { arg0 -> viewModel?.toggleWebChat(arg0) },
            )

            // 4. 运行环境与规格详情（低频信息，默认折叠）
            SystemSpecsCard(metrics = metrics, modeStatus = modeStatus)
        }
    }
}
/** 运行与开发环境体检自愈卡片 (Tianyan Doctor & Auto-Fix) */
@Composable
private fun EnvironmentDoctorCard(
    report: DoctorReport?,
    isChecking: Boolean,
    isRepairing: Boolean,
    repairProgress: RepairProgress?,
    runtimeReady: Boolean,
    onStartAutoRepair: () -> Unit,
    onCancelRepair: () -> Unit,
    onRequestAllFilesAccess: () -> Unit = {},
    onOpenToolCenter: () -> Unit = {},
) {
    var isCardExpanded by rememberSaveable { mutableStateOf(false) }
    var expandedDetails by rememberSaveable { mutableStateOf(false) }
    var showLogs by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    // 仅在实际触发自愈修复时自动展开
    LaunchedEffect(isRepairing) {
        if (isRepairing) isCardExpanded = true
    }

    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        onClick = { isCardExpanded = !isCardExpanded },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    IconTile(
                        icon = if (report?.isAllHealthy == true) RuntimeIconName.Check else RuntimeIconName.Shield,
                        size = 36.dp,
                        color = if (report?.isAllHealthy == true) healthyStatusColor() else MaterialTheme.colorScheme.primary,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.home_doctor_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = when {
                                isRepairing -> stringResource(R.string.home_doctor_repairing)
                                isChecking -> stringResource(R.string.home_doctor_checking)
                                report == null -> stringResource(if (runtimeReady) R.string.home_doctor_ready else R.string.home_waiting_sandbox)
                                report.isAllHealthy -> stringResource(R.string.home_development_ready)
                                report.needsFix -> stringResource(R.string.home_doctor_pending, report.warningCount + report.errorCount)
                                else -> stringResource(R.string.home_doctor_complete)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (report?.needsFix == true && !isCardExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (isChecking) {
                        RuntimeCircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    RuntimeIcon(
                        name = RuntimeIconName.ChevronDown,
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(if (isCardExpanded) 180f else 0f),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }

            // 展开区域：修复进度与体检指标详情
            AnimatedVisibility(
                visible = isCardExpanded || isRepairing,
                enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 修复中状态展示
                    if (isRepairing && repairProgress != null) {
                        // 去嵌套：卡内区块改用平面 Surface，避免玻璃主题下多层卡片材质冲突
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = stringResource(R.string.home_repair_step, repairProgress.stepIndex, repairProgress.totalSteps, repairProgress.stepTitle),
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${(repairProgress.progress * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(start = 8.dp),
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }

                                RuntimeLinearProgressIndicator(
                                    progress = { repairProgress.progress },
                                    modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(4.dp)),
                                    color = MaterialTheme.colorScheme.primary,
                                    wavy = true,
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TextButton(
                                        onClick = { showLogs = !showLogs },
                                        contentPadding = PaddingValues(horizontal = 4.dp),
                                    ) {
                                        Text(
                                            text = if (showLogs) stringResource(R.string.home_hide_logs) else stringResource(R.string.home_view_live_logs, repairProgress.logs.size),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }

                                    TextButton(
                                        onClick = onCancelRepair,
                                        contentPadding = PaddingValues(horizontal = 4.dp),
                                    ) {
                                        Text(stringResource(R.string.home_cancel_repair), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                    }
                                }

                                AnimatedVisibility(visible = showLogs) {
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 140.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(8.dp)
                                                .verticalScroll(rememberScrollState()),
                                        ) {
                                            repairProgress.logs.takeLast(20).forEach { logLine ->
                                                Text(
                                                    text = logLine,
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 11.sp,
                                                    ),
                                                    color = if (logLine.startsWith("ERR:")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 体检报告列表展示
                    if (report != null && !isRepairing) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val attentionItems = report.items.filter { it.status != DoctorStatus.HEALTHY }
                            val displayedItems = if (expandedDetails) report.items else attentionItems
                            displayedItems.forEach { item ->
                                DoctorItemRow(
                                    item = item,
                                    onRequestAllFilesAccess = onRequestAllFilesAccess,
                                )
                            }

                            if (report.items.size > attentionItems.size) {
                                TextButton(
                                    onClick = { expandedDetails = !expandedDetails },
                                    modifier = Modifier.align(Alignment.CenterHorizontally),
                                ) {
                                    Text(
                                        text = if (expandedDetails) stringResource(R.string.home_hide_details) else stringResource(R.string.home_view_checks, report.items.size),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }

                            // Android 环境未安装：展示离线包 / 在线插件两条获取路径
                            // UNKNOWN（沙箱正忙探测不可达）不算未安装，不弹引导卡
                            val missingAndroidEnv = report.items.any {
                                it.id == "android_environment" &&
                                    (it.status == DoctorStatus.WARNING || it.status == DoctorStatus.ERROR)
                            }
                            if (missingAndroidEnv) {
                                AndroidEnvAcquisitionCard(
                                    onJoinQqGroup = { joinQqGroup(context) },
                                    onOpenToolCenter = onOpenToolCenter,
                                )
                            }
                        }

                        // 一键修复按钮或就绪横幅
                        if (report.needsFix) {
                            RuntimeButton(
                                onClick = onStartAutoRepair,
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(vertical = 12.dp),
                            ) {
                                RuntimeIcon(
                                    name = RuntimeIconName.Play,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.home_repair),
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        } else if (report.isAllHealthy) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(healthyStatusColor().copy(alpha = 0.1f))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                RuntimeIcon(
                                    name = RuntimeIconName.Check,
                                    modifier = Modifier.size(16.dp),
                                    tint = healthyStatusColor(),
                                )
                                Text(
                                    text = stringResource(R.string.home_environment_ready_description),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = healthyStatusColor(),
                                )
                            }
                        }
                    } else if (!runtimeReady) {
                        Text(
                            text = stringResource(R.string.home_initialize_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
/** 体检条目单行展示 */
@Composable
private fun DoctorItemRow(
    item: DoctorItem,
    onRequestAllFilesAccess: () -> Unit = {},
) {
    val isAllFilesIssue = item.id == "host_all_files_access" && item.status != DoctorStatus.HEALTHY
    val statusColor = when (item.status) {
        DoctorStatus.HEALTHY -> healthyStatusColor()
        DoctorStatus.WARNING -> warningStatusColor()
        DoctorStatus.ERROR -> MaterialTheme.colorScheme.error
        DoctorStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
        DoctorStatus.CHECKING -> MaterialTheme.colorScheme.tertiary
    }

    val statusIcon = when (item.status) {
        DoctorStatus.HEALTHY -> RuntimeIconName.Check
        DoctorStatus.WARNING -> RuntimeIconName.Alert
        DoctorStatus.ERROR -> RuntimeIconName.Alert
        DoctorStatus.UNKNOWN -> RuntimeIconName.Info
        DoctorStatus.CHECKING -> RuntimeIconName.Refresh
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .then(
                if (isAllFilesIssue) Modifier.clickable { onRequestAllFilesAccess() }
                else Modifier
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f),
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                RuntimeIcon(
                    name = statusIcon,
                    modifier = Modifier.size(14.dp),
                    tint = statusColor,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "· ${item.category.displayName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                Text(
                    text = item.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.status != DoctorStatus.HEALTHY) statusColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val detail = item.detail
                if (!detail.isNullOrBlank() && item.status != DoctorStatus.HEALTHY) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (isAllFilesIssue) {
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = onRequestAllFilesAccess,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                modifier = Modifier.height(30.dp),
            ) {
                Text(stringResource(R.string.home_grant_access), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
            }
        }
    }
}

/** Android 环境未安装引导卡：提供「QQ 群全量离线插件包」与「插件中心在线安装」两条获取路径。 */
@Composable
private fun AndroidEnvAcquisitionCard(
    onJoinQqGroup: () -> Unit,
    onOpenToolCenter: () -> Unit,
) {
    // 去嵌套：本卡嵌在体检卡展开区内，改用平面 Surface + 淡主色底，去掉描边
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTile(
                    icon = RuntimeIconName.Android,
                    size = 36.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.home_android_env_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.home_android_env_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RuntimeButton(
                    onClick = onJoinQqGroup,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    RuntimeIcon(RuntimeIconName.Qq, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.home_android_env_qq),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FilledTonalButton(
                    onClick = onOpenToolCenter,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    RuntimeIcon(RuntimeIconName.Extension, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.home_android_env_tool_center),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private const val TIANYAN_QQ_GROUP_ID = "964382207"

/** 跳转 QQ 加群；未安装 QQ 时兜底复制群号并提示。 */
private fun joinQqGroup(context: Context) {
    val uri = Uri.parse(
        "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=$TIANYAN_QQ_GROUP_ID&card_type=group&source=qrcode",
    )
    val intent = Intent(Intent.ACTION_VIEW, uri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    runCatching {
        context.startActivity(intent)
    }.onFailure {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(
            ClipData.newPlainText(context.getString(R.string.home_qq_clipboard_label), TIANYAN_QQ_GROUP_ID),
        )
        Toast.makeText(context, context.getString(R.string.home_qq_copied, TIANYAN_QQ_GROUP_ID), Toast.LENGTH_LONG).show()
    }
}

/** 运行时引擎主状态卡片 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RuntimeEngineStatusCard(
    state: RuntimeState,
    metrics: SystemResourceMetrics,
    installedDistros: List<InstalledDistro> = emptyList(),
    activeDistroId: String = "ubuntu",
    switchingDistro: Boolean = false,
    modeStatus: ExecutionModeStatus = ExecutionModeStatus(),
    onSwitchDistro: (String) -> Unit = {},
    onInitialize: () -> Unit,
    onCancel: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenModeSettings: () -> Unit = {},
) {
    val ready = state is RuntimeState.Ready
    val error = state is RuntimeState.Error
    val initializing = state as? RuntimeState.Initializing

    val statusColor = when {
        ready -> healthyStatusColor()
        error -> MaterialTheme.colorScheme.error
        initializing != null -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }

    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentPadding = PaddingValues(18.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    PulsingStatusDot(color = statusColor, isPulsing = ready || initializing != null)
                    Column {
                        Text(
                            text = when {
                                ready -> stringResource(R.string.home_runtime_ready)
                                error -> stringResource(R.string.home_runtime_error)
                                initializing != null -> stringResource(R.string.home_runtime_initializing)
                                else -> stringResource(R.string.home_runtime_uninitialized)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = metrics.linuxDistro,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // 右侧展示当前发行版官方精确 Logo 徽章
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    RuntimeIcon(
                        name = distroIconFor(activeDistroId),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            // 当前激活的运行特权模式徽章（点击跳转设置切换）
            ExecutionModeBadge(
                modeStatus = modeStatus,
                onClick = onOpenModeSettings,
            )

            // 活跃进程与运行时长（并入主状态卡，替代原独立卡片，避免与下方"打开控制台"按钮重复）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RuntimeIcon(
                    name = RuntimeIconName.List,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${stringResource(R.string.home_active_processes, metrics.activeProcessCount)} · ${stringResource(R.string.home_uptime, metrics.uptimeFormatted)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 多系统快速切换 Chips（安装了 2 套及以上时展示，自动换行，避免横向滚动）
            if (ready && installedDistros.size > 1) {
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.home_switch_system),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(end = 2.dp)
                            .align(androidx.compose.ui.Alignment.CenterVertically),
                    )
                    if (switchingDistro) {
                        RuntimeCircularProgressIndicator(
                            modifier = Modifier
                                .size(12.dp)
                                .align(androidx.compose.ui.Alignment.CenterVertically),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    installedDistros.forEach { d ->
                        val isSelected = d.id.equals(activeDistroId, ignoreCase = true)
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else Color.Transparent,
                            ),
                            modifier = Modifier.clickable(enabled = !switchingDistro) {
                                if (!isSelected) onSwitchDistro(d.id)
                            },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                RuntimeIcon(distroIconFor(d.id), Modifier.size(13.dp))
                                Text(
                                    text = if (isSelected && switchingDistro) stringResource(R.string.home_switching) else d.displayName,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal),
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            if (initializing != null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RuntimeLinearProgressIndicator(
                        progress = { initializing.progress },
                        modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        wavy = true,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = initializing.step,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(
                            onClick = onCancel,
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Text(stringResource(R.string.home_cancel), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            if (state is RuntimeState.Error) {
                NoticeBanner(
                    text = state.throwable.message ?: stringResource(R.string.home_runtime_error_fallback),
                    isError = true,
                )
            }

            // 快捷控制操作区
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (ready) {
                    RuntimeButton(
                        onClick = onOpenTerminal,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 10.dp),
                    ) {
                        RuntimeIcon(
                            name = RuntimeIconName.Terminal,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.home_open_console), fontWeight = FontWeight.SemiBold)
                    }
                } else if (initializing == null) {
                    RuntimeButton(
                        onClick = onInitialize,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 10.dp),
                    ) {
                        RuntimeIcon(
                            name = RuntimeIconName.Play,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (error) R.string.home_retry_initialization else R.string.home_initialize_now), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/**
 * 当前激活的运行特权模式徽章：展示模式短标签与授权生效状态，点击跳转设置页切换。
 */
@Composable
private fun ExecutionModeBadge(
    modeStatus: ExecutionModeStatus,
    onClick: () -> Unit,
) {
    // 状态色：生效=绿色 / 探测中=主题色 / 未生效=琥珀警示
    val statusColor = when {
        modeStatus.checking -> MaterialTheme.colorScheme.primary
        modeStatus.active -> healthyStatusColor()
        else -> warningStatusColor()
    }
    val statusText = when {
        modeStatus.checking -> stringResource(R.string.home_mode_checking)
        modeStatus.degraded -> stringResource(R.string.home_mode_degraded)
        modeStatus.active -> stringResource(R.string.home_mode_active)
        else -> stringResource(R.string.home_mode_inactive)
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(statusColor.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                RuntimeIcon(
                    name = RuntimeIconName.Shield,
                    modifier = Modifier.size(16.dp),
                    tint = statusColor,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_current_mode, modeStatus.mode.shortLabel),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (modeStatus.degraded) {
                        stringResource(R.string.home_mode_degraded_reason, modeStatus.preferredMode.shortLabel, modeStatus.reason)
                    } else {
                        modeStatus.mode.summary
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (modeStatus.checking) {
                RuntimeCircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = statusColor,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = statusColor,
            )
        }
    }
}

/** 资源监控指标卡片 */
/** 宿主与运行环境规格卡片（低频信息，默认折叠） */
@Composable
private fun SystemSpecsCard(metrics: SystemResourceMetrics, modeStatus: ExecutionModeStatus) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.home_environment_details),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                RuntimeIcon(
                    name = RuntimeIconName.ChevronDown,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(if (expanded) 180f else 0f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SpecRow(label = stringResource(R.string.home_cpu_architecture), value = metrics.cpuArch)
                    SpecRow(label = stringResource(R.string.home_host_os), value = metrics.hostAndroidVersion)
                    SpecRow(label = stringResource(R.string.home_runtime_engine), value = metrics.engineVersion)
                    SpecRow(label = stringResource(R.string.home_guest_os), value = metrics.linuxDistro)
                    SpecRow(
                        label = stringResource(R.string.home_privilege_mode),
                        value = modeStatus.mode.shortLabel +
                            if (modeStatus.active || modeStatus.checking) "" else " · ${stringResource(R.string.home_mode_inactive)}",
                    )
                }
            }
        }
    }
}

@Composable
private fun SpecRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(0.58f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 状态呼吸灯圆点 */
@Composable
private fun PulsingStatusDot(color: Color, isPulsing: Boolean) {
    val transition = rememberInfiniteTransition(label = "status_dot_pulse")
    val alpha by if (isPulsing) {
        transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse_alpha",
        )
    } else {
        remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    }

    Box(
        modifier = Modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha * 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}
