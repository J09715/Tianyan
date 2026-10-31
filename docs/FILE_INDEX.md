# 📚 天衍 (Tianyan) — 关键文件索引速查 (File Index)

> 用于 AI 编码助手快速定位某个功能/类。详细架构细节见 [`ARCHITECTURE.md`](ARCHITECTURE.md)。

---

## 插件平台（P1 页面扩展 / P2 生命周期）

P5 静态 Web 页面入口：[`PLUGIN_WEB_EXTENSIONS.md`](PLUGIN_WEB_EXTENSIONS.md)；[`WebPagePlugin.kt`](../plugin/ui/src/main/java/top/tianyan/app/plugin/ui/web/WebPagePlugin.kt) 提供编译时适配，[`WebPluginView.kt`](../plugin/ui/src/main/java/top/tianyan/app/plugin/ui/web/WebPluginView.kt) 提供专用 WebView，[`PluginCapabilitySession.kt`](../plugin/runtime/src/main/kotlin/top/tianyan/app/plugin/runtime/PluginCapabilitySession.kt) 管理文档授权和撤销。

外部 WEB 包：[`WebPluginPackagePolicy.kt`](../plugin/packages/src/main/kotlin/top/tianyan/app/plugin/packages/WebPluginPackagePolicy.kt) 校验静态包边界；[`WebPluginRepository.kt`](../tools/src/main/java/top/tianyan/app/core/tools/WebPluginRepository.kt) 负责导入、当前版本与完整性快照；[`WebPluginsScreen.kt`](../feature/settings/src/main/java/top/tianyan/app/ui/settings/WebPluginsScreen.kt) 提供管理入口；[`package-web-example.ps1`](../tools/package-web-example.ps1) 生成可导入示例。

