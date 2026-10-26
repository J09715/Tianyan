package top.tianyan.app.runtime.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

class RuntimeServiceController(
    private val context: Context,
) {
    fun start(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "通知权限未授权，跳过 Runtime 前台保活服务")
            return false
        }
        return runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RuntimeForegroundService::class.java),
            )
            true
        }.onFailure { Log.w(TAG, "启动 Runtime 前台服务失败", it) }
            .getOrDefault(false)
    }

    fun stop() {
        context.startService(
            Intent(context, RuntimeForegroundService::class.java)
                .setAction(RuntimeForegroundService.ACTION_STOP),
        )
    }

    private companion object {
        const val TAG = "RuntimeServiceController"
    }
}
