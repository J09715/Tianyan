package top.tianyan.app.harness.agent

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.tianyan.app.core.datastore.AgentServerPreferences
import top.tianyan.app.harness.mcp.server.AgentMcpAccess
import top.tianyan.app.harness.mcp.server.HarnessToolProvider
import top.tianyan.app.harness.mcp.server.McpServerRuntime
import java.util.UUID

/**
 * MCP 被控端生命周期管理：让外部 AI 客户端（Claude Desktop / Cursor 等）通过 MCP 控制本 App。
 *
 * 与 [top.tianyan.app.harness.browser.BrowserMcpBootstrap] 的关键差异：
 *  - **独立端点**：默认 8890（顺延 8890..8899），与浏览器自环 8787 段互不干扰；
 *    `/mcp(8787)` 已被 App 自身 Agent 通过内置预设消费，被控端若复用会让模型工具列表出现
 *    与原生 `read`/`write` 重名的 `mcp__browser__*` 工具；
 *  - **持久化 token**：浏览器自环每次启动随机生成 UUID（外部客户端无法复用），
 *    被控端首次启用时生成并落盘，供外部客户端长期配置；
 *  - **偏好驱动**：本类自持协程监听 DataStore，设置页改开关/端口/令牌后即时重启，
 *    无需重启整个 App，也无需设置页反向调用。
 *
 * 工具集由 [HarnessToolProvider] 提供（只读层默认开，写入/执行层需显式开启）。
 * 启动图（含 ToolExecutor 全家桶）经 [Lazy] 延迟到首个启用信号后才展开，不拖慢启动。
 * 服务端就绪后经 [AgentMcpForegroundLauncher] 拉起常驻前台服务，防止 App 退到后台被系统冻结。
 */
class AgentMcpBootstrap(
    private val runtime: Lazy<McpServerRuntime>,
    private val toolProvider: Lazy<HarnessToolProvider>,
    private val prefs: AgentServerPreferences,
    private val foregroundLauncher: AgentMcpForegroundLauncher,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    /** 当前已生效的配置；与服务端实际绑定状态一一对应，用于跳过重复重启。 */
    @Volatile private var runningConfig: Config? = null

    /** 开始监听偏好并维持被控端状态；由 Application 启动时调用一次，幂等。 */
    fun start() {
        scope.launch {
            combine(
                prefs.enabled,
                prefs.port,
                prefs.allowRemote,
                prefs.token,
                prefs.allowWriteTools,
            ) { enabled, port, allowRemote, token, allowWriteTools ->
                Config(enabled, port, allowRemote, token, allowWriteTools)
            }.collect { apply(it) }
        }
    }

    private suspend fun apply(cfg: Config) {
        mutex.withLock {
            if (!cfg.enabled) {
                if (runningConfig != null) {
                    stopServer("已关闭")
                    foregroundLauncher.stop()
                }
                return
            }
            if (cfg.token.isBlank()) {
                // 首次启用：生成持久 token 并落盘；落盘会再次触发本方法，故本次直接返回。
                prefs.setToken(generateToken())
                return
            }
            if (cfg == runningConfig) return
            toolProvider.value.allowWriteTools = cfg.allowWriteTools
            restart(cfg)
        }
    }

    private fun restart(cfg: Config) {
        // 重配期间不动前台服务：避免"停服务→再起服务"造成通知闪烁
        stopServer("重配")
        val server = runtime.value
        val ok = server.start(
            loopbackOnly = !cfg.allowRemote,
            token = cfg.token,
            port = cfg.port,
        )
        if (ok) {
            runningConfig = cfg
            AgentMcpAccess.port = server.port
            AgentMcpAccess.running = true
            // 服务端已就绪，把进程提升为前台，避免 App 退到后台后被冻结掐断外部客户端
            foregroundLauncher.start()
            val host = if (cfg.allowRemote) "0.0.0.0" else McpServerRuntime.loopbackHost
            Log.i(TAG, "AgentMcpServer 已启动 http://$host:${server.port}/mcp（Bearer 认证已启用，写入层=${cfg.allowWriteTools}）")
        } else {
            runningConfig = null
            AgentMcpAccess.port = null
            AgentMcpAccess.running = false
            foregroundLauncher.stop()
            Log.w(TAG, "AgentMcpServer 启动失败：候选端口 ${cfg.port}..${cfg.port + 9} 均不可用")
        }
    }

    private fun stopServer(reason: String) {
        runCatching { runtime.value.stop() }
            .onFailure { Log.w(TAG, "stop($reason) 失败: ${it.message}") }
        runningConfig = null
        AgentMcpAccess.port = null
        AgentMcpAccess.running = false
        Log.i(TAG, "AgentMcpServer 已停止（$reason）")
    }

    private fun generateToken(): String = UUID.randomUUID().toString().replace("-", "")

    private data class Config(
        val enabled: Boolean,
        val port: Int,
        val allowRemote: Boolean,
        val token: String,
        val allowWriteTools: Boolean,
    )

    companion object {
        const val TAG = "TianyanAgentMcp"
    }
}
