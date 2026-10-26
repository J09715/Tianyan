package top.tianyan.app.core.model

import kotlinx.serialization.Serializable

/**
 * 🧩 MCP 能力仓库（MCP Capability Catalog）
 *
 * 把「有哪些 MCP」「怎么装」「怎么更」拆成三层，本项目只承担**精选层**：
 * - 发现层：`punkpeye/awesome-mcp-servers` —— 只用来人工挑选候选，条目里没有包名/版本/校验和，不能程序化消费；
 * - 安装 / 更新层：npm registry（走 `registry.npmmirror.com`）与 PyPI —— 唯一能提供 version + 校验和 + tarball 的来源；
 * - 精选层：本文件的条目 + 开发套件（[BuiltinPluginBundles]）的组件生命周期。
 *
 * 三条硬约束（见 docs/MCP_CATALOG.md）：
 * 1. **禁止 `@latest`**：一律固定版本号，否则安装不可复现、更新不可审计；
 * 2. **安装期落盘校验，运行期不联网现拉**：元数据里的 `integrity` 会在装配期与 registry 比对，
 *    不一致直接中止；运行时只通过 `run` 子命令直调已落盘的全局包，避免 `discoverTools()` 冷启动拉包超时；
 * 3. **更新是显式动作**：catalog 只声明出厂 pin，升级由用户点「检查更新」触发，写入落位标记即刻生效。
 *
 * 关于版本从哪里读——**落位标记是唯一真相**，`/opt/tianyan/mcp/<id>.ok` 记录实际安装的
 * `NAME/VERSION/SPEC/INTEGRITY`。运行参数因此**不携带版本号**（否则「升级」只改了全局安装，
 * 进程仍按代码里的旧 pin 拉起，升级会退化成假动作）；代码里的 [McpCatalogEntry.pinnedVersion]
 * 只表示出厂装配的默认值，以及「重新安装」时的回落点。
 *
 * 本文件是纯数据 + 纯函数：不碰文件系统、不执行命令，安装与探针都交给开发套件已有的
 * `PluginComponent.postInstallSteps` / `checkCommand` 机制。
 */
@Serializable
enum class McpCatalogChannel {
    /** npm 通道：`npm install -g <pkg>@<pin>`，校验 `dist.integrity`。 */
    NPM,

    /** PyPI 通道：需要 python3-pip 与独立 venv，当前未启用（见 [McpCatalogEntry.available]）。 */
    PYPI,
}

/**
 * 一条可安装的 MCP 能力。
 *
 * `id` 与 `McpServerConfig.id` 一一对应：安装完成后由仓库层把对应服务置为「已安装 + 启用一次」，
 * 因此这里不额外引入第二套标识。
 */
