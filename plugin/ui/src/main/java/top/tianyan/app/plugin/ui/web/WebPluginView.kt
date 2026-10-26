package top.tianyan.app.plugin.ui.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.*
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.tianyan.app.plugin.runtime.PluginCapability
import top.tianyan.app.plugin.runtime.PluginCapabilityResult
import top.tianyan.app.plugin.runtime.PluginCapabilitySession

/** Dedicated WebView; it never joins the browser pool or obtains a HostBridge credential. */
@SuppressLint("SetJavaScriptEnabled")
internal class WebPluginView(
    context: Context,
    private val policy: WebPluginPolicy,
    private val session: PluginCapabilitySession,
    private val scope: CoroutineScope,
    private val pluginResources: WebPluginResources,
    private val bridge: WebPluginStreamBridge,
    private val streamCapabilities: Set<PluginCapability>,
) : WebView(context) {
    private var disposed = false
    private var initialNavigation = true
    private var ready = false

    init {
        check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            databaseEnabled = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            blockNetworkLoads = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            setGeolocationEnabled(false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        setDownloadListener { _, _, _, _, _ -> /* Downloads require a separate future capability. */ }
        webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                result.cancel()
                return true
            }
            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                result.cancel()
                return true
            }
            override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String?, result: JsPromptResult): Boolean {
                result.cancel()
                return true
            }
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) =
                callback.invoke(origin, false, false)
            override fun onShowFileChooser(view: WebView, callback: android.webkit.ValueCallback<Array<android.net.Uri>>, params: FileChooserParams): Boolean {
                callback.onReceiveValue(null)
                return true
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
            @Deprecated("Legacy WebView callback")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                pluginResources.response(request.url.toString(), request.method)
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                // Any reload or second document invalidates pending prompts and prior grants.
                if (!initialNavigation || url == null || !policy.canNavigate(url)) {
                    ready = false
                    session.close()
                    view.stopLoading()
                } else ready = true
                initialNavigation = false
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                dispose()
                return true
            }
        }
        WebViewCompat.addWebMessageListener(this, "tianyanPlugin", setOf(policy.origin)) { _, message, origin, mainFrame, reply ->
            if (disposed || !ready || !mainFrame || origin.toString() != policy.origin) return@addWebMessageListener
            if (message.type != WebMessageCompat.TYPE_STRING) return@addWebMessageListener
            val call = runCatching { WebPluginMessages.decode(message.data.orEmpty()) }.getOrNull()
            if (call == null) {
                reply.postMessage(WebPluginMessages.encode(PluginCapabilityResult("", false, "invalid_request")))
            } else scope.launch {
                val streaming = call.capability in streamCapabilities
                // A stream pushes chunks under the same request id; the reply must be bound before the
                // executor runs, and a document that already streams cannot bind a second sender.
                if (streaming && !bridge.attach(call.id) { envelope ->
                        if (disposed || !ready) false else runCatching { reply.postMessage(envelope) }.isSuccess
                    }) {
                    reply.postMessage(WebPluginMessages.encode(PluginCapabilityResult(call.id, false, "busy")))
                    return@launch
                }
                val result = try {
                    session.call(call, origin.toString(), mainFrame)
                } catch (_: CancellationException) {
                    PluginCapabilityResult(call.id, false, "revoked")
                } finally {
                    if (streaming) bridge.detach()
                }
                if (!disposed && ready) reply.postMessage(WebPluginMessages.encode(result))
            }
        }
        loadUrl("${policy.origin}/index.html")
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        ready = false
        bridge.close()
        session.close()
        WebViewCompat.removeWebMessageListener(this, "tianyanPlugin")
        stopLoading()
        (parent as? android.view.ViewGroup)?.removeView(this)
        removeAllViews()
        destroy()
    }
}
