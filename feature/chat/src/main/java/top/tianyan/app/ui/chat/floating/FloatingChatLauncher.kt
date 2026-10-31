package top.tianyan.app.ui.chat.floating

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import top.tianyan.app.feature.chat.R
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.RuntimeTextButton

/** Keeps permission/result state alive when the workbench sheet closes. */
@Composable
internal fun rememberFloatingChatLauncher(): (() -> Unit)? {
    val context = LocalContext.current
    val activity = floatingChatActivity(context) ?: return null
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var showPermission by rememberSaveable { mutableStateOf(false) }
    val openWindow: () -> Unit = {
        scope.launch {
            // Settings returns before MainActivity.onResume closes an existing overlay. Launch
            // after that callback so the new window is not immediately stopped by the host.
            lifecycle.withResumed { }
            yield()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                !activity.isFinishing && !activity.isDestroyed && Settings.canDrawOverlays(activity)
            ) {
                runCatching {
                    FloatingChatService.start(activity)
                    activity.moveTaskToBack(true)
                }.onFailure {
                    Toast.makeText(context, R.string.chat_floating_launch_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(activity)) openWindow()
    }
    if (showPermission) {
        RuntimeAlertDialog(
            onDismissRequest = { showPermission = false },
            title = { Text(stringResource(R.string.chat_floating_permission_title)) },
            text = { Text(stringResource(R.string.chat_floating_permission_message)) },
            confirmButton = {
                RuntimeButton(onClick = {
                    showPermission = false
                    runCatching {
                        permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            "package:${context.packageName}".toUri()))
                    }.onFailure {
                        Toast.makeText(context, R.string.chat_floating_launch_failed, Toast.LENGTH_SHORT).show()
                    }
                }) { Text(stringResource(R.string.chat_floating_permission_grant)) }
            },
            dismissButton = {
                RuntimeTextButton(onClick = { showPermission = false }) {
                    Text(stringResource(R.string.chat_floating_permission_cancel))
                }
            },
        )
    }
    return { if (Settings.canDrawOverlays(activity)) openWindow() else showPermission = true }
}

private fun floatingChatActivity(context: Context): Activity? {
    var current = context
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
