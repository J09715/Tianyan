package top.tianyan.app.runtime.browser.hook

import android.webkit.JavascriptInterface

/**
 * 页内 hook runtime 与宿主之间的原生桥（每 tab 一个实例，绑定 tabId 免查表路由）。
 *
 * 安全设计（刻意只暴露 2 个 string-in/out 方法，无任何执行能力）：
 * - 每个 tab 持有启动时生成的一次性 nonce，只经**主帧** document-start/onPageStarted 注入，
 *   跨源 iframe 读不到 nonce → 无法伪造 [onEvent]，也拿不到 [getRules] 的规则 payload；
 * - [onEvent] 还校验上报方声明的 origin 必须等于该 tab 当前主帧 origin，且 top 必须为 true，
 *   避免导航后残留脚本/子帧事件污染模型上下文；
 * - [onEvent]：页内异步上报，内部 trySend 有界 Channel 立即返回，绝不阻塞 JavaBridge 线程；
 * - [getRules]：同步返回缓存好的规则 payload JSON —— fetch/XHR wrapper 页内同步决策
 *   block/mock 的关键（异步回调式桥做不到这一点，这也是不采用第三方 JsBridge 库的原因）。
 */
class TianyanHookBridge(
    private val tabId: String,
    private val pipeline: HookEventPipeline,
    private val nonce: String,
    private val mainFrameOrigin: () -> String?,
) {

    @JavascriptInterface
    fun onEvent(nonce: String?, origin: String?, top: Boolean, json: String?) {
        if (nonce != this.nonce || !top) return
        if (origin.isNullOrBlank() || origin != mainFrameOrigin()) return
        if (!json.isNullOrEmpty()) pipeline.enqueue(tabId, json)
    }

    @JavascriptInterface
    fun getRules(nonce: String?): String =
        if (nonce == this.nonce) pipeline.rulesPayloadFor(tabId) else EMPTY_RULES

    private companion object {
        /** 规则未通过 nonce 校验时返回最小空 payload，避免泄露用户配置的 hook 规则。 */
        const val EMPTY_RULES = """{"config":{"maxBodyBytes":0},"rules":[],"scripts":[]}"""
    }
}
