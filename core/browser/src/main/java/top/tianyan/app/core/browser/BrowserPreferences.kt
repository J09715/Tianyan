package top.tianyan.app.core.browser

import kotlinx.serialization.Serializable

/**
 * 品牌起始页（新标签页）虚拟地址：由 WebView 层 shouldInterceptRequest 本地拦截并返回
 * assets/newtab.html，全程不出网（域名并不真实存在）。替代裸 about:blank 作为新 tab 兜底。
 */
object TianyanNewTab {
    const val URL = "https://newtab.tianyan.app/"
}

@Serializable
data class BrowserPreferences(
    val defaultFamily: String = BrowserFamily.IN_APP.name,
    val homeUrl: String = TianyanNewTab.URL,
    val coBrowsingEnabled: Boolean = true,
    val allowRemoteConnect: Boolean = false,
    val allowEvalJs: Boolean = false,
    val allowHooks: Boolean = false,
    val allowCdp: Boolean = false,
    val allowVConsole: Boolean = false,
    val desktopUserAgent: Boolean = false,
    val maxCaptureBytes: Int = 6 * 1024 * 1024
) {
    val resolvedFamily: BrowserFamily
        get() = BrowserFamily.fromRaw(defaultFamily)

    companion object {
        val DEFAULT = BrowserPreferences()
    }
}
