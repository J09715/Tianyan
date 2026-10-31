package top.tianyan.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import top.tianyan.app.runtime.remote.LocalNetworkAccess
import top.tianyan.app.runtime.remote.needsLocalNetworkPermission

/**
 * Android 17+ 在测试、保存或选用局域网 SSH 前请求 ACCESS_LOCAL_NETWORK。
 * 已授权或系统不要求该权限时直接执行。用户拒绝后，公网主机仍继续；
 * 局域网字面量地址则交给 [onLanDenied]，避免带着必失败的连接去握手。
 */
@Composable
internal fun rememberLocalNetworkPermission(onLanDenied: () -> Unit): (String, () -> Unit) -> Unit {
    val context = LocalContext.current
    val denied by rememberUpdatedState(onLanDenied)
    val pending = remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val request = pending.value
        pending.value = null
        if (request == null) return@rememberLauncherForActivityResult
        if (granted || !LocalNetworkAccess.isLanLiteral(request.first)) request.second() else denied()
    }
    return { host, action ->
        if (!context.needsLocalNetworkPermission()) action()
        else {
            pending.value = host to action
            launcher.launch(LocalNetworkAccess.PERMISSION)
        }
    }
}
