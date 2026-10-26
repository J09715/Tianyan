package top.tianyan.app.runtime.browser.inject

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.concurrent.ConcurrentHashMap

/**
 * vConsole 调试面板注入器（allowVConsole 门禁，独立于 allowHooks）：
 * 为每个 tab 的 WebView 注册 document-start 脚本（vconsole.min.js + [VConsoleInstaller.BOOTSTRAP]），
 * 在页面上挂出浮动调试面板（console / network / element / storage），供人工浏览时排查；
 * Agent 侧的 console 捕获 / 网络时间线走事件总线，与本面板互不影响。
 *
 * 与 hook 运行时同一套生命周期契约（[PageScriptInstaller]），线程约定一致：
 * [onWebViewCreated] 必须主线程调用，[onWebViewDestroyed] 内部保证主线程执行。
 * 幂等性：BOOTSTRAP 的 window.__tianyanVConsole 守卫使降级补种 / 验证补种双注无害。
 */
class VConsoleInstaller(context: Context) : PageScriptInstaller {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val docStartHandles = ConcurrentHashMap<String, ScriptHandler>()

    /** document-start 全量脚本 = vConsole 库 + 引导 IIFE（合并单次注册）。internal 供单测校验资产完整性。 */
    internal val fullScript: String by lazy {
        appContext.assets.open("vconsole.min.js").bufferedReader().use { it.readText() } +
            "\n" + BOOTSTRAP
    }

    override fun onWebViewCreated(tabId: String, view: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching {
                docStartHandles[tabId] =
                    WebViewCompat.addDocumentStartJavaScript(view, fullScript, setOf("*"))
            }
        }
    }

    override fun onWebViewDestroyed(tabId: String, view: WebView) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            docStartHandles.remove(tabId)?.let { runCatching { it.remove() } }
        } else {
            mainHandler.post {
                docStartHandles.remove(tabId)?.let { runCatching { it.remove() } }
            }
        }
    }

    override fun injectFallback(view: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        runCatching { view.evaluateJavascript(fullScript, null) }
    }

    override fun verifyInstalled(view: WebView) {
        runCatching {
            view.evaluateJavascript("!!window.__tianyanVConsole") { present ->
                if (present != "true") view.evaluateJavascript(fullScript, null)
            }
        }
    }

    companion object {
        /**
         * 引导 IIFE：幂等守卫 + 等 DOM 就绪再 new VConsole()。
         * document-start 时 document.body 尚不存在，readyState=loading 时挂 DOMContentLoaded（once）；
         * 降级路径（onPageStarted 注入）readyState 已过 loading，直接 boot。
         * 页面环境异常时静默放弃，不得影响页面本身。
         */
        val BOOTSTRAP: String = """
            (function(){
              if (window.__tianyanVConsole) return;
              window.__tianyanVConsole = true;
              var boot = function() {
                try {
                  if (!window.__tianyanVConsoleInstance) window.__tianyanVConsoleInstance = new VConsole();
                } catch (e) {}
              };
              if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', boot, { once: true });
              } else {
                boot();
              }
            })();
        """.trimIndent()
    }
}
