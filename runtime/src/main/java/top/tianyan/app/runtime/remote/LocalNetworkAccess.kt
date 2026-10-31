package top.tianyan.app.runtime.remote

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Android 17（API 37）把局域网访问收进「附近的设备」运行时权限。
 * targetSdk >= 37 时只在清单里声明不会放行，必须在连接前向用户请求。
 */
object LocalNetworkAccess {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    const val MIN_SDK = 37

    /** 局域网 SSH 失败或用户拒绝授权时展示的固定说明，不回显异常原文。 */
    const val USER_HINT =
        "请允许「附近的设备 / 本地网络」权限。Android 17 及以上会拦截未授权应用的局域网连接"

    fun enforcedAt(sdkInt: Int): Boolean = sdkInt >= MIN_SDK

    fun isLanLiteral(host: String): Boolean = bindsUnderlyingLan(host)
}

fun Context.needsLocalNetworkPermission(): Boolean =
    LocalNetworkAccess.enforcedAt(Build.VERSION.SDK_INT) &&
        checkSelfPermission(LocalNetworkAccess.PERMISSION) != PackageManager.PERMISSION_GRANTED