@Serializable
data class McpCatalogEntry(
    val id: String,
    val name: String,
    val summary: String,
    val channel: McpCatalogChannel,
    /** 完整包规格（含固定版本），例如 `open-websearch@2.2.0`、`@colbymchenry/codegraph@1.6.2`。 */
    val packageSpec: String,
    /** 包名（不含版本），卸载时需要。 */
    val packageName: String,
    val pinnedVersion: String,
    /** registry 提供的 `dist.integrity`（sha512）。装配期必须与它逐字相等，否则中止。 */
    val integrity: String,
    /** 本地版本记录；探针同时核验实际包与入口，启动不询问 registry。 */
    val markerPath: String,
    /** 该 MCP 服务自身需要的启动参数（例如 CodeGraph 的 `serve --mcp`）。版本由落位标记决定，不写在这里。 */
    val serverArgs: List<String> = emptyList(),
    val runtimeEnv: Map<String, String> = emptyMap(),
    /** 提供该能力的开发套件组件 id；安装该组件即代表能力已就绪。 */
    val installComponentId: String,
    /** 组件级依赖（例如 Node 运行时）。 */
    val dependsOnComponentIds: List<String> = emptyList(),
    /** 需要用户凭证的能力不可一键安装，保持 [available]=false。 */
    val available: Boolean,
    val unavailableReason: String = "",
    val risk: String = "low",
    /** 需要 per-workspace 索引（如 CodeGraph）：装好 ≠ 可用，首轮使用前要建索引。 */
    val requiresWorkspaceIndex: Boolean = false,
    val notes: String = "",
) {
    /** 装配步骤：交给 `PluginComponent.postInstallSteps` 执行。 */
    val installSteps: List<String>
        get() = listOf(
            "/bin/sh ${McpCatalog.SCRIPT_PATH} install $id $packageSpec '$integrity'",
        )

    /** 卸载命令：交给 `PluginComponent.postUninstallSteps`（受白名单正则约束，不能带 shell 元字符）。 */
    val uninstallCommand: String
        get() = "npm uninstall -g $packageName >/dev/null 2>&1 || true"

    /** 状态探针：交给 `PluginComponent.checkCommand`，返回 0 视为已就绪。 */
    val probeCommand: String
        get() = "/bin/sh ${McpCatalog.SCRIPT_PATH} probe $id $packageName >/dev/null 2>&1"

    fun serverPreset(): McpServerConfig = McpServerConfig(
        id = id, name = name, description = componentDescription,
        command = launchCommand, args = launchArgs, env = runtimeEnv,
        isEnabled = false, isBuiltin = true, requiresInstall = true,
        installComponentId = installComponentId, builtinRisk = risk,
    )

    /**
     * 启动命令：npm 通道由装配脚本按落位标记解析实际版本后 `exec` 全局包的可执行入口。
     * 刻意不经过 `npx`，也不在参数里带版本号，见文件头的「落位标记是唯一真相」。
     */
    val launchCommand: String
        get() = if (channel == McpCatalogChannel.NPM) "/bin/sh" else "python3"

    /** 启动参数：npm 通道为 [McpCatalog.SCRIPT_PATH] 的 `run` 子命令 + 该服务自身的参数。 */
    val launchArgs: List<String>
        get() = if (channel == McpCatalogChannel.NPM) {
            listOf(McpCatalog.SCRIPT_PATH, "run", id, packageName) + serverArgs
        } else {
            serverArgs
        }

    /** 更新检查命令：读落位标记里的已装版本，再对比镜像上的最新版本。 */
    val checkUpdateCommand: String
        get() = "/bin/sh ${McpCatalog.SCRIPT_PATH} check $id $packageName"

    /** 一键升级命令：以镜像上的最新版本覆盖安装，并重写落位标记。 */
    val upgradeCommand: String
        get() = "/bin/sh ${McpCatalog.SCRIPT_PATH} upgrade $id $packageName"

    /** 是否为可一键安装的 npm 能力。 */
    val installable: Boolean
        get() = available && channel == McpCatalogChannel.NPM

    /** 能力仓库里的组件名（带风险与索引提示，避免用户在 UI 里只看名字）。 */
    val componentDescription: String
        get() = buildString {
            append(summary)
            append("。风险等级：$risk；出厂固定版本 $pinnedVersion")
            if (installable) append("；可在装配弹窗内「检查更新」升级，升级后用「重新安装」可回落到出厂版本")
            if (requiresWorkspaceIndex) append("；装配后需在工作区建立索引才可用")
            if (notes.isNotBlank()) append("。").append(notes)
        }
}

/**
 * 一条能力的「检查更新」结果（运行时状态，不落库）。
 *
 * [installedVersion] 来自落位标记（实际装了什么），[latestVersion] 来自镜像（上游现在是什么）；
 * 两者都拿到且不同才构成「可更新」。任何一个读不到都退化为提示信息，绝不猜。
 */