| 关键文件 | 职责 |
| --- | --- |
| [`PLUGIN_PLATFORM.md`](PLUGIN_PLATFORM.md) | 内核与插件边界、三种执行形态、授权设计与 P1–P6 路线 |
| [`PLUGIN_HARNESS_EXTENSIONS.md`](PLUGIN_HARNESS_EXTENSIONS.md) | 编译时工具/上下文/Provider/工作流契约、审批边界、Schema 子集与示例 |
| [`HarnessPluginRegistry.kt`](../harness/src/main/java/top/tianyan/app/harness/plugins/HarnessPluginRegistry.kt) / [`BuiltinHarnessPlugins.kt`](../harness/src/main/java/top/tianyan/app/harness/plugins/BuiltinHarnessPlugins.kt) | 受信任 Harness 扩展装配与内置协议/节点迁移 |
| [`PluginNodeExecutor.kt`](../harness/src/main/java/top/tianyan/app/harness/plugins/PluginNodeExecutor.kt) / [`PluginSchemas.kt`](../harness/src/main/java/top/tianyan/app/harness/plugins/PluginSchemas.kt) | 通用插件节点审批与严格参数校验 |
| [`PLUGIN_PACKAGE_V2.md`](PLUGIN_PACKAGE_V2.md) | v2 PROCESS 包格式、版本依赖、资源声明、事务边界与示例 |
| [`PluginPackageProtocol.kt`](../plugin/packages/src/main/kotlin/top/tianyan/app/plugin/packages/PluginPackageProtocol.kt) / [`PackageInstallPolicy.kt`](../plugin/packages/src/main/kotlin/top/tianyan/app/plugin/packages/PackageInstallPolicy.kt) | 严格版本分派、SemVer 依赖与安装收据冲突检查 |
| [`LocalPluginArchive.kt`](../tools/src/main/java/top/tianyan/app/core/tools/LocalPluginArchive.kt) / [`LocalPluginStore.kt`](../tools/src/main/java/top/tianyan/app/core/tools/LocalPluginStore.kt) | 有界 ZIP 校验、完整性清单、不可覆盖版本与原子选中指针 |
| [`ToolPackageOperations.kt`](../tools/src/main/java/top/tianyan/app/core/tools/ToolPackageOperations.kt) / [`PluginReceiptStore.kt`](../tools/src/main/java/top/tianyan/app/core/tools/PluginReceiptStore.kt) | 串行安装/卸载检查、最小版本收据与旧工具迁移 |
| [`PackagePluginInventory.kt`](../tools/src/main/java/top/tianyan/app/core/tools/PackagePluginInventory.kt) / [`PackagedPluginInventorySection.kt`](../feature/settings/src/main/java/top/tianyan/app/ui/settings/PackagedPluginInventorySection.kt) | 当前发行版统一包目录与工具详情入口 |
| [`PluginDescriptor.kt`](../plugin/api/src/main/kotlin/top/tianyan/app/plugin/api/PluginDescriptor.kt) / [`PluginCatalog.kt`](../plugin/api/src/main/kotlin/top/tianyan/app/plugin/api/PluginCatalog.kt) | 平台无关身份、API 区间、页面扩展引用与依赖图校验 |
| [`NativePluginPageRegistry.kt`](../plugin/ui/src/main/java/top/tianyan/app/plugin/ui/NativePluginPageRegistry.kt) / [`PluginPageHost.kt`](../plugin/ui/src/main/java/top/tianyan/app/plugin/ui/PluginPageHost.kt) | 编译时页面实现一致性校验与 Compose 渲染，不新增导航/VM owner |
| [`TerminalPlugin.kt`](../feature/terminal/src/main/java/top/tianyan/app/ui/terminal/TerminalPlugin.kt) | 首个内置页面插件，适配项目参数与嵌入模式 |
| [`BrowserPlugin.kt`](../feature/browser/src/main/java/top/tianyan/app/ui/browser/BrowserPlugin.kt) | 第二个界面插件，复用共享 ViewModel 与原嵌入退出语义 |
| [`PluginRuntime.kt`](../plugin/runtime/src/main/kotlin/top/tianyan/app/plugin/runtime/PluginRuntime.kt) / [`PluginActivation.kt`](../plugin/runtime/src/main/kotlin/top/tianyan/app/plugin/runtime/PluginActivation.kt) | 依赖启停、代次准入、资源与协程排空、失败保留与重试 |
| [`PluginPreferences.kt`](../core/datastore/src/main/java/top/tianyan/app/core/datastore/PluginPreferences.kt) / [`PluginManagerScreen.kt`](../plugin/ui/src/main/java/top/tianyan/app/plugin/ui/PluginManagerScreen.kt) | 启用选择持久化、统一管理与失败反馈 |
| [`NativePluginPlatform.kt`](../feature/navigation/src/main/java/top/tianyan/app/ui/navigation/NativePluginPlatform.kt) / [`PluginNavigationShell.kt`](../feature/navigation/src/main/java/top/tianyan/app/ui/navigation/PluginNavigationShell.kt) | 宿主插件集合、控制器装配与菜单/设置贡献注入 |
| [`AppDestinations.kt`](../feature/navigation/src/main/java/top/tianyan/app/ui/navigation/AppDestinations.kt) / [`PluginNavigationPages.kt`](../feature/navigation/src/main/java/top/tianyan/app/ui/navigation/PluginNavigationPages.kt) | 通用插件路由、旧终端路由和聊天面板适配 |

## 🤖 Agent Harness（核心调度）

