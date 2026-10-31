package top.tianyan.app.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * MCP (Model Context Protocol) 传输协议类型
 */
@Serializable
enum class McpTransportType {
    STDIO,  // 在 Linux 沙箱内通过标准输入输出与进程交互
    /**
     * 远程 HTTP 服务。客户端按 URL 自动协商：URL 不以 /sse 结尾时先 Streamable HTTP
     * （POST /mcp 请求-响应），失败再回落 legacy HTTP+SSE；服务端只需实现其一。
     */
    SSE,
}
/**
 * MCP 服务配置
 */
@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val description: String = "",
    val transportType: McpTransportType = McpTransportType.STDIO,
    /** 沙箱内执行命令（例如 "npx" 或 "python3"） */
    val command: String = "",
    /** 命令行参数列表（例如 ["-y", "@modelcontextprotocol/server-sqlite", "--db-path", "/workspace/app.db"]） */
    val args: List<String> = emptyList(),
    /** 环境变量 */
    val env: Map<String, String> = emptyMap(),
    /** 远程 MCP 服务的 HTTP 端点 URL（例如 http://127.0.0.1:8000/mcp 或 .../sse） */
    val serverUrl: String = "",
    /** 自定义 HTTP 请求头；仓储单独加密，不参与通用序列化或导出。 */
    @Transient val headers: Map<String, String> = emptyMap(),
    /** 持久化凭据不可读时阻止连接，编辑后重新保存方可恢复。 */
    @Transient val headersReadError: String? = null,
    /** Authentication mode for remote HTTP MCP services. */
    val authMode: McpAuthMode = McpAuthMode.NONE,
    /** Public OAuth client configuration; secrets are never stored here. */
    val oauthClientId: String = "",
    val oauthAuthorizationEndpoint: String = "",
    val oauthTokenEndpoint: String = "",
    val oauthRedirectUri: String = "tianyan://oauth/mcp",
    val oauthScope: String = "",
    val oauthResource: String = "",
    /**
     * 远程 MCP 服务的 Bearer token（发送 `Authorization: Bearer <token>`）。
     * 运行时注入用（例如内置 browser server 的自环凭据）：@Transient 保证不参与序列化，
     * 既不落 Room 也不随 [toExportJsonConfig] 导出。
     */
    @Transient
    val authToken: String = "",
    @Transient val pluginIdentity: PluginExecutionIdentity? = null,
    @Transient val pluginDistribution: String? = null,
    /** 是否启用 */
    val isEnabled: Boolean = true,
    /** 是否为内置预设服务 */
    val isBuiltin: Boolean = false,
    /** 内置 server 默认风险等级（如 "low" / "medium" / "high" / "critical"），MCP bootstrap 据此判定审批粒度。 */
    val builtinRisk: String = "low",
    /** 内置 server bootstrap 优先级。< 0 表示排在所有用户 MCP server 之前；>= 0 表示标准用户配置。 */
    val bootstrapOrder: Int = if (isBuiltin) -1 else 0,
    /**
     * 该能力需要先安装开发套件组件才能运行（例如改成上游 npm 包之后的 WebSearch / CodeGraph）。
     *
     * 出厂默认 [isEnabled] = false，且设置页禁止在组件就绪前开启；这样「已注册但文件不存在」
     * 不会再演成 `discoverTools()` 超时阻塞启动（见 MIGRATION_35_36 的事故记录）。
     */
    val requiresInstall: Boolean = false,
    /** 提供该能力的开发套件组件 id（由 [McpCatalog] 绑定）。空串表示随 APK 直接可用。 */
    val installComponentId: String = "",
    /** 启停偏好与当前环境就绪状态分别表达；二者均不随配置导出。 */
    @Transient val userToggled: Boolean = false,
    @Transient val installationReady: Boolean? = null,
    @Transient val catalogDistribution: String = "",
    @Transient val catalogRevision: Long = 0L,

) {
    /** 导出为 mcpServers JSON 配置片段（键为服务 id）；配置格式约定归模型层，View 只读展示 */
    fun toExportJsonConfig(): String = buildString {
        appendLine("{")
        appendLine("  \"$id\": {")
        if (transportType == McpTransportType.STDIO) {
            appendLine("    \"command\": \"$command\",")
            appendLine("    \"args\": [${args.joinToString(", ") { "\"$it\"" }}]")
            if (env.isNotEmpty()) {
                appendLine("    \"env\": {")
                env.entries.forEachIndexed { i, (k, v) ->
                    val comma = if (i == env.size - 1) "" else ","
                    appendLine("      \"$k\": \"$v\"$comma")
                }
                appendLine("    }")
            }
        } else {
            appendLine("    \"url\": \"$serverUrl\"")
        }
        appendLine("  }")
        append("}")
    }
}

/**
 * MCP 服务连通性检测状态（运行时状态，不持久化）
 */
enum class McpConnectionState {
    /** 尚未检测（默认态 / 服务未启用） */
    UNKNOWN,
    /** 正在检测连通性 */
    CHECKING,
    /** 已连通，服务在线可用 */
    ONLINE,
    /** Tool metadata remains ready; the idle STDIO subprocess has been reclaimed. */
    SLEEPING,
    /** Starting or initializing a STDIO subprocess for a real request. */
    WAKING,
    /** 检测失败 / 服务离线不可达 */
    OFFLINE,
}

/**
 * MCP 工具注解（MCP 规范 tool.annotations 的提示性声明）：
 * 服务端对工具风险特征的自述，**未经本端验证**。审批策略只采信显式声明来提高审批等级
 * （escalation-only）；降低审批要求必须来自本地可信规则（如内置浏览器风险矩阵），
 * 外部声明一律不得放行。缺省（null）按 MCP 规范默认语义是"可能破坏/非只读/可达外部"，
 * 但为避免对存量服务的行为回归，缺省值不参与升级，仅显式 `true` 参与。
 */
