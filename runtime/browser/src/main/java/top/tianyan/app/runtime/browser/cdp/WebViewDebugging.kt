package top.tianyan.app.runtime.browser.cdp

import android.os.Process
import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 进程级开关由启动偏好持有；detach 只关闭 CDP 会话，不撤销用户的调试授权。 */
object WebViewDebugging {
    @Volatile private var enabled = false
    @Volatile private var provider = "unknown"
    @Volatile private var lastFailure: String? = null

    /** 主线程执行并等待完成；不缓存成功或失败，每次 attach 都允许重试。 */
    suspend fun setEnabled(value: Boolean) = withContext(Dispatchers.Main.immediate) {
        try {
            WebView.setWebContentsDebuggingEnabled(value)
            enabled = value
            lastFailure = null
            provider = runCatching {
                WebView.getCurrentWebViewPackage()?.let { "${it.packageName}/${it.versionName}" }
            }.getOrNull() ?: "unknown"
        } catch (e: Exception) {
            lastFailure = "${e.javaClass.simpleName}: ${e.message}"
            Log.e("TianyanCdp", "WebView debugging=$value failed; pid=${Process.myPid()}", e)
            throw IllegalStateException("WebView 调试开关设置失败（enabled=$value）: $lastFailure", e)
        }
    }

    fun diagnostics(): String =
        "pid=${Process.myPid()}, provider=$provider, debuggingEnabled=$enabled, enableError=$lastFailure"
}