| 模块 | 关键文件 | 职责 |
| --- | --- | --- |
| `harness/HarnessLoop.kt` | 主循环，多会话并发 | Agent 主循环 |
| `harness/session/SessionControl.kt` / `InteractiveSessionControl.kt` | 共享控制与前台交互契约，同一执行 singleton | 会话控制接口 |
| `harness/session/SessionInputController.kt` | 锁内输入接收、任务/队列提交、启动/排队/拒绝回执 | 接收屏障 |
| `runtime/webchat/WebChatRunProtocol.kt` / `app/.../webchat/TianyanWebChatAgentGateway.kt` | REST 输入模式、回执与错误分类；显式会话控制适配 | Web 控制边界 |
| `runtime/webchat/WebChatRunTracker.kt` / `webchat/src/taskEvents.ts` | 按关联 ID 跟踪持久化任务结果；客户端会话/任务事件隔离 | Web 结果观察 |
| `harness/core/.../AgentTurnRunner.kt` / `TurnContracts.kt` | 纯 Kotlin 轮次状态机、`prepareRequest` / `finishTurn`、预算与追问调度 | 可复用轮次核心 |
| `harness/core/.../DurableToolRunner.kt` | 意图提交 → 执行 → 结果提交；区分提交失败与工具失败 | 工具持久化屏障 |
| `harness/core/.../ToolBackend.kt` / `ToolCheckpoints.kt` | 泛型工具后端、按序等待的前置否决与后置说明；异常和取消规则 | 纯 Kotlin 工具契约 |
| `harness/core/.../SessionProjection.kt` | 存储无关的保留消息、并发补回、摘要与召回贡献规则；来源和诊断类型 | 纯 Kotlin 会话投影 |
| `harness/session/SessionContextProjector.kt` | 捕获 Lane 叶子，解码快照并选择分支窗口；检查与模型上下文共用来源 | 可检查上下文 |
| `harness/compaction/CompactionManager.kt` | 压缩持久化、`inspect` / `project` 入口与轻量摘要横幅快照 | 压缩编排 |
| `harness/TurnRunner.kt` | 将 Provider 响应与文本工具协议适配到轮次核心 | 协议适配 |
| `harness/core/.../ModelDescriptor.kt` / `harness/ModelConfig.kt` | 协议标识、无凭证能力描述；兼容现有模型档案字段 | 模型能力 |
| `harness/ProviderModelResolver.kt` | 档案/variant 选择、密钥读取、上下文容量和全局推理偏好 | 模型解析 |
| `harness/LlmApiAdapter.kt` / `ProviderTransport.kt` | 三种协议统一入口、密钥轮换、超时、诊断和取消语义 | 请求边界 |
| `harness/subagent/SubagentToolRoundRunner.kt` | Lane 工具执行、写租约与审批移交证据，共用持久化屏障 | 子任务工具回合 |
| `harness/HarnessProviderRunner.kt` | 模型能力选择、流式请求与重试、助手回复结算 | 模型回合 |
| `harness/diagnostics/*` | 最终请求体脱敏快照、内存保留预算；聊天圆环「最近请求上下文」入口 | 请求诊断（详见 [CONTEXT_DIAGNOSTICS.md](CONTEXT_DIAGNOSTICS.md)） |
| `harness/ResponsesApi.kt` / `ResponsesRequestBuilder.kt` / `ResponsesTurn.kt` | 原生 output 保存与回放、作用域校验、调用 ID 映射；主会话与子智能体共用投影 | Responses 历史（详见 [RESPONSES_HISTORY.md](RESPONSES_HISTORY.md)） |
| `harness/HarnessToolRoundRunner.kt` | 工具参数校验、单轮限额、执行与审批暂停 | 工具回合 |
| `harness/HarnessWorkspaceRecommendations.kt` | 工作区路径边界、MCP 推荐扫描与前台投影 | MCP 推荐 |
| `harness/ToolExecutor.kt` | 内置工具策略与分派；文件操作转交后端，保留审批和 PLAN 门控 | 工具执行门面 |
| `harness/ToolRecoveryAdvice.kt` | 工具失败后的结构化行动建议，持久化后供模型投影使用 | 失败恢复提示 |
| `harness/operation/FailureMemory.kt` / `FailureMemoryJournal.kt` | 与结果原子保存的运行内失败事实、指纹、TTL 与低权威背景投影 | 失败记忆 |
| `harness/TaskDelivery.kt` / `harness/task/TaskDeliveryBuilder.kt` / `TaskDeliveryReport.kt` | 本轮工具证据、缺口和文件版本引用；与任务完成一起原子落库 | 任务交付记录 |
| `harness/ToolOutputArtifact.kt` / `ToolFileArtifacts.kt` / `feature/chat/.../TaskDeliveryCard.kt` | 脱敏日志、实际 APK/图片/下载文件 SHA-256 观察及未验证交付卡片 | 文件版本与展示 |
| `harness/BuildArtifactObserver.kt` | 标准 APK 目录有界前后扫描；区分新增、变化、已有和不完整观察 | 构建产物观察 |
| `runtime/environment/FileFingerprint.kt` / `harness/environment/LocalFileFingerprint.kt` / `runtime/remote/SshExecutionFiles.kt` | 当前执行环境的流式二进制指纹、容量与并发变化检查；缺少能力不回退 | 文件指纹 |
| `harness/ToolExecutionBoundary.kt` / `ToolExecutionRequest.kt` | 检查点观察快照、否决及说明脱敏；保留结果标识与审批状态 | 扩展控制边界 |
| `harness/WorkspaceToolBackend.kt` / `WorkspaceToolOperations.kt` | read/write/edit 语义与可替换工作区操作接口；本地实现为 `WorkspaceFileAccess` | 文件工具后端 |
| `harness/WorkspaceMutationSnapshots.kt` | 写入前后快照与大小保护，文件后端和下载工具复用 | 写入恢复证据 |
| `harness/di/harness/ToolBackendModule.kt` | 工具门面、默认检查点、工作区后端与快照的 Koin 装配 | 工具依赖装配 |
| `harness/ApprovalPolicyEngine.kt` | 工具调用的审批策略（normal / high / critical 三档） |
| `harness/ToolRoundDispatcher.kt` | 单回合多工具并发调度（mutation 互斥 / read-only 4 并发） |
| `harness/SubagentOrchestrator.kt` | 子智能体 Lane 编排 |
| `harness/mcp/*` | MCP 协议：`McpManager` / `McpHttpTransport` / `McpJsonRpc` |
| `harness/browser/BrowserMcpBootstrap.kt` | 内置 Browser MCP Server 启动 + 注册引擎 |
| `harness/mcp/server/*` | in-process MCP Server：`McpServerRuntime` / Auth / Tool+Resource Dispatcher |
| `harness/HarnessMessage.kt` | `HarnessTool` 枚举 + `ToolResult` (含 `imageAttachments`) |

