package top.tianyan.app.runtime.browser.inject

import android.webkit.WebView

/**
 * per-tab WebView 脚本注入器的生命周期契约。
 *
 * 实现：hook 运行时（top.tianyan.app.runtime.browser.hook.HookInstaller，随 allowHooks 门禁）
 * 与 vConsole 调试面板（[VConsoleInstaller]，随 allowVConsole 门禁）。
 * [top.tianyan.app.runtime.browser.engine.WebViewTabPool] 持有启用中的实现列表，
 * 在 WebView 创建 / 导航 / 销毁时机统一分发。
 */
interface PageScriptInstaller {
    /** WebView 创建后、首个 loadUrl 之前调用（主线程）：注册 document-start 脚本与桥。 */
    fun onWebViewCreated(tabId: String, view: WebView)

    /** onPageStarted 降级补种（仅无 document-start 支持的 WebView 需要）。 */
    fun injectFallback(view: WebView)

    /** onPageFinished 验证：运行时缺失则补种（实现需幂等，双注无害）。 */
    fun verifyInstalled(view: WebView)

    /** tab 关闭 / 崩溃 / 引擎 shutdown 时调用：移除 document-start handle 与桥。 */
    fun onWebViewDestroyed(tabId: String, view: WebView)
}
