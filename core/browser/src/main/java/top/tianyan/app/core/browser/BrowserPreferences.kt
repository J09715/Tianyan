package top.tianyan.app.core.browser

import kotlinx.serialization.Serializable

/**
 * 内置浏览器起始页。指向 runtime/browser 模块 assets 下的 home.html，
 * 经 WebView 的 file:///android_asset 协议加载（不受 allowFileAccess=false 影响）。
 */
const val BROWSER_HOME_URL: String = "file:///android_asset/home.html"

@Serializable
data class BrowserPreferences(
    val defaultFamily: String = BrowserFamily.IN_APP.name,
    val homeUrl: String = BROWSER_HOME_URL,
    val coBrowsingEnabled: Boolean = true,
    val allowRemoteConnect: Boolean = false,
    val allowEvalJs: Boolean = false,
    val allowHooks: Boolean = false,
    val allowCdp: Boolean = false,
    val desktopUserAgent: Boolean = false,
    val maxCaptureBytes: Int = 6 * 1024 * 1024
) {
    val resolvedFamily: BrowserFamily
        get() = BrowserFamily.fromRaw(defaultFamily)

    companion object {
        val DEFAULT = BrowserPreferences()
    }
}