## 💬 聊天与主导航

聊天与主导航：`feature/navigation/.../TianyanNavHost.kt` 默认进入智枢，保留各主页面独立返回栈；`feature/chat/.../ChatNavigationDrawer.kt` 共用 Activity 级会话模型并整合会话与功能入口；`feature/components/.../AppNavigation.kt` 提供导航上下文和主入口列表。`ChatTopBar.kt` / `ChatWorkbenchControls.kt` 实现会话、模型、工作区与按需展开的控制；`ChatWelcome.kt` 提供项目感知的欢迎与预填任务示例。原底部导航组件仅保留组件本身，不在主页面渲染。

旁路问答（/btw）：[`BtwService.kt`](../harness/src/main/java/top/tianyan/app/harness/btw/BtwService.kt) 快照转录+同构单次调用+用量落账；[`BtwChatController.kt`](../feature/chat/src/main/java/top/tianyan/app/ui/chat/BtwChatController.kt) 会话内状态与收割；[`BtwPanel.kt`](../feature/chat/src/main/java/top/tianyan/app/ui/chat/BtwPanel.kt) 用户可见卡片；[`BtwQueryEntity.kt`](../core/database/src/main/java/top/tianyan/app/core/database/BtwQueryEntity.kt) 记录表与仓储。上下文管家（压缩 vs 换段判定）：[`CompactionSteward.kt`](../harness/src/main/java/top/tianyan/app/harness/compaction/CompactionSteward.kt)。Bot 能力白名单端口：[`BotCapabilityScope.kt`](../harness/src/main/java/top/tianyan/app/harness/bot/BotCapabilityScope.kt)。让步总账：[`CONCESSIONS_LEDGER.md`](CONCESSIONS_LEDGER.md)。