@Serializable
data class McpToolAnnotations(
    val readOnlyHint: Boolean? = null,
    val destructiveHint: Boolean? = null,
    val idempotentHint: Boolean? = null,
    val openWorldHint: Boolean? = null,
)

/**
 * 单个 MCP 工具的本地策略。
 *
 * - [enabled]：关闭后模型可见面（inspect / @ 挂载）与执行入口都会拒绝该工具；
 * - [riskOverride]：本地用户显式覆盖审批风险等级；null = 继承基础策略。
 *   覆盖会先替换基础等级，再应用服务端注解升级——本地可降级，但服务端
 *   声明的破坏性特征仍会把等级抬回去。
 */
@Serializable
data class McpToolPolicy(
    val enabled: Boolean = true,
    val riskOverride: String? = null,
) {
    companion object {
        val RISK_LEVELS = setOf("low", "normal", "medium", "high", "critical")

        fun normalizeRisk(value: String?): String? =
            value?.trim()?.lowercase()?.takeIf { it in RISK_LEVELS }
    }
}

/**
 * 动态从 MCP Server 发现并注册的工具定义
 */
@Serializable
data class McpToolInfo(
    val serverId: String,
    val serverName: String,
    val name: String,
    val description: String,
    val parametersJson: String = "{}",
    val annotations: McpToolAnnotations? = null,
)

/**
 * 子智能体任务规格参数
 */
@Serializable
data class SubagentTaskSpec(
    val taskName: String,
    /** Optional exact profile id/name override retained for persisted and legacy calls. */
    val role: String = "",
    val prompt: String,
    /** Built-in department id used to constrain local catalog matching. */
    val department: String = "",
    /** Short English professional keywords matched against id/name/description locally. */
    val agentQuery: String = "",
    /** 子任务声明的写目标文件/目录。空列表 = 只读并行；["*"] = 整工作区独占写租约。 */
    val writePaths: List<String> = emptyList(),
    /** 可选：已保存模型档案的 ID/名称，或该档案中已配置的具体模型名；null/"inherit" 继承父会话。 */
    val model: String? = null,
    /**
     * 可选：续跑目标（对齐 opencode task 工具的 task_id resume）。
     * 填入上次 invoke_subagent 汇总中的 task_id（即子任务 Lane 名）时，
     * 本次在该 Lane 已有对话上继续执行（prompt 为续跑指令），而不是创建全新 Lane。
     */
    val taskId: String? = null,
)

/**
 * 内置 MCP 预设服务
 */
object BuiltinMcpPresets {
    /** 内置 in-app browser MCP server 的 id：自环 Bearer 认证与浏览器工具审批白名单都以它为准。 */
    const val BROWSER_BUILTIN_ID = "tianyan-browser-builtin"

    val presets: List<McpServerConfig> = listOf(
        McpServerConfig(
            id = "mcp_sqlite",
            name = "SQLite 数据库探索器",
            description = "查询、分析与操作沙箱或工作区内的 SQLite 数据库文件",
            transportType = McpTransportType.STDIO,
            command = "python3",
            args = listOf("-u", "/opt/tianyan/scripts/sqlite_mcp_server.py", "--db-path", "/root/tianyan.db"),
            isEnabled = true,
            isBuiltin = true,
        ),
        McpServerConfig(
            id = "mcp_git",
            name = "Git 仓库协同中心",
            description = "深入分析 Git 历史提交、分支拓扑、Diff 差异与工作区状态",
            transportType = McpTransportType.STDIO,
            command = "python3",
            args = listOf("-u", "/opt/tianyan/scripts/git_mcp_server.py", "--repository", "/workspace"),
            isEnabled = true,
            isBuiltin = true,
        ),
        McpServerConfig(
            id = "mcp_apktool",
            name = "Android 逆向与 APK 审计",
            description = "自动化反编译 APK、解析清单权限、提取硬编码凭据、Smali 敏感代码检索与重打包签名（天衍内置 MCP 服务，依赖 android-suite 的 apktool/jadx/aapt 工具链）",
            transportType = McpTransportType.STDIO,
            command = "python3",
            args = listOf("-u", "/opt/tianyan/scripts/apktool_mcp_server.py"),
            isEnabled = true,
            isBuiltin = true,
        ),
        McpServerConfig(
            id = BROWSER_BUILTIN_ID,
            name = "Tianyan Browser (Built-in)",
            description = "Tianyan 内置 in-app WebView 的 MCP server（in-process loopback 127.0.0.1:8787，Streamable HTTP POST /mcp 请求-响应，Bearer 认证）；由 harness 层 BrowserMcpBootstrap 启动，暴露 mcp__browser__* 工具给 Agent 与外接 IDE。",
            transportType = McpTransportType.SSE,
            serverUrl = "http://127.0.0.1:8787/mcp",
            isEnabled = true,
            isBuiltin = true,
            builtinRisk = "medium",
            bootstrapOrder = -100,
        ),
    ) + McpCatalog.installable.map { it.serverPreset() }

    private val byId: Map<String, McpServerConfig> = presets.associateBy { it.id }

    /**
     * 按 id 取预设定义。
     *
     * 预设里的 [McpServerConfig.requiresInstall] / [McpServerConfig.installComponentId] 等
     * "代码侧元数据"不落数据库，仓库层从数据库读回后用本方法叠加回去。
     */
    fun find(id: String): McpServerConfig? = byId[id]
}
