package top.tianyan.app.runtime.browser.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonPrimitive
import top.tianyan.app.runtime.browser.inject.PageScriptInstaller

/**
 * 每引擎一个安装器：为每个 tab 的 WebView 装 `TianyanBridge`（addJavascriptInterface）
 * 与 document-start 脚本（[WebViewCompat.addDocumentStartJavaScript]，WebView 105+）。
 *
 * 线程约定：所有方法都必须在主线程调用（[WebViewTabPool] 的 create / close 均在主线程上下文）。
 * 降级路径：document-start 不可用、或页面是新 origin 时由 [injectFallback]（onPageStarted）
 * 在主帧补种；[verifyInstalled]（onPageFinished）验证；IIFE 幂等守卫使双注无害。
 *
 * 安全收口（审计 P1-4）：
 * - document-start 不再用 `setOf("*")` 注入所有 frame，只对本 tab **访问过的顶层 origin** 生效；
 * - 首帧一律经 [injectFallback] 的 evaluateJavascript 在**主帧**执行；脚本携带 per-tab nonce，
 *   跨源 iframe 读不到 nonce，既不能伪造事件，也拿不到 hook 规则；
 * - 每次导航记录主帧 origin，[TianyanHookBridge] 用它校验事件来源。
 */
class HookInstaller(
    context: Context,
    private val pipeline: HookEventPipeline,
    private val store: HookRuleStore,
) : PageScriptInstaller {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val docStartHandles = ConcurrentHashMap<String, ScriptHandler>()
    private val nonceByTab = ConcurrentHashMap<String, String>()
    private val tabByView = ConcurrentHashMap<WebView, String>()
    private val mainFrameOrigins = ConcurrentHashMap<String, String>()
    private val allowedOrigins = ConcurrentHashMap<String, MutableSet<String>>()

    /** 懒加载并缓存 assets/hook_runtime.js。 */
    val runtimeScript: String by lazy {
        appContext.assets.open("hook_runtime.js").bufferedReader().use { it.readText() }
    }

    /** WebView 创建后、loadUrl 之前调用（主线程）。 */
    override fun onWebViewCreated(tabId: String, view: WebView) {
        val nonce = UUID.randomUUID().toString()
        nonceByTab[tabId] = nonce
        tabByView[view] = tabId
        view.addJavascriptInterface(
            TianyanHookBridge(tabId, pipeline, nonce) { mainFrameOrigins[tabId] },
            "TianyanBridge",
        )
        // WebView 创建时还没有目标 origin：document-start 推迟到首次 onPageStarted 再登记。
    }

    /** tab 关闭 / 崩溃 / 引擎 shutdown 时调用（内部保证主线程执行）：移除 document-start handle 与桥。 */
    override fun onWebViewDestroyed(tabId: String, view: WebView) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            doDestroy(tabId, view)
        } else {
            mainHandler.post { doDestroy(tabId, view) }
        }
    }

    private fun doDestroy(tabId: String, view: WebView) {
        docStartHandles.remove(tabId)?.let { ref -> runCatching { ref.remove() } }
        nonceByTab.remove(tabId)
        mainFrameOrigins.remove(tabId)
        allowedOrigins.remove(tabId)
        tabByView.remove(view)
        runCatching { view.removeJavascriptInterface("TianyanBridge") }
    }

    /**
     * onPageStarted：主帧注入入口。
     *
     * 每次导航都执行（幂等）：记录当前主帧 origin、重新登记该 tab 已访问 origin 的
     * document-start 规则（供后续导航提前注入），并用 nonce 包裹脚本在主帧再注入一次，
     * 保证首个 origin / 无 document-start 支持的 WebView 也能工作。
     */
    override fun injectFallback(view: WebView) {
        val tabId = tabByView[view] ?: return
        val nonce = nonceByTab[tabId] ?: return
        val origin = originOf(view.url)
        if (origin != null) {
            mainFrameOrigins[tabId] = origin
            val origins = allowedOrigins.getOrPut(tabId) { ConcurrentHashMap.newKeySet() }
            if (origins.size < MAX_TRACKED_ORIGINS && origins.add(origin)) {
                registerDocumentStart(tabId, view, nonce, origins)
            }
        }
        runCatching { view.evaluateJavascript(wrappedScript(nonce), null) }
    }

    /** onPageFinished：验证 runtime 存在，缺失则补种（幂等）。 */
    override fun verifyInstalled(view: WebView) {
        val tabId = tabByView[view] ?: return
        val nonce = nonceByTab[tabId] ?: return
        runCatching {
            view.evaluateJavascript("!!window.__tianyanHooks") { present ->
                if (present != "true") view.evaluateJavascript(wrappedScript(nonce), null)
            }
        }
    }

    /** 规则变更后推送到已加载页面（主线程）。payload 嵌入用 JSON 字符串字面量（= 合法 JS 字面量）。 */
    fun pushRules(tabId: String, view: WebView) {
        val payload = store.payloadFor(tabId)
        val literal = JsonPrimitive(payload).toString()
        runCatching {
            view.evaluateJavascript(
                "window.__tianyanApplyRules&&window.__tianyanApplyRules($literal)", null
            )
        }
    }

    /** document-start 只对这些顶层 origin 生效；未知 origin 走 [injectFallback] 的主帧注入。 */
    private fun registerDocumentStart(
        tabId: String,
        view: WebView,
        nonce: String,
        origins: Set<String>,
    ) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        runCatching {
            docStartHandles.remove(tabId)?.let { it.remove() }
            docStartHandles[tabId] = WebViewCompat.addDocumentStartJavaScript(
                view,
                "if(window.top===window.self){window.__tianyanBridgeNonce=${JsonPrimitive(nonce)};}",
                origins,
            )
        }
    }

    private fun wrappedScript(nonce: String): String =
        "if(window.top===window.self){window.__tianyanBridgeNonce=${JsonPrimitive(nonce)};}\n$runtimeScript"

    private fun originOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return runCatching {
            val uri = android.net.Uri.parse(url)
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host?.lowercase() ?: return null
            "$scheme://$host" + if (uri.port > 0) ":$uri.port" else ""
        }.getOrNull()
    }

    private companion object {
        /** 单 tab 记住的 origin 上限：防止长时间浏览把集合撑成无界内存。 */
        const val MAX_TRACKED_ORIGINS = 16
    }
}