窗口三态适配：`feature/components/.../RuntimeWindowSize.kt` 定义 `RuntimeWidthClass`（Compact/Medium/Expanded，600/840dp）、`LocalRuntimeWidthClass` 与共享动画规格，由 `MainActivity` 在页面缩放密度内注入；`RuntimeAdaptiveNavigationScaffold.kt` 按三态切换推拉抽屉 / Rail+浮层侧栏 / 停靠侧栏（可收起为 Rail），页面与侧栏以 movable content 迁移；`RuntimeNavigationRail.kt` 为中间态与展开态图标栏；`RuntimeAdaptiveContent.kt` 提供 `LocalRuntimePageWidth`、`runtimeContentPadding()`（宽屏内容最大 840dp 居中）与网格 `fullWidthItem`。`feature/chat/.../ChatAdaptiveLayout.kt` 负责智枢伴随面板：紧凑态浏览器整页、中间态右侧滑出、展开态可拖拽可收起双栏。

## 🗂️ 模型档案迁移

模型档案的备份与迁移见 [MODEL_PROFILE_TRANSFER.md](MODEL_PROFILE_TRANSFER.md)：`tools/AiProfileBackupCodec.kt` / `AiProfileTransferFormat.kt` / `AiProfileImportWriter.kt`，持久化端口为 `core/database/AiModelRepository.kt`，界面为 `feature/settings/ModelImportDialog.kt` / `ModelExportDialog.kt`。

## 🌐 内置浏览器（Browser）

| 模块 | 关键文件 | 职责 |
| --- | --- | --- |
| `core/browser/...` | `BrowserFamily / Risk / Capability / SelectionPolicy / Preferences / FileOps` | Pure Kotlin 模型 + 策略 |
| `runtime/browser/BrowserRegistry.kt` | 浏览器注册中心 interface | 多家族管理 |
| `runtime/browser/BrowserRegistryImpl.kt` | 单一 in-app WebView 实现 | 注册 / 启动 / 选 family |
| `runtime/browser/BrowserEngine.kt` | 引擎操作 interface（24 个动作）| 抽象所有浏览器动作 |
| `runtime/browser/AndroidInAppBrowserEngine.kt` | in-app WebView 引擎实现 | 全部动作落地 |
| `runtime/browser/engine/WebViewTabPool.kt` | 多 tab 复用池 | 主线程 + StateFlow |
| `runtime/browser/snapshot/SnapshotBuilder.kt` | DOM 扫描脚本 + ref 注入 | PageSnapshot 生成 |
| `runtime/browser/screenshot/ScreenshotRecorder.kt` | `view.draw` 软渲截图落 PNG | ToolImageRef |
| `runtime/browser/network/NetworkInterceptor.kt` | `shouldInterceptRequest` 拦截 | CapturedRequest |
| `runtime/browser/storage/StorageController.kt` | Cookie + local/session 操作 | WebView eval |
| `runtime/browser/secret/SecretRedactingInterceptor.kt` | 接入现有 `SecretRedactor` | 工具产物脱敏 |
| `runtime/browser/hook/HookRuleStore.kt` | Hook 规则存储（线程安全） | 规则 CRUD + payload 生成 |
| `runtime/browser/hook/HookInstaller.kt` | `TianyanBridge` + document-start 注入 | 页面侧 runtime 安装 |
| `runtime/browser/hook/HookEventPipeline.kt` | 桥事件 → 事件总线 | hook 命中/网络捕获合并 |
| `runtime/browser/hook/NetworkBodyStore.kt` | 请求/响应体 LRU 缓存 | 字节预算内 body 存取 |
| `runtime/browser/hook/hook_runtime.js`（assets） | 页面侧 fetch/XHR/fn/prop 拦截 | 网络改写 + 函数 hook |
| `runtime/browser/cdp/CdpTransport.kt` | LocalSocket → DevTools socket 传输 | `webview_devtools_remote_<pid>` |
| `runtime/browser/cdp/CdpSession.kt` | WS 帧编解码 + 命令关联/事件分发 | CDP JSON-RPC 会话 |
| `runtime/browser/cdp/CdpManager.kt` | attach 生命周期 + socket 引用计数 | `setWebContentsDebuggingEnabled` |
| `runtime/browser/cdp/CdpTabConnection.kt` | 单 tab 连接（Debugger + Fetch + Worker 子会话） | 断点/拦截路由 |
| `runtime/browser/cdp/CdpDebugController.kt` | 真断点/暂停/单步/作用域/求值 | JS 调试状态机 |
| `runtime/browser/cdp/CdpFetchInterceptor.kt` | `Fetch.requestPaused` 引擎级拦截 | Worker/子资源网络改写 |
| `runtime/browser/tools/BrowserMcpTools.kt` | `mcp__browser__*` 工具分派 + 风险等级 | 50+ tools（hook_*/debug_* 门禁） |
| `runtime/browser/tools/BrowserMcpResources.kt` | `browser://*` resources | 6 resources |
| `feature/browser/BrowserScreen.kt` | 内置浏览器 Compose 主屏 | UI 入口 |
| `feature/browser/BrowserViewModel.kt` | 持有 Registry + EventBus + Snapshot State | 状态 |
| `feature/browser/BrowserActionCard.kt` | 给 Chat 复用的产物卡（缩略图） | 跨模块复用 |
| `feature/browser/BrowserNavRoute.kt` | BrowserRoute 常量与跳转助手 | 路由入口 |
| `docs/BROWSER_DESIGN.md` | 内置浏览器设计文档 | 决策 + ADR |

