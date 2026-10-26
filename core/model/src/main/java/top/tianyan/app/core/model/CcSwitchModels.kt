package top.tianyan.app.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class CcAgentType(
    val id: String,
    val displayName: String,
    val defaultExecutable: String,
    val category: String = "CLI",
    /**
     * cc-switch-web 服务端纳管该 Agent 时使用的 AppType 标识（即 `/api/providers/{app}` 的 `{app}`）。
     * 为 null 表示当前服务端版本不为该 Agent 暴露模型源纳管接口，界面不再提供切源入口。
     */
    val serverAppId: String? = null,
) {
    CLAUDE_CODE("claude-code", "Claude Code", "claude", "CLI", "claude"),
    CODEX("codex", "Codex", "codex", "CLI", "codex"),
    GEMINI_CLI("gemini-cli", "Gemini CLI", "gemini", "CLI", "gemini"),
    OPENCODE("opencode", "OpenCode", "opencode", "CLI", "opencode"),
    OPENCLAW("openclaw", "OpenClaw", "openclaw", "Agent", "openclaw"),
    GROK_BUILD("grok-build", "Grok Build", "grok", "CLI", null),
    HERMES("hermes-agent", "Hermes Agent", "hermes", "Agent", null),
    PI("pi", "Pi", "pi", "Agent", null);

    companion object {
        fun fromId(id: String): CcAgentType? = entries.firstOrNull {
            it.id.equals(id, ignoreCase = true) || it.name.equals(id, ignoreCase = true)
        }

        /** 服务端支持模型源纳管的 Agent 子集。 */
        val serverManaged: List<CcAgentType> get() = entries.filter { it.serverAppId != null }
    }
}

@Serializable
data class CcAgentState(
    val type: CcAgentType,
    val installed: Boolean = false,
    val currentVersion: String? = null,
    val latestVersion: String? = null,
    val availableVersions: List<String> = emptyList(),
    val activeProviderId: String? = null,
    val activeProviderName: String? = null,
    val activeModelName: String? = null,
    val isChecking: Boolean = false,
    val errorNotice: String? = null,
    val description: String = "",
    val running: Boolean = false,
    val servicePort: Int? = null,
    val webPath: String? = null,
)

@Serializable
data class CcProviderProfile(
    val id: String,
    val name: String,
    val protocol: String = "OPENAI", // "OPENAI" or "ANTHROPIC"
    val baseUrl: String = "",
    val apiKey: String = "",
    val maskedApiKey: String = "",
    val selectedModel: String = "",
    val availableModels: List<String> = emptyList(),
    val isCustom: Boolean = false,
    val iconName: String? = null,
)

@Serializable
data class CcTokenUsage(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val totalTokens: Long = 0,
    val totalCostEstimateCny: Double = 0.0,
    val requestCount: Long = 0,
)

@Serializable
data class CcSwitchDaemonStatus(
    val running: Boolean = false,
    val port: Int = 19870,
    val version: String = "",
    val proxyEnabled: Boolean = true,
    val uptimeSeconds: Long = 0,
    val totalTokens: CcTokenUsage = CcTokenUsage(),
    val todayTokens: CcTokenUsage = CcTokenUsage(),
)