data class McpUpdateStatus(
    val id: String,
    val installedVersion: String = "",
    val latestVersion: String = "",
    val error: String = "",
) {
    val updateAvailable: Boolean
        get() = error.isBlank() && installedVersion.isNotBlank() &&
            latestVersion.isNotBlank() && installedVersion != latestVersion

    /** 一行可读结论，UI 直接展示。 */
    val summary: String
        get() = when {
            error.isNotBlank() -> error
            installedVersion.isBlank() -> "尚未安装"
            latestVersion.isBlank() -> "上游版本未知"
            updateAvailable -> "可更新到 v$latestVersion"
            else -> "已是最新 v$installedVersion"
        }
}

/**
 * 精选清单。
 *
 * 入库门槛见 docs/MCP_CATALOG.md 的四道筛子；下列每一条的版本与校验和都在 2026-10-13 实机核对过
 * （版本存在 + `dist.integrity` 存在）。未核对条目一律不得入库。
 */
object McpCatalog {
    /** 开发套件 id：MCP 能力仓库。 */
    const val BUNDLE_ID = "mcp-catalog"

    /** 装配脚本在沙箱内的绝对路径（随 `assets/scripts/` 同步，见 RuntimeAssetSynchronizer）。 */
    const val SCRIPT_PATH = "/opt/tianyan/scripts/setup_mcp_catalog.sh"

    /** 落位标记目录：卸载与探针都只认这里，不与 npm 的全局目录耦合。 */
    const val MARKER_DIR = "/opt/tianyan/mcp"

    private fun marker(id: String) = "$MARKER_DIR/$id.ok"

