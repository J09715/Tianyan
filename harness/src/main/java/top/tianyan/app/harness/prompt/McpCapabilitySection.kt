package top.tianyan.app.harness.prompt

import kotlinx.coroutines.flow.first
import top.tianyan.app.core.database.McpServerRepository
import top.tianyan.app.core.model.BuiltinMcpPresets
import top.tianyan.app.harness.ToolCallMode
import top.tianyan.app.core.model.withInstalledComponents

/**
 * MCP 能力引导章节（use_capability 代理形态）。
 *
 * 从 SystemPromptBuilder 抽出：该文件已触及架构棘轮基线，章节本身是「纯渲染」，
 * 只依赖设置库的启用服务清单，与宿主类的其余状态无耦合。
 *
 * - MCP 工具 schema 不在本轮 tools 列表里，模型经 use_capability 的
 *   list/inspect 发现能力、call 调用（未连接的服务在调用时自动启动）；
 * - 内置能力保留「使用时机」引导（何时该想到用某个能力）；
 * - 未启用 / 未安装的内置能力按「使用时机」引导请求授权或安装，在此之前不得绕过或模拟。
 *
 * 数据源是设置库的启用服务清单（零发现、零进程启动）——服务增删只在
 * 用户显式操作时变化，属于既定的缓存重置事件。
 */
internal suspend fun buildMcpCapabilitySection(
    mcpServerRepository: McpServerRepository,
    toolCallMode: ToolCallMode,
    installedComponentIds: Set<String>? = null,
): String {
    val servers = runCatching {
        mcpServerRepository.servers.first().map { it.withInstalledComponents(installedComponentIds) }
    }.getOrDefault(emptyList())
    val enabledServers = servers.filter { it.isEnabled }
    val enabledById = enabledServers.associateBy { it.id }
    val builtinIds = BuiltinMcpPresets.presets.map { it.id }.toSet()

    val builtinLines = BuiltinMcpPresets.presets.map { preset ->
        val enabled = preset.id in enabledById
        val ready = servers.firstOrNull { it.id == preset.id }?.installationReady
        val status = when {
            enabled -> "已启用"
            preset.requiresInstall && ready == false -> "未安装（当前环境缺少有效包）"
            preset.requiresInstall && ready == null -> "安装状态未确认"
            else -> "未启用（默认关闭）"
        }
        val trigger = mcpUsageGuidance[preset.id]
        val triggerLine = if (!trigger.isNullOrBlank()) "使用时机：$trigger。" else ""
        val usage = when {
            enabled -> "已授权常驻；本轮若已挂载该服务的 mcp__ 工具则直接调用，否则用 use_capability(action=\"call\", server=\"${preset.id}\", tool=…) 调用。"
            // 未安装的能力不能靠"请求授权"解决：先要做一次安装动作，提示词必须区分这两件事，
            // 否则模型会反复请求用户开启一个开关（而开关在安装前是锁死的）。
            preset.requiresInstall && ready != true ->
                "未安装：该能力已从 APK 内置改为按需安装。一旦任务命中上述使用时机，" +
                    "请向用户说明并引导其到「设置 → 工具中心 → MCP 能力仓库」安装对应组件；" +
                    "安装前不得绕过、不得模拟调用，也不要请求用户仅切换开关。"
            else -> "未授权：一旦任务命中上述使用时机，请先向用户说明该能力并请求其到「设置 → MCP 插件与协议生态」开启，授权常驻后再调用；未授权前不得绕过或模拟。"
        }
        val desc = preset.description.replace(Regex("\\s+"), " ").trim()
        val brief = if (desc.length > 120) desc.take(117) + "…" else desc
        "- [${status}] ${preset.name}（server=\"${preset.id}\"）：${brief}。${triggerLine}${usage}"
    }
    // 自定义（非内置）已启用服务
    val customLines = enabledServers.filter { it.id !in builtinIds }.map { server ->
        "- [已启用] ${server.name}（server=\"${server.id}\"）：本轮若已出现该服务的 mcp__ 工具则直接调用；否则用 use_capability(action=\"inspect\", server=\"${server.id}\") 查看工具清单后调用。"
    }
    if (builtinLines.isEmpty() && customLines.isEmpty()) return ""
    val protocol = buildString {
        append("\n\n## 系统核心 MCP 能力（经 use_capability 统一代理发现与调用）\n")
        append("MCP 工具与内置低频宿主能力的名称与参数默认不在本轮工具列表里，发现与调用全部通过 use_capability（虚拟屏等宿主能力域用 server=\"host\"）：\n")
        append("0. 若本轮 tools 中出现以 `mcp__` 开头的工具，说明用户已通过 @ 挂载对应服务：直接调用这些工具，不要重复 inspect；未挂载的服务仍走下面的代理流程。\n")
        append("1. action=\"list\"：列出已启用的服务（不启动任何进程）；\n")
        append("2. action=\"inspect\" + server=\"<id>\"：查看该服务的工具清单与参数说明；\n")
        append("3. action=\"call\" + server=\"<id>\" + tool=\"<工具名>\" + arguments={...}：调用工具（未连接的服务会自动启动，首次启动可能需要数秒）；4. action=\"script\" + code=\"<JS>\"：写一段 JS 批量/循环/条件调用能力（capability.call 返回 {ok, output}；不支持顶层 return，末条表达式的完成值即返回值），把多轮 call 合并成一次，每条内层调用照常校验、审批与留痕。\n")
        if (toolCallMode == ToolCallMode.JSON_TEXT) {
            append("（当前为文本工具协议：use_capability 的调用标记同样按工具调用协议输出。）\n")
        }
        append("重要：工具名必须原样使用，不可编造；审批按工具风险执行；@ 提及只产生能力挂载记录，不影响工具可用性。\n")
    }
    return protocol + (builtinLines + customLines).joinToString("\n")
}

/**
 * 内置 MCP 的「使用时机」引导：让 harness 在任务发生前就知道该优先调用哪个系统核心能力，
 * 而不是等用户点名。key = 内置 MCP 的 serverId。
 */
internal val mcpUsageGuidance: Map<String, String> = mapOf(
    "mcp_codegraph" to "代码检索、项目重构、架构分析、符号定位、调用链与影响面分析（具体检索策略见 code-navigation 规则块）",
    BuiltinMcpPresets.BROWSER_BUILTIN_ID to "用户要求打开/浏览具体网站（如\"打开百度\"\"去 GitHub 看看某仓库\"）、在真实浏览器里可视化操作页面（导航、点击、输入、截图、读 console）、从网页 API 拉取数据、做浏览器脚本测试时使用。与 websearch 的边界：用户点名网站或要看\"浏览器里发生了什么\"→ 用浏览器工具真实导航操作；只要纯文本检索结果不要可视化 → 用 websearch。用户说\"打开 XX 搜索 YY\"属于前者，应打开该网站并在页面内完成搜索",
    "mcp_websearch" to "仅需联网获取文本资料（最新资讯、文档、外部信息）时使用；用户明确要求打开某个网站、或在浏览器里可视化操作/抓取页面时改用内置浏览器工具",
    "mcp_git" to "只读分析 Git 历史提交、分支拓扑、Diff 差异与仓库状态时使用；实际变更仓库（add/commit/push/checkout 等）改用 base 执行 git 命令",
    "mcp_sqlite" to "交互式查询与表结构分析 SQLite 数据库时使用；批量导入/dump/迁移等脚本化操作改用 base",
    "mcp_apktool" to "APK 逆向、清单权限解析、硬编码凭据提取、Smali 敏感代码检索时使用",
)
