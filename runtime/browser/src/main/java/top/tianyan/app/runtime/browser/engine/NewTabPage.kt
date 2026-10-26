package top.tianyan.app.runtime.browser.engine

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import top.tianyan.app.core.browser.TianyanNewTab

/**
 * 品牌起始页（新标签页）本地拦截：WebView 请求 [TianyanNewTab.URL] 虚拟地址时，
 * 直接从 assets 返回 newtab.html，全程不出网（该域名并不真实存在）。
 *
 * host 精确匹配、path/query 任意（未来页面内可加子资源）；
 * assets 缺失时返回 404 纯文本兜底，避免 WebView 撞向真实 DNS 解析失败。
 * [WebViewClients.shouldInterceptRequest] 在 IO 线程回调，assets 读取线程安全。
 */
object NewTabPage {

    private const val HOST = "newtab.tianyan.app"

    fun matches(uri: Uri): Boolean =
        uri.scheme?.lowercase() == "https" && uri.host?.lowercase() == HOST

    /** 非起始页地址返回 null，走 WebView 默认加载流程。 */
    fun intercept(context: Context, uri: Uri): WebResourceResponse? {
        if (!matches(uri)) return null
        val body = runCatching { context.assets.open("newtab.html") }.getOrNull()
        return if (body != null) {
            WebResourceResponse("text/html", "utf-8", body)
        } else {
            WebResourceResponse(
                "text/plain", "utf-8", 404, "Not Found",
                emptyMap(), "newtab asset missing".byteInputStream(),
            )
        }
    }
}
