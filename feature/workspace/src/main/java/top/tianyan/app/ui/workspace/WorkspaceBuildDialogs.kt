package top.tianyan.app.ui.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.tianyan.app.feature.workspace.R
import top.tianyan.app.runtime.build.BuildRunProgress
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeButton as Button
import top.tianyan.app.ui.components.RuntimeCircularProgressIndicator
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeIconButton as IconButton
import top.tianyan.app.ui.components.RuntimeLinearProgressIndicator as LinearProgressIndicator
import top.tianyan.app.ui.components.RuntimeTextButton as TextButton

/**
 * 后台构建常驻状态栏（列表首项 Banner）：
 * 进行中显示可点击进度条，结束后显示成功/失败与安装、详情操作。
 */
@Composable
internal fun WorkspaceBuildStatusBanner(
    buildProgress: BuildRunProgress?,
    activeBuildingProjectName: String?,
    isBuildDialogVisible: Boolean,
    onShowDialog: () -> Unit,
    onLaunchInstaller: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (activeBuildingProjectName != null && !isBuildDialogVisible) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().clickable { onShowDialog() },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RuntimeCircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.workspace_building_project, activeBuildingProjectName.orEmpty()),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildProgress?.step ?: stringResource(R.string.workspace_building),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onShowDialog) {
                    Text(stringResource(R.string.workspace_view_logs), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    } else if (activeBuildingProjectName == null && buildProgress != null && !isBuildDialogVisible) {
        val progress = buildProgress
        Surface(
            color = if (progress.isSuccess == true) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RuntimeIcon(
                    if (progress.isSuccess == true) RuntimeIconName.Check else RuntimeIconName.Close,
                    Modifier.size(18.dp),
                    tint = if (progress.isSuccess == true) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(if (progress.isSuccess == true) R.string.workspace_build_ready else R.string.workspace_build_failed),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (progress.isSuccess == true) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    progress.message?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (progress.isSuccess == true) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (progress.isSuccess == true && progress.apkPath != null) {
                        TextButton(onClick = { onLaunchInstaller(progress.apkPath!!) }) {
                            Text(stringResource(R.string.workspace_install), fontWeight = FontWeight.Bold)
                        }
                    }
                    TextButton(onClick = onShowDialog) {
                        Text(stringResource(R.string.workspace_details))
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(28.dp)
                            .minimumInteractiveComponentSize(),
                        contentDescription = stringResource(R.string.workspace_cd_dismiss),
                    ) {
                        RuntimeIcon(RuntimeIconName.Close, Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

/**
 * 运行/构建进度与实时日志弹窗（支持后台运行与随时最小化）：
 * 含依赖活动、构建阶段耗时分析与分类日志折叠面板。
 */
@Composable
internal fun BuildProgressDialog(
    progress: BuildRunProgress,
    onOpenToolCenter: () -> Unit,
    onHideDialog: () -> Unit,
    onDismissProgress: () -> Unit,
    onCancelBuild: () -> Unit,
    onLaunchInstaller: (String) -> Unit,
) {
    val context = LocalContext.current
    var showBuildLog by remember { mutableStateOf(false) }
    var showStepAnalysis by remember { mutableStateOf(false) }
    // Category collapse states: dependencies & others collapsed by default; compile & package expanded
    var collapsedDeps by remember { mutableStateOf(true) }
    var collapsedCompile by remember { mutableStateOf(false) }
    var collapsedPackage by remember { mutableStateOf(false) }
    var collapsedOther by remember { mutableStateOf(true) }

    RuntimeAlertDialog(
        onDismissRequest = onHideDialog,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (progress.isRunning) {
                    RuntimeCircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                Text(stringResource(if (progress.isRunning) R.string.workspace_building_device else if (progress.isSuccess == true) R.string.workspace_run_ready else R.string.workspace_run_failed), fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(progress.step, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                if (progress.isRunning) {
                    LinearProgressIndicator(
                        progress = { progress.progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp)),
                    )
                }
                if (progress.currentDependency != null || progress.dependencyItemsObserved > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.workspace_dependency_activity),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                val countText = progress.dependenciesTotal?.let { total ->
                                    stringResource(R.string.workspace_dependency_count_total, progress.dependencyItemsObserved, total)
                                } ?: stringResource(R.string.workspace_dependency_count, progress.dependencyItemsObserved)
                                Text(countText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            progress.currentDependency?.let { dependency ->
                                Text(
                                    text = dependency,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            progress.dependencyProgressPercent?.let { percent ->
                                LinearProgressIndicator(
                                    progress = { percent / 100f },
                                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                )
                                Text(
                                    text = stringResource(R.string.workspace_dependency_percent, percent),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                progress.message?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (progress.isSuccess == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // ====== 构建阶段耗时分析 ======
                if (progress.stepDurations.isNotEmpty()) {
                    TextButton(
                        onClick = { showStepAnalysis = !showStepAnalysis },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            RuntimeIcon(if (showStepAnalysis) RuntimeIconName.ArrowUp else RuntimeIconName.ChevronDown, Modifier.size(14.dp))
                            Text(stringResource(if (showStepAnalysis) R.string.workspace_hide_timing else R.string.workspace_timing), style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    if (showStepAnalysis) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                val maxDuration = (progress.stepDurations.maxOfOrNull { it.durationMs } ?: 1L).coerceAtLeast(1L)
                                progress.stepDurations.forEach { step ->
                                    val barRatio = (step.durationMs.toFloat() / maxDuration.toFloat()).coerceIn(0.02f, 1f)
                                    val barColor = when {
                                        step.step.contains("拉取") || step.step.contains("依赖") -> Color(0xFF2196F3) // 蓝色
                                        step.step.contains("编译") || step.step.contains("Kotlin") || step.step.contains("Java") || step.step.contains("Dex") -> Color(0xFF4CAF50) // 绿色
                                        step.step.contains("打包") || step.step.contains("APK") -> Color(0xFFFF9800) // 橙色
                                        else -> Color(0xFF9E9E9E) // 灰色
                                    }
                                    val durationText = if (step.durationMs >= 1000) "${"%.1f".format(step.durationMs / 1000.0)}s" else "${step.durationMs}ms"
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(
                                                text = step.step,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                            Text(
                                                text = durationText,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant),
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth(barRatio)
                                                    .height(6.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(barColor),
                                            )
                                        }
                                    }
                                }
                                // 总时长
                                progress.totalDurationMs?.let { total ->
                                    val totalText = if (total >= 1000) "${"%.1f".format(total / 1000.0)}s" else "${total}ms"
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            text = stringResource(R.string.workspace_total),
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = totalText,
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ====== 构建日志折叠面板 ======
                // The build task survives destination recreation in the
                // coordinator. Its first restored snapshot may not have
                // received log text yet, so keep the entry visible while
                // running (and for completed tasks) instead of tying it
                // to logOutput being non-empty.
                run {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(
                            onClick = { showBuildLog = !showBuildLog },
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                RuntimeIcon(if (showBuildLog) RuntimeIconName.ArrowUp else RuntimeIconName.ChevronDown, Modifier.size(14.dp))
                                Text(stringResource(if (showBuildLog) R.string.workspace_hide_build_log else R.string.workspace_view_build_log), style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        if (showBuildLog && progress.logOutput.isNotBlank()) {
                            TextButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    clipboard?.setPrimaryClip(ClipData.newPlainText("Tianyan Build Log", progress.logOutput))
                                },
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    RuntimeIcon(RuntimeIconName.Copy, Modifier.size(12.dp))
                                    Text(stringResource(R.string.workspace_copy_log), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }

                    if (showBuildLog) {
                        if (progress.logOutput.isBlank()) {
                            Text(
                                text = stringResource(if (progress.isRunning) R.string.workspace_build_log_receiving else R.string.workspace_no_build_log),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // 分类日志
                        val logLines = progress.logOutput.lines()
                        val depKeywords = listOf("downloading", "fetching", "kilobytes", "megabytes", "get")
                        val compileKeywords = listOf("compile", "kotlin", "javac", "dex")
                        val packageKeywords = listOf("package", "install", "apk")
                        fun classifyLine(line: String): Int {
                            val lower = line.lowercase()
                            return when {
                                depKeywords.any { lower.contains(it) } -> 0
                                compileKeywords.any { lower.contains(it) } -> 1
                                packageKeywords.any { lower.contains(it) } -> 2
                                else -> 3
                            }
                        }
                        val categorized = logLines.map { line -> classifyLine(line) to line }
                        val depsLogs = categorized.filter { it.first == 0 }.map { it.second }
                        val compileLogs = categorized.filter { it.first == 1 }.map { it.second }
                        val packageLogs = categorized.filter { it.first == 2 }.map { it.second }
                        val otherLogs = categorized.filter { it.first == 3 }.map { it.second }

                        data class Category(val type: Int, val emoji: String, val name: String, val logs: List<String>)
                        val categories = listOf(
                            Category(0, "📥", stringResource(R.string.workspace_log_dependencies), depsLogs),
                            Category(1, "🔨", stringResource(R.string.workspace_log_compile), compileLogs),
                            Category(2, "📦", stringResource(R.string.workspace_log_package), packageLogs),
                            Category(3, "📋", stringResource(R.string.workspace_log_other), otherLogs),
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            categories.forEach { cat ->
                                if (cat.logs.isNotEmpty()) {
                                    val isCollapsed = when (cat.type) {
                                        0 -> collapsedDeps
                                        1 -> collapsedCompile
                                        2 -> collapsedPackage
                                        else -> collapsedOther
                                    }
                                    TextButton(
                                        onClick = {
                                            when (cat.type) {
                                                0 -> collapsedDeps = !collapsedDeps
                                                1 -> collapsedCompile = !collapsedCompile
                                                2 -> collapsedPackage = !collapsedPackage
                                                else -> collapsedOther = !collapsedOther
                                            }
                                        },
                                        contentPadding = PaddingValues(0.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                RuntimeIcon(if (isCollapsed) RuntimeIconName.ChevronRight else RuntimeIconName.ArrowUp, Modifier.size(12.dp))
                                                Text("${cat.emoji} ${cat.name}", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                            }
                                            Text(stringResource(R.string.workspace_log_count, cat.logs.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    if (!isCollapsed) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Column(modifier = Modifier.padding(6.dp)) {
                                                val displayLogs = if (cat.logs.size > 200) cat.logs.takeLast(200) else cat.logs
                                                displayLogs.forEach { line ->
                                                    Text(
                                                        text = line,
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontFamily = FontFamily.Monospace,
                                                            fontSize = 10.sp,
                                                            lineHeight = 14.sp,
                                                        ),
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                    )
                                                }
                                                if (cat.logs.size > 200) {
                                                    Text(
                                                        text = stringResource(R.string.workspace_log_truncated, cat.logs.size),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!progress.isRunning) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 缺少环境时展示前往插件中心准备环境按钮
                    if (progress.suggestedSuiteId != null) {
                        Button(
                            onClick = {
                                onDismissProgress()
                                onOpenToolCenter()
                            },
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                RuntimeIcon(RuntimeIconName.Package, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary)
                                Text(stringResource(R.string.workspace_prepare_environment))
                            }
                        }
                    }

                    val path = progress.apkPath
                    if (progress.isSuccess == true && path != null) {
                        Button(onClick = { onLaunchInstaller(path) }) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                RuntimeIcon(RuntimeIconName.Download, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary)
                                Text(stringResource(R.string.workspace_launch_install))
                            }
                        }
                    }
                    TextButton(onClick = onDismissProgress) {
                        Text(stringResource(R.string.workspace_done))
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onCancelBuild) {
                        Text(stringResource(R.string.workspace_stop_build), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = onHideDialog) {
                        Text(stringResource(R.string.workspace_background), fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        dismissButton = {
            if (progress.isRunning) {
                TextButton(onClick = onHideDialog) {
                    Text(stringResource(R.string.workspace_collapse))
                }
            }
        },
    )
}
