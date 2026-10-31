package top.tianyan.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.database.AiModelEntity
import top.tianyan.app.core.database.AiModelRepository
import top.tianyan.app.core.database.ToolSettingsRepository
import top.tianyan.app.core.model.CcAgentState
import top.tianyan.app.core.model.CcAgentType
import top.tianyan.app.core.model.CcProviderProfile
import top.tianyan.app.core.model.CcSwitchDaemonStatus
import top.tianyan.app.core.network.CcSwitchClient
import top.tianyan.app.core.model.RuntimeName
import top.tianyan.app.core.model.RuntimeRequirement
import top.tianyan.app.core.tools.DependencyManager
import top.tianyan.app.core.tools.ProviderRepository
import top.tianyan.app.core.tools.ToolManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.update
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.shell.ShellCommand
import java.net.Inet4Address
import java.net.NetworkInterface
import top.tianyan.app.core.common.result.AppResult

data class CcSwitchUiState(
    val isInstalled: Boolean = true,
    val isDaemonRunning: Boolean = false,
    val isOperating: Boolean = false,
    val isCheckingEnvironment: Boolean = false,
    val installingAgentType: CcAgentType? = null,
    val autoStartEnabled: Boolean = false,
    val servicePort: Int = 19870,
    val daemonStatus: CcSwitchDaemonStatus? = null,
    val agents: List<CcAgentState> = defaultAgents(),
    val providers: List<CcProviderProfile> = emptyList(),
    val tianyanModels: List<AiModelEntity> = emptyList(),
    val deviceLanIp: String? = null,
    val webUsername: String = "admin",
    val webPassword: String = "admin123",
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val switchingAgent: CcAgentState? = null,
    val upgradingAgent: CcAgentState? = null,
    val installLogs: List<String> = emptyList(),
    val showLogDialog: Boolean = false,
    val logAgentTitle: String = "安装日志",
) {
    val loopbackUrl: String get() = "http://127.0.0.1:$servicePort"
    val lanUrl: String? get() = deviceLanIp?.let { "http://$it:$servicePort" }
    val preferredWebUrl: String get() = lanUrl ?: loopbackUrl

    companion object {
        fun defaultAgents(): List<CcAgentState> = CcAgentType.entries.map { type ->
            CcAgentState(
                type = type,
                installed = false,
                currentVersion = null,
                latestVersion = null,
                activeProviderName = null,
                isChecking = false,
            )
        }
    }
}