    val entries: List<McpCatalogEntry> = listOf(
        McpCatalogEntry(
            id = "mcp_websearch",
            name = "Web 搜索（Open-WebSearch）",
            summary = "免 API Key 的多引擎网络搜索与网页正文抓取",
            channel = McpCatalogChannel.NPM,
            packageSpec = "open-websearch@2.2.0",
            packageName = "open-websearch",
            pinnedVersion = "2.2.0",
            integrity = "sha512-Wv2iZqknbNGNMqD9YQ7+gfgc0FFIFg303HfbysRDDbbob9vamCp/99ltP3EIMO/kskWERU9cFpDV3tO1sdnPTw==",
            markerPath = marker("mcp_websearch"),
            runtimeEnv = mapOf("MODE" to "stdio", "DEFAULT_SEARCH_ENGINE" to "baidu"),
            installComponentId = "mcp_websearch",
            dependsOnComponentIds = listOf("nodejs-core"),
            available = true,
            risk = "medium",
            notes = "新版不再内置 Playwright，网页正文抓取仅走 request 模式；需要浏览器渲染或带 Cookie 的页面会失败",
        ),
        McpCatalogEntry(
            id = "mcp_codegraph",
            name = "CodeGraph 代码知识图谱",
            summary = "tree-sitter 解析 + SQLite 索引，提供符号检索、调用链与影响面",
            channel = McpCatalogChannel.NPM,
            packageSpec = "@colbymchenry/codegraph@1.6.2",
            packageName = "@colbymchenry/codegraph",
            pinnedVersion = "1.6.2",
            integrity = "sha512-hODH/mGnCf3IUMs6rzktv/zbecJIKJfdZAYHZ9mqecb0G7ZkHaA8CZafDVnswANFEL1YFcG+lfAwc5SIXzUH6Q==",
            markerPath = marker("mcp_codegraph"),
            serverArgs = listOf("serve", "--mcp"),
            installComponentId = "mcp_codegraph",
            dependsOnComponentIds = listOf("nodejs-core"),
            available = true,
            risk = "medium",
            requiresWorkspaceIndex = true,
            notes = "better-sqlite3 是原生模块，ARM64 未命中预编译产物时会回落 WASM（可用但更慢）",
        ),
        McpCatalogEntry(
            id = "mcp_memory",
            name = "Knowledge Graph Memory",
            summary = "跨会话持久化的实体 / 关系知识图谱，本地 JSON 存储",
            channel = McpCatalogChannel.NPM,
            packageSpec = "@modelcontextprotocol/server-memory@1.0.0",
            packageName = "@modelcontextprotocol/server-memory",
            pinnedVersion = "1.0.0",
            integrity = "sha512-UDMtMrw2xeI16mcn7O4fesp9Pf3hYh91thuU5KSz0BxKqwnTwEytZKjHfkrqxxmKp0r4QHoiEkMV99UmadmODw==",
            markerPath = marker("mcp_memory"),
            runtimeEnv = mapOf("MEMORY_FILE_PATH" to "/opt/tianyan/data/mcp-memory/memory.json"),
            installComponentId = "mcp_memory",
            dependsOnComponentIds = listOf("nodejs-core"),
            available = true,
            risk = "low",
            notes = "与工程记忆（Room 的 agent_memories）职责分离：本能力是模型自管的知识图谱，不回写工程记忆",
        ),
        McpCatalogEntry(
            id = "mcp_sequential_thinking",
            name = "Sequential Thinking",
            summary = "显式多步推理链，纯计算无副作用",
            channel = McpCatalogChannel.NPM,
            packageSpec = "@modelcontextprotocol/server-sequential-thinking@1.0.0",
            packageName = "@modelcontextprotocol/server-sequential-thinking",
            pinnedVersion = "1.0.0",
            integrity = "sha512-ykTmeS4Eejs+FESDrD73mDBNYCkb+cCZJ1MWuv7/vwucbVgGfEbBL5HVt2nVw0/vNF8jz+o9sO/WmxxfXt0J6w==",
            markerPath = marker("mcp_sequential_thinking"),
            installComponentId = "mcp_sequential_thinking",
            dependsOnComponentIds = listOf("nodejs-core"),
            available = true,
            risk = "low",
            notes = "无 IO，审批策略可作为免审批只读能力",
        ),
        McpCatalogEntry(
            id = "mcp_context7",
            name = "Context7 库文档检索",
            summary = "按库名拉取最新官方文档片段，降低 SDK 用法幻觉",
            channel = McpCatalogChannel.NPM,
            packageSpec = "@upstash/context7-mcp@4.3.0",
            packageName = "@upstash/context7-mcp",
            pinnedVersion = "4.3.0",
            integrity = "sha512-fuDt5nWjE5JkAPLjU0m+m0LlJphxyegAvAmcwHoA5jrzAhKo/rVV0RFA8TOwODGdDVdt/nPxNslkxeDIDp/rPA==",
            markerPath = marker("mcp_context7"),
            installComponentId = "mcp_context7",
            dependsOnComponentIds = listOf("nodejs-core"),
            available = true,
            risk = "medium",
            notes = "依赖境外 SaaS 端点，国内网络可能不可达；失败只应降级为不可用，不得阻塞会话",
        ),
        McpCatalogEntry(
            id = "mcp_github",
            name = "GitHub 平台集成",
            summary = "Issues / PR / 搜索 / 文件操作",
            channel = McpCatalogChannel.NPM,
            packageSpec = "@modelcontextprotocol/server-github@2025.4.8",
            packageName = "@modelcontextprotocol/server-github",
            pinnedVersion = "2025.4.8",
            integrity = "sha512-8N43bQw9MlUB0piTZHK2JMh8kYPKxH57d4Z7Wb8PS4by2MkZ0FzI5xPImg3xumpev82VZw2VWHQJJJYp+WkwEw==",
            markerPath = marker("mcp_github"),
            runtimeEnv = mapOf("GITHUB_PERSONAL_ACCESS_TOKEN" to "<vault-ref>"),
            installComponentId = "mcp_github",
            dependsOnComponentIds = listOf("nodejs-core"),
            // 需要用户凭证：必须先接入加密凭证仓库，未配置凭证时不得安装（否则每个工具调用都失败）。
            available = false,
            unavailableReason = "需要 GitHub 个人访问令牌；凭证仓库接入后再开放安装",
            risk = "high",
        ),
        // ---------- PyPI 通道（当前未启用） ----------
        // 官方参考实现里 git / fetch / time / sqlite 只在 PyPI 发布，且每个包需要独立 venv，
        // 成本高于 npm 通道；在 pip 通道落地前这些条目只作为可见的规划项，不入开发套件。
        McpCatalogEntry(
            id = "mcp_fetch",
            name = "Fetch 网页抓取（官方）",
            summary = "把给定 URL 的 HTML 转 Markdown 并返回正文",
            channel = McpCatalogChannel.PYPI,
            packageSpec = "mcp-server-fetch==2026.10.10",
            packageName = "mcp-server-fetch",
            pinnedVersion = "2026.10.10",
            integrity = "",
            markerPath = marker("mcp_fetch"),
            serverArgs = listOf("-m", "mcp_server_fetch"),
            installComponentId = "mcp_fetch",
            available = false,
            unavailableReason = "pip 通道未启用（需要 python3-pip 与每包独立 venv）",
            risk = "low",
        ),
        McpCatalogEntry(
            id = "mcp_time",
            name = "Time 时区工具（官方）",
            summary = "时区换算与当前时间",
            channel = McpCatalogChannel.PYPI,
            packageSpec = "mcp-server-time==2026.10.10",
            packageName = "mcp-server-time",
            pinnedVersion = "2026.10.10",
            integrity = "",
            markerPath = marker("mcp_time"),
            serverArgs = listOf("-m", "mcp_server_time"),
            installComponentId = "mcp_time",
            available = false,
            unavailableReason = "pip 通道未启用（需要 python3-pip 与每包独立 venv）",
            risk = "low",
        ),
        McpCatalogEntry(
            id = "mcp_pandoc",
            name = "Pandoc 文档格式转换",
            summary = "Office / Markdown / HTML / PDF 等格式互转",
            channel = McpCatalogChannel.PYPI,
            packageSpec = "mcp-pandoc==0.11.1",
            packageName = "mcp-pandoc",
            pinnedVersion = "0.11.1",
            integrity = "",
            markerPath = marker("mcp_pandoc"),
            serverArgs = listOf("-m", "mcp_pandoc"),
            installComponentId = "mcp_pandoc",
            available = false,
            unavailableReason = "pip 通道未启用，且需要沙箱内 pandoc 二进制",
            risk = "low",
        ),
    )

