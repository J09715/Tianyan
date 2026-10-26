package top.tianyan.app.harness.browser

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import top.tianyan.app.harness.mcp.server.BuiltinBrowserMcpAccess
import top.tianyan.app.harness.mcp.server.McpServerRuntime
import top.tianyan.app.runtime.browser.BrowserRegistry
import top.tianyan.app.runtime.browser.BrowserRegistryImpl
import top.tianyan.app.runtime.browser.engine.AndroidInAppBrowserEngine
import top.tianyan.app.runtime.browser.engine.WebViewTabPool
import top.tianyan.app.runtime.browser.cdp.WebViewDebugging
import top.tianyan.app.core.browser.BrowserFamily
import java.util.UUID
import kotlinx.coroutines.CancellationException
import top.tianyan.app.core.browser.BrowserPreferences
import top.tianyan.app.core.browser.TianyanNewTab
import top.tianyan.app.core.datastore.BrowserPreferences as DataStoreBrowserPreferences

/**
 * 把 in-process 浏览器 MCP server 注入到 harness 流水线，并负责：
 *  - 在 ApplicationContext 上拉起 [AndroidInAppBrowserEngine] 注册到 [BrowserRegistry];
 *  - 按用户偏好（allowRemoteConnect / desktopUserAgent）在 loopback 或 0.0.0.0 端口上启动 [McpServerRuntime];
 *  - 无论 loopback 还是外接都生成 Bearer Token（[top.tianyan.app.harness.mcp.server.McpAuthFilter] 恒强制校验），
 *    并写入 [BuiltinBrowserMcpAccess]（token + 实际端口）供自环客户端使用。
 *
 * 调用时机：Application.onCreate 后由 AppScope 协程调用一次 [bootstrap]。
 * [McpServerRuntime] 经 [Lazy] 注入：其构造图（含 BrowserMcpTools 的 DataStore 快照读取）
 * 推迟到 bootstrap() 的 IO 协程内才展开，不阻塞主线程。
 */
class BrowserMcpBootstrap(
    private val context: Context,
    private val runtime: Lazy<McpServerRuntime>,
    private val registry: BrowserRegistry,
    private val browserPrefs: DataStoreBrowserPreferences,
) {
    /** 注册引擎并启动 HTTP server；幂等。按用户偏好（#4）决定绑定面：allowRemote 时绑定 0.0.0.0。 */
    suspend fun bootstrap(): Boolean {
        val server = runtime.value
        if (server.isRunning) return true
        val regImpl = registry as? BrowserRegistryImpl ?: return false
        val prefs = readPrefs()
        if (registry.get(BrowserFamily.IN_APP) == null) {
            // Application 启动任务中先在主线程应用偏好，再公布引擎，首个 tab 不会抢跑。
            // 开启失败不阻断普通浏览；错误已留日志，debug_attach 会再次尝试并透传原因。
            try {
                WebViewDebugging.setEnabled(prefs.allowCdp)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "初始化 WebView 调试开关失败", e)
            }
            // hooksEnabled/cdpEnabled/vConsoleEnabled 与 desktopUserAgent 一样：池级开关，切换需重启（或新引擎注册）才生效
            val pool = WebViewTabPool(
                context, registry.eventBus,
                desktopUserAgent = prefs.desktopUserAgent,
                hooksEnabled = prefs.allowHooks,
                cdpEnabled = prefs.allowCdp,
                vConsoleEnabled = prefs.allowVConsole,
                maxCaptureBytes = prefs.maxCaptureBytes.toLong(),
            )
            val engine = AndroidInAppBrowserEngine(context, registry.eventBus, pool)
            regImpl.registerEngine(engine)
        }
        val allowRemote = prefs.allowRemoteConnect
        // loopback 也必须带 token：Android 回环地址不按 UID 隔离，认证恒开启
        val token = generateToken()
        BuiltinBrowserMcpAccess.token = token
        // 首选端口被占用时 start 内部会自动顺延尝试相邻端口，自环客户端经 BuiltinBrowserMcpAccess 感知实际端口
        val ok = server.start(loopbackOnly = !allowRemote, token = token, port = McpServerRuntime.defaultPort)
        if (ok) {
            // 自环访问点由本类负责落盘（运行时类已解耦）：首选端口被占用顺延后，
            // 自环客户端须以实际绑定端口为准替换静态预设 URL 中的端口。
            BuiltinBrowserMcpAccess.port = server.port
            val host = if (allowRemote) "0.0.0.0" else McpServerRuntime.loopbackHost
            Log.i(TAG, "BrowserMcpServer 已启动 http://$host:${server.port}/mcp（Bearer 认证已启用）")
        } else {
            BuiltinBrowserMcpAccess.port = null
            Log.w(TAG, "BrowserMcpServer 启动失败：候选端口 ${McpServerRuntime.defaultPort}..${McpServerRuntime.defaultPort + 9} 均不可用")
        }
        return ok
    }

    /** 启动时读一次真实偏好（IO 协程内调用，单次 DataStore first() 毫秒级；失败兜底 DEFAULT）。 */
    private fun readPrefs(): BrowserPreferences = runCatching {
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

    private fun generateToken(): String = UUID.randomUUID().toString().replace("-", "")

    fun stop() {
        try {
            runtime.value.stop()
            BuiltinBrowserMcpAccess.token = null
            BuiltinBrowserMcpAccess.port = null
        } catch (t: Throwable) { Log.w(TAG, "stop: ${t.message}") }
    }

    companion object {
        const val TAG = "TianyanMcpBootstrap"
    }
}