class CcSwitchViewModel(
    private val toolManager: ToolManager,
    private val ccSwitchClient: CcSwitchClient,
    private val aiModelDao: AiModelRepository,
    private val providerRepository: ProviderRepository,
    private val toolSettingsRepository: ToolSettingsRepository,
    private val linuxRuntime: LinuxRuntime,
    private val dependencyManager: DependencyManager,
    private val logger: AppLogger,
    private val localAccess: top.tianyan.app.runtime.environment.LocalBackendAccess? = null,
) : ViewModel() {

    private val toolId = "cc-switch"
    private val _isOperating = MutableStateFlow(false)
    private val _isDaemonRunning = MutableStateFlow(false)
    private val _isCheckingEnvironment = MutableStateFlow(false)
    private val _installingAgentType = MutableStateFlow<CcAgentType?>(null)
    private val _daemonStatus = MutableStateFlow<CcSwitchDaemonStatus?>(null)
    private val _agents = MutableStateFlow<List<CcAgentState>>(CcSwitchUiState.defaultAgents())
    private val _providers = MutableStateFlow<List<CcProviderProfile>>(emptyList())
    private val _deviceLanIp = MutableStateFlow<String?>(null)
    private val _webUsername = MutableStateFlow("admin")
    private val _webPassword = MutableStateFlow("admin123")
    private val _errorMessage = MutableStateFlow<String?>(null)
    private val _successMessage = MutableStateFlow<String?>(null)
    private val _switchingAgent = MutableStateFlow<CcAgentState?>(null)
    private val _upgradingAgent = MutableStateFlow<CcAgentState?>(null)
    private val _installLogs = MutableStateFlow<List<String>>(emptyList())
    private val _showLogDialog = MutableStateFlow(false)
    private val _logAgentTitle = MutableStateFlow("Agent 安装日志")

    val uiState: StateFlow<CcSwitchUiState> = combine(
        linuxRuntime.activeDistroId,
        _isOperating,
        _isDaemonRunning,
        _isCheckingEnvironment,
        _installingAgentType,
        _daemonStatus,
        _agents,
        _providers,
        aiModelDao.observeAll(),
        _deviceLanIp,
        _webUsername,
        _webPassword,
        _errorMessage,
        _successMessage,
        _switchingAgent,
        _upgradingAgent,
        _installLogs,
        _showLogDialog,
        _logAgentTitle,
    ) { values ->
        val distroId = values[0] as String
        val operating = values[1] as Boolean
        val daemonRunning = values[2] as Boolean
        val checkingEnv = values[3] as Boolean
        val installingAgent = values[4] as CcAgentType?
        val daemonStatus = values[5] as CcSwitchDaemonStatus?
        @Suppress("UNCHECKED_CAST")
        val agents = values[6] as List<CcAgentState>
        @Suppress("UNCHECKED_CAST")
        val providers = values[7] as List<CcProviderProfile>
        @Suppress("UNCHECKED_CAST")
        val models = values[8] as List<AiModelEntity>
        val lanIp = values[9] as String?
        val username = values[10] as String
        val password = values[11] as String
        val err = values[12] as String?
        val success = values[13] as String?
        val switching = values[14] as CcAgentState?
        val upgrading = values[15] as CcAgentState?
        @Suppress("UNCHECKED_CAST")
        val logs = values[16] as List<String>
        val showDialog = values[17] as Boolean
        val logTitle = values[18] as String

        CcSwitchUiState(
            isInstalled = true,
            isDaemonRunning = daemonRunning,
            isOperating = operating,
            isCheckingEnvironment = checkingEnv,
            installingAgentType = installingAgent,
            autoStartEnabled = false,
            daemonStatus = daemonStatus,
            agents = agents,
            providers = providers,
            tianyanModels = models,
            deviceLanIp = lanIp,
            webUsername = username,
            webPassword = password,
            errorMessage = err,
            successMessage = success,
            switchingAgent = switching,
            upgradingAgent = upgrading,
            installLogs = logs,
            showLogDialog = showDialog,
            logAgentTitle = logTitle,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CcSwitchUiState())

    init {
        refreshStatus()
        startPolling()
    }

    private fun startPolling() {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(3500)
                try {
                    val running = toolManager.isGatewayRunning(toolId)
                    val lanIp = detectLanIp()
                    val wasRunning = _isDaemonRunning.value
                    withContext(Dispatchers.Main.immediate) {
                        _isDaemonRunning.value = running
                        _deviceLanIp.value = lanIp
                        if (!running && wasRunning) {
                            _daemonStatus.value = null
                        }
                    }
                    if (running && !wasRunning) {
                        fetchDaemonData()
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    fun refreshStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val lanIp = detectLanIp()
            val running = toolManager.isGatewayRunning(toolId)
            withContext(Dispatchers.Main.immediate) {
                _isDaemonRunning.value = running
                _deviceLanIp.value = lanIp
                if (!running) {
                    _daemonStatus.value = null
                }
            }
            fetchDaemonData()
        }
    }

    fun refreshEnvironment() {
        viewModelScope.launch(Dispatchers.IO) {
            fetchDaemonData()
        }
    }

    private suspend fun fetchDaemonData() {
        withContext(Dispatchers.Main.immediate) {
            _isCheckingEnvironment.value = true
            _agents.value = _agents.value.map { it.copy(isChecking = true) }
        }
        try {
            val isRunning = toolManager.isGatewayRunning(toolId)
            val creds = readCcSwitchWebCredentials(linuxRuntime)
            val statusRes = if (isRunning) ccSwitchClient.getDaemonStatus(creds.first, creds.second) else Result.failure(IllegalStateException("中枢未运行"))
            val snapshotRes = if (isRunning) ccSwitchClient.getProviderSnapshot(creds.first, creds.second) else Result.failure(IllegalStateException("中枢未运行"))
            val snapshot = snapshotRes.getOrNull()
            val sandboxMap = inspectSandboxAgents()

            val mergedAgents = CcAgentType.entries.map { type ->
                val sandboxVer = sandboxMap[type.defaultExecutable.lowercase()]
                val currentProviderId = type.serverAppId?.let { snapshot?.currentProviderByApp?.get(it) }
                val currentProviderName = currentProviderId?.let { pid ->
                    snapshot?.providers?.firstOrNull { it.id == pid }?.name
                }

                CcAgentState(
                    type = type,
                    installed = sandboxVer != null,
                    currentVersion = sandboxVer,
                    activeProviderId = currentProviderId,
                    activeProviderName = currentProviderName,
                    isChecking = false,
                    errorNotice = if (sandboxVer == null) "not installed or not executable" else null,
                    description = type.displayName,
                )
            }

            withContext(Dispatchers.Main.immediate) {
                if (isRunning) {
                    val failure = statusRes.exceptionOrNull() ?: snapshotRes.exceptionOrNull()
                    statusRes.onSuccess { _daemonStatus.value = it }
                    snapshotRes.onSuccess { _providers.value = it.providers }
                    _errorMessage.value = failure?.let { "读取中枢数据失败：${it.message}" }
                } else {
                    _daemonStatus.value = null
                }
                _agents.value = mergedAgents
                _webUsername.value = creds.first
                _webPassword.value = creds.second
            }
        } finally {
            withContext(Dispatchers.Main.immediate) {
                _isCheckingEnvironment.value = false
                _agents.value = _agents.value.map { it.copy(isChecking = false) }
            }
        }
    }

    fun resetWebPassword(newPassword: String = "admin123") {
        viewModelScope.launch(Dispatchers.IO) {
            val cmd = """
                PASS_FILE="${'$'}{HOME:-/root}/.cc-switch/web_password"
                mkdir -p "${'$'}{HOME:-/root}/.cc-switch" 2>/dev/null || true
                printf '%s' "$newPassword" > "${'$'}PASS_FILE" 2>/dev/null || true
                chmod 600 "${'$'}PASS_FILE" 2>/dev/null || true
            """.trimIndent()
            linuxRuntime.execute(ShellCommand(commandLine = cmd, timeoutMs = 5000L))
            _webPassword.value = newPassword
            withContext(Dispatchers.Main.immediate) {
                _successMessage.value = "Web 访问密码已重置为：$newPassword（重启中枢生效）"
            }
        }
    }

    private suspend fun inspectSandboxAgents(): Map<String, String?> {
        return withContext(Dispatchers.IO) {
            try {
                val checkScript = """
                    export PATH="/opt/tianyan/bin:/usr/local/bin:/root/.local/bin:${'$'}PATH"

                    find_and_shim_node_agent() {
                        cmd="${'$'}1"
                        pkg_name="${'$'}2"

                        pkg_dir=""
                        NPM_PREFIX="${'$'}(npm config get prefix 2>/dev/null || true)"
                        for nmdir in \
                            /opt/tianyan/runtimes/node/*/lib/node_modules \
                            "${'$'}NPM_PREFIX/lib/node_modules" \
                            /usr/local/lib/node_modules \
                            /usr/lib/node_modules \
                            /root/.local/lib/node_modules; do
                            if [ -d "${'$'}nmdir/${'$'}pkg_name" ]; then
                                pkg_dir="${'$'}nmdir/${'$'}pkg_name"
                                break
                            fi
                        done

                        ver=""
                        entry_file=""
                        if [ -n "${'$'}pkg_dir" ] && [ -f "${'$'}pkg_dir/package.json" ]; then
                            ver="${'$'}(grep -m 1 '"version"' "${'$'}pkg_dir/package.json" 2>/dev/null | grep -oE '[0-9]+(\.[0-9]+)+' | head -n 1)"

                            for candidate in \
                                "${'$'}pkg_dir/cli.js" \
                                "${'$'}pkg_dir/bin/${'$'}cmd" \
                                "${'$'}pkg_dir/bin/${'$'}cmd.js" \
                                "${'$'}pkg_dir/dist/cli.js" \
                                "${'$'}pkg_dir/index.js"; do
                                if [ -f "${'$'}candidate" ]; then
                                    entry_file="${'$'}candidate"
                                    break
                                fi
                            done

                            if [ -z "${'$'}entry_file" ]; then
                                bin_rel="${'$'}(grep -A 5 '"bin"' "${'$'}pkg_dir/package.json" 2>/dev/null | grep -oE '"[^"]+\.js"' | tr -d '"' | head -n 1)"
                                if [ -n "${'$'}bin_rel" ] && [ -f "${'$'}pkg_dir/${'$'}bin_rel" ]; then
                                    entry_file="${'$'}pkg_dir/${'$'}bin_rel"
                                fi
                            fi
                        fi

                        if [ -n "${'$'}entry_file" ]; then
                            mkdir -p /opt/tianyan/bin /usr/local/bin 2>/dev/null || true
                            rm -f "/opt/tianyan/bin/${'$'}cmd" "/usr/local/bin/${'$'}cmd" 2>/dev/null || true
                            cat << 'EOF' > "/opt/tianyan/bin/${'$'}cmd"
#!/bin/sh
export PATH="/opt/tianyan/bin:/usr/local/bin:/root/.local/bin:${'$'}PATH"
EOF
                            printf 'exec node "%s" "$@"\n' "${'$'}entry_file" >> "/opt/tianyan/bin/${'$'}cmd"
                            chmod 755 "/opt/tianyan/bin/${'$'}cmd" 2>/dev/null || true

                            cat << 'EOF' > "/usr/local/bin/${'$'}cmd"
#!/bin/sh
export PATH="/opt/tianyan/bin:/usr/local/bin:/root/.local/bin:${'$'}PATH"
EOF
                            printf 'exec node "%s" "$@"\n' "${'$'}entry_file" >> "/usr/local/bin/${'$'}cmd"
                            chmod 755 "/usr/local/bin/${'$'}cmd" 2>/dev/null || true
                        fi

                        if [ -z "${'$'}ver" ]; then
                            for candidate in \
                                /opt/tianyan/runtimes/node/*/bin/"${'$'}cmd" \
                                /opt/tianyan/tools/*/bin/"${'$'}cmd" \
                                /root/.local/bin/"${'$'}cmd" \
                                /usr/bin/"${'$'}cmd"; do
                                if [ -f "${'$'}candidate" ] && [ "${'$'}candidate" != "/opt/tianyan/bin/${'$'}cmd" ] && [ "${'$'}candidate" != "/usr/local/bin/${'$'}cmd" ]; then
                                    if ! grep -q "exec .*/${'$'}cmd" "${'$'}candidate" 2>/dev/null; then
                                        ver="${'$'}("${'$'}candidate" --version 2>/dev/null | head -n 1 | grep -oE '[0-9]+(\.[0-9]+)+' | head -n 1)"
                                        if [ -n "${'$'}ver" ]; then
                                            mkdir -p /opt/tianyan/bin 2>/dev/null || true
                                            rm -f "/opt/tianyan/bin/${'$'}cmd" 2>/dev/null || true
                                            cat << 'EOF' > "/opt/tianyan/bin/${'$'}cmd"
#!/bin/sh
export PATH="/opt/tianyan/bin:/usr/local/bin:/root/.local/bin:${'$'}PATH"
EOF
                                            printf 'exec "%s" "$@"\n' "${'$'}candidate" >> "/opt/tianyan/bin/${'$'}cmd"
                                            chmod 755 "/opt/tianyan/bin/${'$'}cmd" 2>/dev/null || true
                                            break
                                        fi
                                    fi
                                fi
                            done
                        fi

                        if [ -n "${'$'}ver" ]; then
                            echo "${'$'}cmd:${'$'}ver"
                        elif [ -n "${'$'}entry_file" ]; then
                            echo "${'$'}cmd:installed"
                        else
                            echo "${'$'}cmd:none"
                        fi
                    }

                    find_and_shim_python_agent() {
                        cmd="${'$'}1"
                        pkg_name="${'$'}2"

                        real_bin=""
                        for candidate in \
                            /opt/tianyan/runtimes/python/*/bin/"${'$'}cmd" \
                            /root/.local/bin/"${'$'}cmd" \
                            /usr/local/bin/"${'$'}cmd" \
                            /usr/bin/"${'$'}cmd"; do
                            if [ -f "${'$'}candidate" ] && [ "${'$'}candidate" != "/opt/tianyan/bin/${'$'}cmd" ]; then
                                if ! grep -q "exec .*/${'$'}cmd" "${'$'}candidate" 2>/dev/null; then
                                    real_bin="${'$'}candidate"
                                    break
                                fi
                            fi
                        done

                        ver=""
                        if [ -n "${'$'}real_bin" ]; then
                            mkdir -p /opt/tianyan/bin 2>/dev/null || true
                            rm -f "/opt/tianyan/bin/${'$'}cmd" 2>/dev/null || true
                            cat << 'EOF' > "/opt/tianyan/bin/${'$'}cmd"
#!/bin/sh
export PATH="/opt/tianyan/bin:/usr/local/bin:/root/.local/bin:${'$'}PATH"
EOF
                            printf 'exec "%s" "$@"\n' "${'$'}real_bin" >> "/opt/tianyan/bin/${'$'}cmd"
                            chmod 755 "/opt/tianyan/bin/${'$'}cmd" 2>/dev/null || true

                            raw="${'$'}("${'$'}real_bin" --version 2>/dev/null || "${'$'}real_bin" -v 2>/dev/null || true)"
                            ver="${'$'}(echo "${'$'}raw" | head -n 1 | grep -oE '[0-9]+(\.[0-9]+)+' | head -n 1)"
                        fi

                        if [ -n "${'$'}ver" ]; then
                            echo "${'$'}cmd:${'$'}ver"
                        elif [ -n "${'$'}real_bin" ]; then
                            echo "${'$'}cmd:installed"
                        else
                            echo "${'$'}cmd:none"
                        fi
                    }

                    find_and_shim_node_agent "claude" "@anthropic-ai/claude-code"
                    find_and_shim_node_agent "codex" "@openai/codex"
                    find_and_shim_node_agent "gemini" "@google/gemini-cli"
                    find_and_shim_node_agent "grok" "grok-build"
                    find_and_shim_node_agent "opencode" "opencode-ai"
                    find_and_shim_node_agent "openclaw" "openclaw"
                    find_and_shim_python_agent "hermes" "hermes-agent"
                    find_and_shim_node_agent "pi" "pi-agent"
                """.trimIndent()
                val res = linuxRuntime.execute(ShellCommand(commandLine = checkScript, timeoutMs = 15000L))
                if (!res.isSuccess) return@withContext emptyMap()
                val map = mutableMapOf<String, String?>()
                res.stdout.lines().forEach { line ->
                    val parts = line.split(":", limit = 2)
                    if (parts.size == 2) {
                        val key = parts[0].trim().lowercase()
                        val value = parts[1].trim()
                        map[key] = if (value == "none" || value.isBlank()) null else value
                    }
                }
                map
            } catch (_: Exception) {
                emptyMap()
            }
        }
    }

    fun startDaemon() {
        _isOperating.value = true
        _errorMessage.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                toolManager.startGateway(toolId)
                delay(1000)
                val running = toolManager.isGatewayRunning(toolId)
                withContext(Dispatchers.Main.immediate) {
                    _isDaemonRunning.value = running
                    if (running) {
                        _successMessage.value = "CC-Switch 守护进程已启动，端口 19870"
                    } else {
                        val lastLog = toolManager.getServiceLogs(toolId).takeLast(10).joinToString("\n").trim()
                        _errorMessage.value = if (lastLog.isNotBlank()) "中枢启动后未保持运行，最新日志：\n$lastLog" else "中枢启动失败，端口未正常监听"
                    }
                }
                if (running) {
                    fetchDaemonData()
                }
            } catch (e: Exception) {
                logger.w("Failed to start cc-switch daemon: ${e.message}", e)
                withContext(Dispatchers.Main.immediate) {
                    _errorMessage.value = "启动中枢守护进程失败：${e.message}"
                }
            } finally {
                _isOperating.value = false
            }
        }
    }

    fun stopDaemon() {
        _isOperating.value = true
        _errorMessage.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                toolManager.stopGateway(toolId)
                delay(500)
                val running = toolManager.isGatewayRunning(toolId)
                withContext(Dispatchers.Main.immediate) {
                    _isDaemonRunning.value = running
                    if (!running) {
                        _daemonStatus.value = null
                        _successMessage.value = "CC-Switch 守护进程已停止"
                    } else {
                        _errorMessage.value = "守护进程未能停止，端口可能仍被占用"
                    }
                }
            } catch (e: Exception) {
                logger.w("Failed to stop cc-switch daemon: ${e.message}", e)
                withContext(Dispatchers.Main.immediate) {
                    _errorMessage.value = "停止中枢守护进程失败：${e.message}"
                }
            } finally {
                _isOperating.value = false
            }
        }
    }

    fun restartDaemon() {
        _isOperating.value = true
        _errorMessage.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                toolManager.restartGateway(toolId)
                delay(1000)
                val running = toolManager.isGatewayRunning(toolId)
                withContext(Dispatchers.Main.immediate) {
                    _isDaemonRunning.value = running
                    if (running) {
                        _successMessage.value = "CC-Switch 守护进程已重启"
                    } else {
                        val lastLog = toolManager.getServiceLogs(toolId).takeLast(10).joinToString("\n").trim()
                        _errorMessage.value = if (lastLog.isNotBlank()) "中枢重启后未保持运行，最新日志：\n$lastLog" else "中枢重启失败，端口未正常监听"
                    }
                }
                if (running) {
                    fetchDaemonData()
                }
            } catch (e: Exception) {
                logger.w("Failed to restart cc-switch daemon: ${e.message}", e)
                withContext(Dispatchers.Main.immediate) {
                    _errorMessage.value = "重启失败：${e.message}"
                }
            } finally {
                _isOperating.value = false
            }
        }
    }

    fun openSwitchProviderDialog(agent: CcAgentState) {
        _switchingAgent.value = agent
    }

    fun openUpgradeDialog(agent: CcAgentState) {
        _upgradingAgent.value = agent
    }

    fun dismissDialogs() {
        _switchingAgent.value = null
        _upgradingAgent.value = null
    }

    fun dismissMessage() {
        _errorMessage.value = null
        _successMessage.value = null
    }

    fun switchAgentProvider(agent: CcAgentState, provider: CcProviderProfile) {
        val appId = agent.type.serverAppId
        if (appId == null) {
            _switchingAgent.value = null
            _errorMessage.value = "${agent.type.displayName} 当前服务端版本不支持模型源切换"
            return
        }
        viewModelScope.launch {
            _switchingAgent.value = null
            _isOperating.value = true
            try {
                val creds = readCcSwitchWebCredentials(linuxRuntime)
                val res = ccSwitchClient.switchProvider(appId, provider.id, creds.first, creds.second, creds.third)
                if (res.isSuccess) {
                    // Update local state optimistic
                    _agents.value = _agents.value.map {
                        if (it.type == agent.type) {
                            it.copy(
                                activeProviderId = provider.id,
                                activeProviderName = provider.name,
                                activeModelName = provider.selectedModel,
                            )
                        } else it
                    }
                    _successMessage.value = "已将 ${agent.type.displayName} 切换至 ${provider.name}"
                } else {
                    _errorMessage.value = "切换 Provider 失败：${res.exceptionOrNull()?.message}"
                }
            } finally {
                _isOperating.value = false
            }
        }
    }

    fun importTianyanModel(model: AiModelEntity, target: CcAgentType? = null) {
        val appId = (target ?: CcAgentType.CLAUDE_CODE).serverAppId
        if (appId == null) {
            _errorMessage.value = "所选 Agent 当前服务端版本不支持导入模型源"
            return
        }
        viewModelScope.launch {
            _isOperating.value = true
            try {
                val apiKey = if (model.secretRef.isNotBlank()) {
                    providerRepository.readModelApiKey(model.secretRef).orEmpty()
                } else ""
                val profile = CcProviderProfile(
                    id = model.id,
                    name = model.name,
                    protocol = if (model.provider.contains("anthropic", ignoreCase = true)) "ANTHROPIC" else "OPENAI",
                    baseUrl = model.baseUrl,
                    apiKey = apiKey,
                    maskedApiKey = if (apiKey.length > 8) "${apiKey.take(4)}...${apiKey.takeLast(4)}" else "***",
                    selectedModel = model.model,
                    availableModels = listOf(model.model),
                    isCustom = true,
                )
                val creds = readCcSwitchWebCredentials(linuxRuntime)
                val saveRes = ccSwitchClient.saveProvider(appId, profile, creds.first, creds.second, creds.third)
                if (saveRes.isSuccess) {
                    _providers.value = _providers.value.filterNot { it.id == profile.id } + profile
                    _successMessage.value = "已从天衍模型库同步：${model.name}"
                } else {
                    _errorMessage.value = "同步到 CC-Switch 失败：${saveRes.exceptionOrNull()?.message}"
                }
            } finally {
                _isOperating.value = false
            }
        }
    }

    fun showInstallLogs() {
        _showLogDialog.value = true
    }

    fun dismissInstallLogs() {
        _showLogDialog.value = false
    }

    fun clearInstallLogs() {
        _installLogs.value = emptyList()
    }

    fun installOrUpgradeAgent(agent: CcAgentState, targetVersion: String? = null) {
        try { localAccess?.requireLocal() } catch (e: IllegalStateException) { _errorMessage.value = e.message; return }
        _upgradingAgent.value = null
        _isOperating.value = true
        _installingAgentType.value = agent.type
        _errorMessage.value = null
        _successMessage.value = null
        _logAgentTitle.value = "${agent.type.displayName} 安装与部署"
        _showLogDialog.value = true

        val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        fun log(msg: String) {
            val now = timeFormatter.format(Date())
            msg.lines().filter { it.isNotBlank() }.forEach { line ->
                _installLogs.update { (it + "[$now] $line").takeLast(1000) }
            }
        }

        _installLogs.value = listOf("[${timeFormatter.format(Date())}] [*] 准备安装/升级 ${agent.type.displayName} (目标版本: ${targetVersion ?: "最新版本"})...")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val isNodeTool = agent.type in listOf(
                    CcAgentType.CLAUDE_CODE,
                    CcAgentType.CODEX,
                    CcAgentType.GEMINI_CLI,
                    CcAgentType.OPENCODE,
                    CcAgentType.OPENCLAW,
                    CcAgentType.GROK_BUILD,
                    CcAgentType.PI,
                )

                if (isNodeTool) {
                    log("[*] 检查沙箱内 Node.js / npm 运行时环境...")
                    val hasNode = linuxRuntime.execute(
                        ShellCommand("command -v npm >/dev/null 2>&1 || [ -x /opt/tianyan/bin/npm ] || [ -x /usr/local/bin/npm ]")
                    ).isSuccess
                    if (!hasNode) {
                        log("[!] 沙箱内未检测到可用 npm，正在通过天衍依赖管理器自动解析...")
                        val depRes = dependencyManager.acquire(
                            RuntimeRequirement(RuntimeName.NODE),
                            "cc-switch",
                        )
                        if (depRes is AppResult.Failure) {
                            log("[!] 依赖管理器未命中预置包，降级使用 apt-get 安装 nodejs & npm (请耐心等待)...")
                            val aptRes = linuxRuntime.execute(
                                ShellCommand(
                                    commandLine = "apt-get update -y && apt-get install -y nodejs npm curl",
                                    environment = mapOf("DEBIAN_FRONTEND" to "noninteractive"),
                                    timeoutMs = 120_000L,
                                    onOutput = { log(it) },
                                ),
                            )
                            if (!aptRes.isSuccess) {
                                log("[-] apt-get 安装 nodejs 失败，返回码: ${aptRes.exitCode}")
                            }
                        } else {
                            log("[+] Node.js 运行时依赖装载完成")
                        }
                    } else {
                        log("[+] Node.js / npm 运行时环境已就绪")
                    }
                } else if (agent.type == CcAgentType.HERMES) {
                    log("[*] [步骤 1/3] 检查沙箱内 Python3 运行时环境...")
                    val hasPython = linuxRuntime.execute(ShellCommand("command -v python3 >/dev/null 2>&1")).isSuccess
                    if (!hasPython) {
                        log("[!] 正在装载 Python 运行时依赖...")
                        dependencyManager.acquire(
                            RuntimeRequirement(RuntimeName.PYTHON),
                            "cc-switch",
                        )
                    } else {
                        log("[+] Python3 运行时环境已就绪")
                    }
                }

                // 2. 构建精准安装指令
                val npmPackage = when (agent.type) {
                    CcAgentType.CLAUDE_CODE -> "@anthropic-ai/claude-code"
                    CcAgentType.CODEX -> "@openai/codex"
                    CcAgentType.GEMINI_CLI -> "@google/gemini-cli"
                    CcAgentType.OPENCODE -> "opencode-ai"
                    CcAgentType.OPENCLAW -> "openclaw"
                    CcAgentType.HERMES -> "hermes-agent"
                    CcAgentType.GROK_BUILD -> "grok-build"
                    CcAgentType.PI -> "pi-agent"
                }

                val installScript = if (agent.type == CcAgentType.HERMES) {
                    val pkg = if (targetVersion.isNullOrBlank()) "hermes-agent" else "hermes-agent==$targetVersion"
                    log("[*] [步骤 2/3] 执行 pip 安装: $pkg...")
                    """
                    export PATH="/opt/tianyan/bin:/usr/local/bin:/root/.local/bin:${'$'}PATH"
                    pip install --break-system-packages $pkg -i https://pypi.tuna.tsinghua.edu.cn/simple || pip install --break-system-packages $pkg
                    real_bin=""
                    for candidate in \
                        /opt/tianyan/runtimes/python/*/bin/hermes \
                        /root/.local/bin/hermes \
                        /usr/local/bin/hermes \
                        /usr/bin/hermes; do
                        if [ -f "${'$'}candidate" ] && [ "${'$'}candidate" != "/opt/tianyan/bin/hermes" ]; then
                            if ! grep -q "exec .*/hermes" "${'$'}candidate" 2>/dev/null; then
                                real_bin="${'$'}candidate"
                                break
                            fi
                        fi
                    done
                    if [ -n "${'$'}real_bin" ]; then
                        mkdir -p /opt/tianyan/bin 2>/dev/null || true
                        rm -f "/opt/tianyan/bin/hermes" 2>/dev/null || true
                        cat << 'EOF' > "/opt/tianyan/bin/hermes"
#!/bin/sh
export PATH="/opt/tianyan/bin:/usr/local/bin:/root/.local/bin:${'$'}PATH"
EOF
                        printf 'exec "%s" "${'$'}@"\n' "${'$'}real_bin" >> "/opt/tianyan/bin/hermes"
                        chmod 755 "/opt/tianyan/bin/hermes" 2>/dev/null || true
                        echo "[+] 已建立可执行包装器: /opt/tianyan/bin/hermes -> ${'$'}real_bin"
                    fi
                    """.trimIndent()
                } else {
                    val verSuffix = if (targetVersion.isNullOrBlank()) "" else "@$targetVersion"
                    log("[*] [步骤 2/3] 执行 npm 全局安装: $npmPackage$verSuffix...")
                    log("[*] 正在从国内加速镜像 (https://registry.npmmirror.com) 下载与构建...")
                    """
                    export PATH="/opt/tianyan/bin:/usr/local/bin:/root/.local/bin:${'$'}PATH"
                    NPM_PREFIX="${'$'}(npm config get prefix 2>/dev/null || true)"
                    if [ -n "${'$'}NPM_PREFIX" ] && [ -d "${'$'}NPM_PREFIX/bin" ]; then
                        export PATH="${'$'}NPM_PREFIX/bin:${'$'}PATH"
                    fi
                    for np in /opt/tianyan/runtimes/node/*/bin; do
                        if [ -d "${'$'}np" ]; then
                            export PATH="${'$'}np:${'$'}PATH"
                        fi
                    done

                    # 执行 npm 全局安装 (允许执行 lifecycle 脚本下载原生组件)
                    npm install -g --foreground-scripts $npmPackage$verSuffix --registry=https://registry.npmmirror.com || npm install -g --foreground-scripts $npmPackage$verSuffix

                    pkg_dir=""
                    for nmdir in \
                        /opt/tianyan/runtimes/node/*/lib/node_modules \
                        "${'$'}NPM_PREFIX/lib/node_modules" \
                        /usr/local/lib/node_modules \
                        /usr/lib/node_modules \
                        /root/.local/lib/node_modules; do
                        if [ -d "${'$'}nmdir/$npmPackage" ]; then
                            pkg_dir="${'$'}nmdir/$npmPackage"
                            break
                        fi
                    done

                    entry_file=""
                    if [ -n "${'$'}pkg_dir" ]; then
                        for candidate in \
                            "${'$'}pkg_dir/cli.js" \
                            "${'$'}pkg_dir/bin/${agent.type.defaultExecutable}" \
                            "${'$'}pkg_dir/bin/${agent.type.defaultExecutable}.js" \
                            "${'$'}pkg_dir/dist/cli.js" \
                            "${'$'}pkg_dir/index.js"; do
                            if [ -f "${'$'}candidate" ]; then
                                entry_file="${'$'}candidate"
                                break
                            fi
                        done
                        if [ -z "${'$'}entry_file" ] && [ -f "${'$'}pkg_dir/package.json" ]; then
                            bin_rel="${'$'}(grep -A 5 '"bin"' "${'$'}pkg_dir/package.json" 2>/dev/null | grep -oE '"[^"]+\.js"' | tr -d '"' | head -n 1)"
                            if [ -n "${'$'}bin_rel" ] && [ -f "${'$'}pkg_dir/${'$'}bin_rel" ]; then
                                entry_file="${'$'}pkg_dir/${'$'}bin_rel"
                            fi
                        fi
                    fi

                    if [ -n "${'$'}entry_file" ]; then
                        mkdir -p /opt/tianyan/bin 2>/dev/null || true
                        rm -f "/opt/tianyan/bin/${agent.type.defaultExecutable}" "/usr/local/bin/${agent.type.defaultExecutable}" 2>/dev/null || true
                        cat << 'EOF' > "/opt/tianyan/bin/${agent.type.defaultExecutable}"
#!/bin/sh
export PATH="/opt/tianyan/bin:/usr/local/bin:/root/.local/bin:${'$'}PATH"
EOF
                        printf 'exec node "%s" "$@"\n' "${'$'}entry_file" >> "/opt/tianyan/bin/${agent.type.defaultExecutable}"
                        chmod 755 "/opt/tianyan/bin/${agent.type.defaultExecutable}" 2>/dev/null || true

                        cat << 'EOF' > "/usr/local/bin/${agent.type.defaultExecutable}"
#!/bin/sh
export PATH="/opt/tianyan/bin:/usr/local/bin:/root/.local/bin:${'$'}PATH"
EOF
                        printf 'exec node "%s" "$@"\n' "${'$'}entry_file" >> "/usr/local/bin/${agent.type.defaultExecutable}"
                        chmod 755 "/usr/local/bin/${agent.type.defaultExecutable}" 2>/dev/null || true
                        echo "[+] 确认主入口包装器: /opt/tianyan/bin/${agent.type.defaultExecutable} -> ${'$'}entry_file"
                    fi
                    """.trimIndent()
                }

                val cmdRes = linuxRuntime.execute(
                    ShellCommand(
                        commandLine = installScript,
                        environment = mapOf(
                            "DEBIAN_FRONTEND" to "noninteractive",
                            "npm_config_registry" to "https://registry.npmmirror.com",
                        ),
                        timeoutMs = 180_000L,
                        onOutput = { log(it) },
                    ),
                )

                // 3. 严格在沙箱内校验可执行文件是否生成
                log("[*] [步骤 3/3] 验证沙箱可执行文件路径与版本...")
                val verifyScript = """
                    export PATH="/opt/tianyan/bin:/usr/local/bin:/root/.local/bin:${'$'}PATH"
                    NPM_PREFIX="${'$'}(npm config get prefix 2>/dev/null || true)"
                    if [ -n "${'$'}NPM_PREFIX" ] && [ -d "${'$'}NPM_PREFIX/bin" ]; then
                        export PATH="${'$'}NPM_PREFIX/bin:${'$'}PATH"
                    fi
                    for np in /opt/tianyan/runtimes/node/*/bin; do
                        if [ -d "${'$'}np" ]; then
                            export PATH="${'$'}np:${'$'}PATH"
                        fi
                    done

                    # 如果包装器不存在或存在循环 exec 坏引用，自动自愈重建
                    if [ ! -s "/opt/tianyan/bin/${agent.type.defaultExecutable}" ] || grep -q "exec .*/${agent.type.defaultExecutable}" "/opt/tianyan/bin/${agent.type.defaultExecutable}" 2>/dev/null; then
                        pkg_dir=""
                        for nmdir in \
                            /opt/tianyan/runtimes/node/*/lib/node_modules \
                            "${'$'}NPM_PREFIX/lib/node_modules" \
                            /usr/local/lib/node_modules \
                            /usr/lib/node_modules \
                            /root/.local/lib/node_modules; do
                            if [ -d "${'$'}nmdir/$npmPackage" ]; then
                                pkg_dir="${'$'}nmdir/$npmPackage"
                                break
                            fi
                        done
                        entry_file=""
                        if [ -n "${'$'}pkg_dir" ]; then
                            for candidate in \
                                "${'$'}pkg_dir/cli.js" \
                                "${'$'}pkg_dir/bin/${agent.type.defaultExecutable}" \
                                "${'$'}pkg_dir/bin/${agent.type.defaultExecutable}.js" \
                                "${'$'}pkg_dir/dist/cli.js" \
                                "${'$'}pkg_dir/index.js"; do
                                if [ -f "${'$'}candidate" ]; then
                                    entry_file="${'$'}candidate"
                                    break
                                fi
                            done
                            if [ -z "${'$'}entry_file" ] && [ -f "${'$'}pkg_dir/package.json" ]; then
                                bin_rel="${'$'}(grep -A 5 '"bin"' "${'$'}pkg_dir/package.json" 2>/dev/null | grep -oE '"[^"]+\.js"' | tr -d '"' | head -n 1)"
                                if [ -n "${'$'}bin_rel" ] && [ -f "${'$'}pkg_dir/${'$'}bin_rel" ]; then
                                    entry_file="${'$'}pkg_dir/${'$'}bin_rel"
                                fi
                            fi
                        fi
                        if [ -n "${'$'}entry_file" ]; then
                            mkdir -p /opt/tianyan/bin 2>/dev/null || true
                            rm -f "/opt/tianyan/bin/${agent.type.defaultExecutable}" "/usr/local/bin/${agent.type.defaultExecutable}" 2>/dev/null || true
                            cat << 'EOF' > "/opt/tianyan/bin/${agent.type.defaultExecutable}"
#!/bin/sh
export PATH="/opt/tianyan/bin:/usr/local/bin:/root/.local/bin:${'$'}PATH"
EOF
                            printf 'exec node "%s" "$@"\n' "${'$'}entry_file" >> "/opt/tianyan/bin/${agent.type.defaultExecutable}"
                            chmod 755 "/opt/tianyan/bin/${agent.type.defaultExecutable}" 2>/dev/null || true

                            cat << 'EOF' > "/usr/local/bin/${agent.type.defaultExecutable}"
#!/bin/sh
export PATH="/opt/tianyan/bin:/usr/local/bin:/root/.local/bin:${'$'}PATH"
EOF
                            printf 'exec node "%s" "$@"\n' "${'$'}entry_file" >> "/usr/local/bin/${agent.type.defaultExecutable}"
                            chmod 755 "/usr/local/bin/${agent.type.defaultExecutable}" 2>/dev/null || true
                        fi
                    fi

                    if [ -f "/opt/tianyan/bin/${agent.type.defaultExecutable}" ]; then
                        echo "/opt/tianyan/bin/${agent.type.defaultExecutable}"
                        exit 0
                    else
                        exit 1
                    fi
                """.trimIndent()

                val verifyRes = linuxRuntime.execute(
                    ShellCommand(
                        commandLine = verifyScript,
                        timeoutMs = 10_000L,
                    ),
                )

                if (verifyRes.isSuccess && verifyRes.stdout.isNotBlank()) {
                    val binPath = verifyRes.stdout.trim().lines().firstOrNull { it.isNotBlank() } ?: verifyRes.stdout.trim()
                    log("[+] 检测到可执行命令路径: $binPath")
                    val verRes = linuxRuntime.execute(
                        ShellCommand(
                            commandLine = """
                                export PATH="/opt/tianyan/bin:/usr/local/bin:/root/.local/bin:${'$'}PATH"
                                ver=""
                                for nmdir in /opt/tianyan/runtimes/node/*/lib/node_modules "${'$'}(npm config get prefix 2>/dev/null)/lib/node_modules" /usr/local/lib/node_modules /usr/lib/node_modules; do
                                    pj="${'$'}nmdir/$npmPackage/package.json"
                                    if [ -f "${'$'}pj" ]; then
                                        ver="${'$'}(grep -m 1 '"version"' "${'$'}pj" 2>/dev/null | grep -oE '[0-9]+(\.[0-9]+)+' | head -n 1)"
                                        [ -n "${'$'}ver" ] && break
                                    fi
                                done
                                if [ -z "${'$'}ver" ]; then
                                    raw="${'$'}("${agent.type.defaultExecutable}" --version 2>/dev/null || "${agent.type.defaultExecutable}" -v 2>/dev/null || true)"
                                    ver="${'$'}(echo "${'$'}raw" | head -n 1 | grep -oE '[0-9]+(\.[0-9]+)+' | head -n 1)"
                                fi
                                echo "${'$'}{ver:-installed}"
                            """.trimIndent(),
                            timeoutMs = 10_000L,
                        ),
                    )
                    val rawVer = verRes.stdout.trim().lines().firstOrNull { it.isNotBlank() }
                    val cleanVer = rawVer?.let { Regex("""[0-9]+(\.[0-9]+)+""").find(it)?.value } ?: rawVer ?: targetVersion ?: "已安装"
                    log("[+] 版本检测结果: $cleanVer")
                    log("[+] [SUCCESS] ${agent.type.displayName} 安装成功！可随时在终端中运行 '${agent.type.defaultExecutable}'")
                    withContext(Dispatchers.Main.immediate) {
                        _successMessage.value = "${agent.type.displayName} 安装成功！当前版本：$cleanVer"
                    }
                } else {
                    log("[-] 验证失败：沙箱中未找到可执行命令 '${agent.type.defaultExecutable}'")
                    if (cmdRes.stderr.isNotBlank()) {
                        log("[-] 进程错误输出 (stderr):\n${cmdRes.stderr.trim()}")
                    }
                    val lastErr = cmdRes.stderr.ifBlank { cmdRes.stdout }.lines()
                        .filter { it.isNotBlank() }
                        .takeLast(3)
                        .joinToString("; ")
                    withContext(Dispatchers.Main.immediate) {
                        _errorMessage.value = "安装未完成：${lastErr.ifBlank { "未在沙箱中生成 ${agent.type.defaultExecutable} 命令，请点击下方查看日志排查" }}"
                    }
                }

                delay(500)
                fetchDaemonData()
            } catch (e: Exception) {
                logger.w("Install agent failed: ${e.message}", e)
                log("[-] 安装过程发生异常: ${e.message}")
                log(e.stackTraceToString())
                withContext(Dispatchers.Main.immediate) {
                    _errorMessage.value = "操作失败：${e.message}"
                }
            } finally {
                _installingAgentType.value = null
                _isOperating.value = false
            }
        }
    }

    private fun detectLanIp(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces.asSequence()) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.inetAddresses.asSequence()) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress
                        if (!host.isNullOrBlank() && host != "127.0.0.1") {
                            return host
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