## 🖥️ Linux 运行时（PRoot）

| 模块 | 关键文件 | 职责 |
| --- | --- | --- |
| `runtime/.../LinuxRuntime.kt` | PRoot 启动入口 / `base` 命令面板 | 命令执行边界 |
| `runtime/.../ProcessRegistry.kt` | `process` 命令的 PID / 日志环形缓冲 | 后台进程托管 |
| `runtime/.../shell/CommandOutputCapture.kt` | 有界前台命令流落盘，容量和不完整输出标记；结束时清理私有缓存 | 长输出保留 |
| `runtime/.../shell/CommandResourcePolicy.kt` / `ForegroundCommandAdmission.kt` | 已知重型前台命令的独立资源分类、共享 FIFO 名额与取消 | 本地资源调度 |
| `runtime/.../ProotCommandBuilder.kt` | `-b` 挂载点规范化 + Shell 注入防护 | 安全 |
| `runtime/.../WorkspaceFileService.kt` | 工作区读/写/搜/hash/zip/share | 与 file.* 工具对齐 |
| `runtime/.../shell/VT100.kt` | 终端 VT100 状态机 | 终端渲染 |

## 📱 内置无线 ADB 与 Logcat

| 模块 | 关键文件 | 职责 |
| --- | --- | --- |
| `runtime/.../bridge/adb/EmbeddedAdbManager.kt` | Kadb 客户端 + mDNS 发现 + 持久密钥 | 自动发现 `_adb-tls-pairing` / `_adb-tls-connect`、一次配对、自动重连、Logcat 抓取 |
| `runtime/.../bridge/HostBridge.kt` | 沙箱 HTTP 桥接 (127.0.0.1:7980) | 提供 `/api/logcat`、`/api/shell`（内置无线 ADB 回退）与静默 APK 安装 |
| `app/src/main/assets/bin/logcat-grabber` | 沙箱内置 CLI 日志工具 | `logcat-grabber` / `logcat-tail` / `logcat-export` 脚本资产 |
| `feature/developer/.../AdbLogcatScreen.kt` | 独立无线 ADB 与日志工作台 | 系统保活与诊断一级直达：配对码输入、mDNS 探测、多维 Logcat 过滤与复制 |
| `feature/developer/.../DeveloperScreen.kt` | 开发者控制台 | 包含底层健康监控、无线 ADB 状态卡片、工具源更新等 |
| `harness/ToolExecutor.kt` | `host.logcat` 分派 | 优先使用无线 ADB，失败后回退 Shizuku/Root |

