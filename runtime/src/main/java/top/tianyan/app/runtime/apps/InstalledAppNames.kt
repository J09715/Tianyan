package top.tianyan.app.runtime.apps

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** 已安装应用的包名和应用名。手机操作模型说「微信」时靠应用名找回包名。 */
fun installedAppNames(context: Context): List<Pair<String, String>> = runCatching {
    val manager = context.packageManager
    val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        manager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        manager.getInstalledApplications(0)
    }
    apps.map { info ->
        val label = runCatching { info.loadLabel(manager).toString() }.getOrDefault(info.packageName).ifBlank { info.packageName }
        info.packageName to label
    }
}.getOrDefault(emptyList())
