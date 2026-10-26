package top.tianyan.app.ui.settings
import top.tianyan.app.ui.components.previewOrNull
import top.tianyan.app.ui.components.previewStateOf
import org.koin.compose.viewmodel.koinViewModel
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import top.tianyan.app.feature.settings.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tianyan.app.core.database.AndroidAppEntity
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.RuntimeCircularProgressIndicator
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeSettingsScaffold
import top.tianyan.app.ui.components.RuntimeFilterChip
import top.tianyan.app.ui.settings.LocalizedText as Text
@Composable
fun AppManagementScreen(
    onBack: () -> Unit,
    viewModel: AppManagementViewModel? = previewOrNull { koinViewModel() },
) {
    val apps by viewModel?.apps?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val syncing by viewModel?.syncing?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val message by viewModel?.message?.collectAsStateWithLifecycle() ?: previewStateOf(null)
    val messageIsError by viewModel?.messageIsError?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    var showSystemApps by remember { mutableStateOf(false) }

    // Every entry reconciles the live PackageManager inventory with Room: missing packages are
    // removed and newly installed ones are inserted, without rebuilding the UI's data source.
    LaunchedEffect(Unit) { viewModel?.synchronize() }
    val userApps = apps.filterNot { it.isSystemApp }
    val systemApps = apps.filter { it.isSystemApp }

    RuntimeSettingsScaffold(
        title = "应用管理", onBack = onBack, itemSpacing = 12.dp,
        statusText = if (syncing) "正在同步" else "普通读取 · 特权增强",
    ) {
        item {
            SettingsDetailCard(contentPadding = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("受控应用索引", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.settings_app_inventory_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        RuntimeButton(onClick = { viewModel?.synchronize() }, enabled = !syncing) {
                            if (syncing) RuntimeCircularProgressIndicator(Modifier.size(18.dp)) else RuntimeIcon(RuntimeIconName.Refresh, Modifier.size(18.dp))
                            Text(if (syncing) "同步中" else "立即同步", modifier = Modifier.padding(start = 8.dp))
                        }
                        Text("共 ${apps.size} 个", style = MaterialTheme.typography.labelLarge)
                    }
                    message?.let {
                        Text(it, color = if (messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // 纯计数标签：不可交互，避免伪装成可点击的 FilterChip
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(
                        "用户应用 (${userApps.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
                RuntimeFilterChip(
                    shape = CircleShape,
                    selected = showSystemApps,
                    onClick = { showSystemApps = !showSystemApps },
                    label = { Text("显示系统应用 (${systemApps.size})") },
                )
            }
        }
        if (userApps.isEmpty() && !syncing) {
            item { Text("暂无应用数据，请执行一次同步。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(userApps, key = { it.packageName }) { app -> AndroidAppRow(app) }
        if (showSystemApps && systemApps.isNotEmpty()) {
            item {
                Text(
                    "系统应用",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(systemApps, key = { it.packageName }) { app -> AndroidAppRow(app) }
        }
    }
}

@Composable
private fun AndroidAppRow(app: AndroidAppEntity) {
    SettingsDetailCard(contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AndroidAppIcon(app.packageName)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    app.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (app.isEnabled) "已开启" else "已冻结",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (app.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                if (app.isNetworkRestricted) {
                    // 「已冻结」已由上方状态文案表达，此处只补充联网受限信息，避免重复
                    Text(
                        "联网受限",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun AndroidAppIcon(packageName: String) {
    val context = LocalContext.current
    val drawable = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (drawable == null) {
            RuntimeIcon(RuntimeIconName.Package, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            AndroidView(
                factory = {
                    ImageView(it).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        contentDescription = packageName
                        setImageDrawable(drawable)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { image -> image.setImageDrawable(drawable) },
            )
        }
    }
}