## 🌿 Git 分支管理（feature:git）

| 模块 | 关键文件 | 职责 |
| --- | --- | --- |
| `feature/git/.../GitManager.kt` | JGit 封装（MGit 同款技术栈） | 分支列表/切换/新建/删除、提交树泳道算法、push/pull 进度、友好错误映射 |
| `feature/git/.../GitCredentialsStore.kt` | 按 host 的 HTTPS 凭据存储 | Token 经 SecretManager（AndroidKeyStore AES/GCM）加密后落 JSON |
| `feature/git/.../GitViewModel.kt` | GitScreen 状态机 | 项目绑定 / 操作互斥 / 进度上抛 |
| `feature/git/.../GitScreen.kt` | 分支管理页（分支+提交记录双页签） | 入口：智枢顶部工具条「仓库」 |
| `feature/git/.../GitCommitGraph.kt` | Canvas 泳道提交图 | 穿线/合并/分叉斜线 + 多色节点 |
| `feature/chat/.../ChatWorkbenchPanels.kt` | 顶部工具条「仓库」入口 | `onOpenRepository` 可选回调模式 |
| `feature/navigation/.../TianyanNavHost.kt` | `GitRepositoryDestination(projectName)` | 路由注册 |

> JGit 在宿主侧直接打开工作区仓库（`RepositoryBuilder` + 空的 system/user 配置规避 Android 路径问题），不依赖沙箱内 git 安装。

## 💾 本地数据备份与恢复

完整说明见 [LOCAL_BACKUP.md](LOCAL_BACKUP.md)。

| 文件 | 说明 |
|---|---|
| `tools/backup/LocalBackupService.kt` | 备份/恢复协调器、原子恢复日志、启动挂单恢复 |
| `tools/backup/BackupArchive.kt` | ZIP 归档读写、路径安全校验、SHA-256 摘要 |
| `tools/backup/BackupResources.kt` | 资源文件收集与路径重写 |
| `core/database/BackupRecordPolicy.kt` | 可备份表清单、运行时字段归零、记录差集 |
| `core/database/BackupRestoreReceipt.kt` | 恢复收据实体 + DB Migration 53→54 |
| `core/database/DatabaseBackupRepository.kt` | Room 事务级快照、结构校验和合并 |
| `core/datastore/BackupPreferences.kt` | 可移植偏好快照、校验、原子回滚 |
| `core/model/LocalBackup.kt` | `LocalBackupManifest` / `BackupRecords` / `BackupPreview` 数据类 |
| `feature/settings/LocalBackupDialog.kt` | 导出/导入 UI（含预览、偏好复选框）|
| `feature/settings/LocalBackupViewModel.kt` | 备份 UI ViewModel |
| `app/BackupStartupRecovery.kt` | Application 启动检查并完成挂起恢复 |

- `runtime/src/main/java/top/tianyan/app/runtime/bridge/PluginHostGateway.kt`：外部 Web 原生批准后的短期单次宿主凭据、身份绑定、撤销与 APP_INFO 代理。
- `runtime/src/main/java/top/tianyan/app/runtime/bridge/HostBridgeHttp.kt`：有界 HTTP 字节分帧与响应写入。

- `runtime/src/main/java/top/tianyan/app/runtime/bridge/PluginHostClipboard.kt`：原生确认后的纯文本剪贴板写入，不读取旧值。
- `runtime/src/main/java/top/tianyan/app/runtime/bridge/FullAccessGrant.kt`：开发者全权模式的应用层门控，缺省 fail-safe；仅持久开发者模式开启时把沙箱凭据动作集（默认 `host.install-apk,host.logcat`）升到含 `host.shell`，关闭即下次签发降权。
- `plugin/runtime/src/main/kotlin/top/tianyan/app/plugin/runtime/PluginCapabilityInput.kt`：能力输入类型与文本容量校验。
- `tools/examples/web-clipboard/`：逐次授权复制文本的离线 Web 示例源文件。

