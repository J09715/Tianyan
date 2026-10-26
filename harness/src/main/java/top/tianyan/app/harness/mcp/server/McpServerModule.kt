package top.tianyan.app.harness.mcp.server

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import top.tianyan.app.core.browser.BrowserPreferences
import top.tianyan.app.runtime.browser.BrowserRegistry
import top.tianyan.app.runtime.browser.tools.BrowserMcpResources
import top.tianyan.app.runtime.browser.tools.BrowserMcpTools
import top.tianyan.app.core.browser.TianyanNewTab
import top.tianyan.app.core.datastore.BrowserPreferences as DataStoreBrowserPreferences

/**
 * 装配进程内 MCP Server 相关的 Koin 注入。
 *
 * - [BrowserMcpTools] / [BrowserMcpResources] 接受 `List<BrowserEngine>` 等运行时参数，Koin 无法自动注入，
 *   由本模块集中构造，确保 [McpServerRuntime] 在容器内即可装配。
 */
object McpServerModule {

    fun provideBrowserMcpResources(registry: BrowserRegistry): BrowserMcpResources =
        BrowserMcpResources(engineProvider = { runCatching { registry.getDefault() }.getOrNull() })

    fun provideBrowserMcpTools(
        registry: BrowserRegistry,
        browserPrefs: DataStoreBrowserPreferences,
    ): BrowserMcpTools {
        // #14：启动时把 datastore 里的真实用户偏好映射为工具层快照。
        // 本 Provider 经 BrowserMcpBootstrap 的 Kotlin Lazy 延迟到首个 IO 协程内才构造，
        // 此处 runBlocking 只阻塞该协程（单次 DataStore first() 毫秒级），不再卡主线程；
        // 读取失败兜底 DEFAULT，不阻塞启动。
        val prefs = runCatching {
            runBlocking {
                BrowserPreferences(
                    defaultFamily = browserPrefs.defaultFamily().first(),
                    homeUrl = browserPrefs.homeUrl().first().ifBlank { TianyanNewTab.URL },
                    coBrowsingEnabled = browserPrefs.coBrowsingEnabled().first(),
                    allowRemoteConnect = browserPrefs.allowRemoteConnect().first(),
                    allowEvalJs = browserPrefs.allowEvalJs().first(),
                    allowHooks = browserPrefs.allowHooks().first(),
                    allowCdp = browserPrefs.allowCdp().first(),
                    allowVConsole = browserPrefs.allowVConsole().first(),
                    desktopUserAgent = browserPrefs.desktopUserAgent().first(),
                    maxCaptureBytes = browserPrefs.maxCaptureBytes().first(),
                )
            }
        }.getOrDefault(BrowserPreferences.DEFAULT)
        return BrowserMcpTools(
            // 引擎不在构造期快照（本构造可能早于 BrowserMcpBootstrap 注册引擎）：
            // engineSelector 在每次工具调用时实时查 registry，先按 family 精确匹配，
            // 再退回任一健康引擎，注册时序不再影响可用性。
            engines = emptyList(),
            engineSelector = { token ->
                registry.get(token.family)
                    ?: registry.list().firstNotNullOfOrNull { registry.get(it.family) }
            },
            prefs = prefs,
        )
    }
}
