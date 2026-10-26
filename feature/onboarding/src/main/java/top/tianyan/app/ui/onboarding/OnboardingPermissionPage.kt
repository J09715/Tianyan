package top.tianyan.app.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import top.tianyan.app.feature.onboarding.R
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeOutlinedButton
import top.tianyan.app.ui.components.runtimeContentPadding

private enum class SetupPermission(val title: Int, val description: Int, val icon: RuntimeIconName) {
    NOTIFICATION(R.string.onboarding_permission_notification, R.string.onboarding_permission_notification_hint, RuntimeIconName.Chat),
    OVERLAY(R.string.onboarding_permission_overlay, R.string.onboarding_permission_overlay_hint, RuntimeIconName.Terminal),
    FILES(R.string.onboarding_permission_files, R.string.onboarding_permission_files_hint, RuntimeIconName.Folder),
    AUTOSTART(R.string.onboarding_permission_autostart, R.string.onboarding_permission_autostart_hint, RuntimeIconName.PowerSettingsNew),
    BATTERY(R.string.onboarding_permission_battery, R.string.onboarding_permission_battery_hint, RuntimeIconName.Battery),
}

@Composable
internal fun OnboardingPermissionPage(
    modifier: Modifier = Modifier,
    onOpenAutostartSettings: (() -> Boolean)? = null,
    onNotificationGranted: () -> Unit = {},
) {
    val context = LocalContext.current
    val preview = LocalInspectionMode.current
    var statuses by remember { mutableStateOf(if (preview) emptyMap() else permissionStatuses(context)) }
    var notificationRequested by rememberSaveable { mutableStateOf(false) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        statuses = permissionStatuses(context)
        if (granted) onNotificationGranted()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (!preview) statuses = permissionStatuses(context) }

    LazyColumn(
        modifier = modifier,
        contentPadding = runtimeContentPadding(horizontal = 24.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OnboardingPageHeading(
                title = stringResource(R.string.onboarding_permissions_title),
                description = stringResource(R.string.onboarding_permissions_description),
            )
        }
        items(SetupPermission.entries) { permission ->
            val granted = statuses[permission] == true
            val legacyFiles = permission == SetupPermission.FILES && Build.VERSION.SDK_INT < Build.VERSION_CODES.R
            RuntimeCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RuntimeIcon(permission.icon, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(permission.title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    }
                    Text(stringResource(permission.description), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        stringResource(when {
                            permission == SetupPermission.AUTOSTART -> R.string.onboarding_permission_manual
                            legacyFiles -> R.string.onboarding_permission_files_legacy
                            granted && permission == SetupPermission.BATTERY -> R.string.onboarding_permission_battery_granted
                            granted -> R.string.onboarding_permission_granted
                            else -> R.string.onboarding_permission_optional
                        }),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    RuntimeOutlinedButton(
                        onClick = {
                            if (permission == SetupPermission.AUTOSTART && onOpenAutostartSettings != null) {
                                if (!onOpenAutostartSettings()) {
                                    Toast.makeText(context, R.string.onboarding_permission_unavailable, Toast.LENGTH_LONG).show()
                                }
                            } else if (permission == SetupPermission.NOTIFICATION && Build.VERSION.SDK_INT >= 33 &&
                                !notificationRequested && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationRequested = true
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                openPermissionSettings(context, permission)
                            }
                        },
                        enabled = !legacyFiles,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(if (granted) R.string.onboarding_permission_manage else R.string.onboarding_permission_configure),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** 始终可继续，授权结果不参与导航门控。 */
@Composable
internal fun PermissionContinueActions(onContinue: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(WindowInsets.navigationBars.asPaddingValues())
        .padding(horizontal = 24.dp, vertical = 16.dp), contentAlignment = Alignment.Center) {
        RuntimeButton(onClick = onContinue, modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_permissions_continue))
        }
    }
}

private fun permissionStatuses(context: Context): Map<SetupPermission, Boolean> = mapOf(
    SetupPermission.NOTIFICATION to NotificationManagerCompat.from(context).areNotificationsEnabled(),
    SetupPermission.OVERLAY to Settings.canDrawOverlays(context),
    SetupPermission.FILES to (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()),
    SetupPermission.BATTERY to context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
)

private fun openPermissionSettings(context: Context, permission: SetupPermission) {
    val appUri = "package:${context.packageName}".toUri()
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appUri)
    val intents = when (permission) {
        SetupPermission.NOTIFICATION -> listOf(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        SetupPermission.OVERLAY -> listOf(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, appUri))
        SetupPermission.FILES -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, appUri),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        ) else emptyList()
        SetupPermission.AUTOSTART -> emptyList()
        // 未豁免时直接请求系统授权框；已豁免后提供设置入口，避免再次请求无响应。
        SetupPermission.BATTERY -> if (context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) {
            listOf(details, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } else listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, appUri),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )
    }
    for (intent in intents + details) {
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // OEM 不支持此页面时尝试下一个系统入口。
        } catch (_: SecurityException) {
            // 部分系统入口只允许系统应用打开。
        }
    }
    Toast.makeText(context, R.string.onboarding_permission_unavailable, Toast.LENGTH_LONG).show()
}