- `runtime/src/main/java/top/tianyan/app/runtime/service/ManagedWebAssets.kt`：固定 loopback 目标、哈希与配额校验的服务静态快照读取。
- `runtime/src/main/java/top/tianyan/app/runtime/service/LoopbackWebAssetService.kt`：按页面创建与回收的宿主示例资源服务。
- `plugin/ui/src/main/java/top/tianyan/app/plugin/ui/web/WebPluginSourceOwner.kt`：预留清理记录、取得与取消/排空期间归还页面资源。
- `plugin/ui/src/main/java/top/tianyan/app/plugin/ui/web/WebPluginAssetLease.kt`：来源可用性、快照与幂等释放。

- `runtime/src/main/java/top/tianyan/app/runtime/service/ProotWebAssetService.kt`：准备态租约、固定发行版、管道启动配置及精确实例回收的 PRoot 静态来源。
- `runtime/src/main/java/top/tianyan/app/runtime/service/ProotWebAssetProgram.kt`：宿主固定 Python 标准库资源程序，不接受包脚本。
- `feature/settings/src/main/java/top/tianyan/app/ui/settings/PackagedWebPluginSources.kt`：按外部 WEB 选中版本校验静态资源，创建页面自有的惰性 PRoot 来源租约。
- `runtime/src/main/java/top/tianyan/app/runtime/service/ProotWebBootstrap.kt`：预览与启动共用的确定容量编码，定长认证头及有序资源压缩。
- `tools/src/main/java/top/tianyan/app/core/tools/ProotWebPackagePreflight.kt`：PRoot 包完整预览、容量复核和临时文件清理，不分配进程。

- `runtime/src/main/java/top/tianyan/app/runtime/service/IndexedManagedWebAssets.kt`：已运行工具服务的按需资源代理，首次响应钉住 SHA-256，漂移/超限即整源关闭。
- `runtime/src/main/java/top/tianyan/app/runtime/service/ToolServiceWebSource.kt`：`ToolServiceDescriptor` 原生描述 + 本地后端门控 + 有界探活的工具服务页面来源。
- `feature/settings/src/main/java/top/tianyan/app/ui/settings/ToolServicePluginScreen.kt`：宿主注册的只读工具服务页面（`tianyan.tool-service`），登记单次读与分块读两个只读能力。
- `feature/navigation/src/main/java/top/tianyan/app/ui/navigation/ToolDetailRoute.kt`：工具详情导航条目装配，含插件页打开回调。
- `feature/settings/src/main/java/top/tianyan/app/ui/settings/ToolDetailAvatar.kt`：工具详情头像组件，为守尺寸棘轮自 `ToolDetailScreen.kt` 抽出。
- `runtime/src/main/java/top/tianyan/app/runtime/service/ProotToolServiceSource.kt`：`ProotServiceDescriptor`/`ServiceLaunch` 原生描述、就绪探测与精确实例回收，页面自启工具服务的 PRoot 来源。
- `tools/src/main/java/top/tianyan/app/core/tools/ToolInstallEventMapping.kt`：安装事件脱敏、日志映射与卸载结果归一，为守尺寸棘轮自 `ToolManager.kt` 抽出（951 → 923）。

- `runtime/src/main/java/top/tianyan/app/runtime/service/ManagedWebServiceClient.kt`：页面自持服务的原生有界动态读取/分块流，路径与预算受限，端口与凭据留在原生侧。
- `plugin/ui/src/main/java/top/tianyan/app/plugin/ui/web/WebPluginStreamBridge.kt`：同一请求 id 下的分块推送桥，单块与单流预算受限，关闭即停止生产者。
- `plugin/ui/src/main/java/top/tianyan/app/plugin/ui/web/WebPluginServiceCapabilities.kt`：宿主登记的 `host.service-request` / `host.service-stream` 只读能力工厂。
- `plugin/runtime/src/main/kotlin/top/tianyan/app/plugin/runtime/PluginCapabilityInput.kt`：能力入站校验与相对子路径白名单（含 `SERVICE_ACTIONS`）。