    /** 只包含可一键安装的 npm 能力：开发套件为每条生成一个组件。 */
    val installable: List<McpCatalogEntry> = entries.filter { it.installable }

    private val byId: Map<String, McpCatalogEntry> = entries.associateBy { it.id }

    fun find(id: String): McpCatalogEntry? = byId[id]

    /**
     * 已安装组件集合 → 就绪的 MCP 服务 id。
     * 安装状态以组件探针为唯一来源，不再单独持久化一个「是否已安装」字段，
     * 避免出现「数据库说装了、文件其实不在」这种会阻塞启动的第二真相。
     */
    fun readyServerIds(installedComponentIds: Set<String>): Set<String> =
        entries.filter { it.installComponentId in installedComponentIds }.map { it.id }.toSet()

    /** 由 catalog 条目生成开发套件组件（安装 / 探针 / 卸载全部复用既有流水线）。 */
    fun component(entry: McpCatalogEntry): PluginComponent = PluginComponent(
        id = entry.installComponentId,
        name = entry.name,
        description = entry.componentDescription,
        isRequired = false,
        aptPackages = listOf("nodejs", "npm"),
        postInstallSteps = entry.installSteps,
        checkCommand = entry.probeCommand,
        dependsOn = entry.dependsOnComponentIds,
        purgeFiles = listOf(entry.markerPath),
        postUninstallSteps = listOf(entry.uninstallCommand),
    )
}
