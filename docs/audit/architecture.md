# 架构铁律与接线完整性审计（architecture）

审计范围：`/root/City团队/DSH/tianyan-latest`（只读静态审计，未跑 Gradle/测试）
方法：全仓 `*.kt` 扫描（1643 文件）+ 定义/调用点 grep 计数 + `architecture-policy.json` 门禁规则复算。
证据口径：**「调用点 = 0」= 除声明处外全仓无引用（含表单引用、`::fn`），且排除 `src/test`。**

---

## 一、未接线功能（实现完整但零调用，P0/P1）

### P0-1 赞助支持页整条链路从未挂载
- 定义：`feature/navigation/.../AppDestinations.kt:25`（`SponsorDestination`）、`feature/settings/.../SponsorScreen.kt:73`、`SponsorListRepository.kt`、`SponsorListViewModel.kt`（共 591 行 + 入口）。
- 调用点：`SponsorDestination` **0**（既无 `entry<SponsorDestination>`，也无任何 `activeStack.push`）；`SponsorScreen` 仅被自身 Preview 引用。
- 结论：dead-but-shipped；文档 `ARCHITECTURE_RULES.md:35` 仍把「赞助支持」列为在用页面。影响：用户永远进不去，458 行 UI + 名单拉取逻辑白做。
- 建议：补 `entry<SponsorDestination>` + 在 `AboutCommunityScreen`/设置首页加真实入口；不打算上线就整体删除。

### P0-2 第二套终端渲染器（571 行）零调用
- 定义：`runtime/.../terminal/AnsiTerminalBuffer.kt:34`（完整 VT100/ANSI 视口渲染器）。
- 调用点：**0**（仅 `runtime/src/test` 单测）；线上终端走 `TermuxTerminalProcessFactory` / `RemoteTerminalProcess`（`TerminalSessionManager.kt:39`、`RemoteTerminalProcess.kt:29`）。
- 结论：两套终端实现并存，其一是死的。影响：571 行且占棘轮基线（margin 0），误导后续维护者以为主渲染路径在此。
- 建议：删除并移除基线条目；若作为降级后端保留，则必须有装配分支与开关。

### P0-3 红队「自动识别目标」整套能力零调用
- 定义：`core/model/.../RedTeamTargetExtractor.kt:13`（`extract` L44、`defaultScope` L88，111 行，含 TLD 白名单与误判防护）、`feature/chat/.../RedTeamConsole.kt:97-98`（`autoBindNotice` / `onConsumeAutoBind`）、提示卡 L143-144。
- 调用点：`RedTeamTargetExtractor` **0**；`autoBindNotice` **无生产者**（唯一 `RedTeamConsole` 调用点 `RedTeamWorkbenchSheet.kt:57` 从未传参，永远 null）；`onConsumeAutoBind` 永远不被触发。
- 结论：`RedTeamWorkbenchSheet` 式的「已修好入口、但整块面板内容死」——此处是「组件在、数据源不在」。影响：用户必须手填目标，自动绑定文案与提取器全是死码。
- 建议：在 `ChatViewModel.send`/`bindRedTeamTarget` 前置调用 `extract(text)`，把命中写入 `autoBindNotice`；或删除提取器与提示卡。

### P1-4 群聊「工作 / 工位看板」三件套零调用（762 行）
- 定义：`feature/bot/.../GroupWorkPanel.kt:45`、`GroupWorkSummary.kt:48`、`GroupCoworkingDeskBar.kt:50`。
- 调用点：均 **0**（`GroupCoworkingDeskBar` 仅 `feature/bot/src/test` 引用）。
- 结论：群聊委派进度的持久化 UI 全部未挂到 `GroupChat` 页面。影响：群委派只在消息流里裸露，看板/摘要能力用户不可见。
- 建议：在群聊顶栏挂 `GroupCoworkingDeskBar`、详情抽屉挂 `GroupWorkPanel`；弃用则删除三文件。

### P1-5 聊天工具细节 UI 七件套零调用（约 799 行）
- 定义：`ToolActivityPill.kt:82`、`ToolDetailsSheet.kt:34`、`ToolStepHeader.kt:22`、`ToolchainStripEntry.kt:21`、`ChatProcessStyle.kt:10`、`ChatTimelineFocus.kt:12`、`ChatUiHelpers.kt:11`、`AssistantMessageFooter.kt:15`、`ChatAttachmentSending.kt:22`、`ChatDialogs.kt:107`（`SessionsDialog`）。
- 调用点：全部 **0**。其中 `ToolchainStripEntry` 的宿主 `CollapsibleChatWorkbenchStrip` 已在整个仓库不存在（只在注释里被 `[]` 引用）——抽文件时宿主已被删。
- 结论：工具时间线/活动胶囊/会话对话框是被新实现替换后的残留。影响：按 M3/RuntimeCard 规范写的成品组件放在库里无人使用，且全部吃棘轮基线。
- 建议：一次性清理；`SessionsDialog` 已被 `ChatNavigationDrawer`（`TianyanNavHost.kt:690`）取代，属确定可删。

### P1-6 浏览器四项设置的「写侧」未接线（只有默认值）
- 定义：`core/datastore/.../PreferenceFacades.kt:288`（`setDefaultFamily`）、`:289`（`setHomeUrl`）、`:296`（`setDesktopUserAgent`）、`:297`（`setMaxCaptureBytes`）。
- 调用点：四个 setter **0**；读侧已接（`harness/.../BrowserMcpBootstrap.kt:89-98`、`McpServerModule.kt:34-43`）。
- 结论：读得通、写不进去 = 永远默认值。影响：首页 URL、桌面 UA、截图上限、内核家族在 App 内无法修改，等于死配置。
- 建议：设置页「浏览器」分组补四个控件；否则把 setter 与偏好键一起删，避免误导。

### P1-7 Agent 并发上限：可读不可写 + 控件零调用
- 定义：`feature/settings/.../AgentPreferenceComponents.kt:172`（`ConcurrencySliderRow`，唯一 UI）；`PreferenceFacades.kt:195`（setter）；`SettingsDataStore.kt:643`（读）。
- 调用点：`ConcurrencySliderRow` **0**、`setMaxConcurrentAgentTurns` **0**；读侧 `SessionTurnCoordinatorImpl.kt:59` 真实生效。
- 结论：运行时尊重一个用户永远改不了的偏好。影响：并发回合数锁死在默认值，调参靠手改 DataStore。
- 建议：把它挂回「智能体设置」，或把并发上限设为编译期常量并删控件。

### P2-8 其余零调用小件（逐个已核）
| 位置 | 行数 | 结论 |
|---|---|---|
| `feature/components/.../RuntimeComponents.kt:104` `RuntimeBottomBar` | — | 主页面按铁律无底栏，共享设计系统里留了死组件 |
| `feature/theme/.../Dimens.kt:108` `object Breakpoints`（含 `WindowClass`） | 20 | 第二套断点，**与铁律 3.3 冲突**（应只用 `LocalRuntimeWidthClass`/`widthClassOf`，全仓 19 处在用） |
| `feature/settings/.../PackagedPluginInventorySection.kt:24` | 80 | 设置页「打包插件清单」区块从未挂载 |
| `feature/browser/.../BrowserActionCard.kt:25` | 50 | 注释说给 `ChatToolCards` 复用，实际未被复用 |
| `runtime/browser/.../di/BrowserInitializer.kt:9` | — | 无引用（DI 装配走 Koin），疑似旧初始化残留 |
| `core/database/.../EntryTree.kt:4`、`repository/GroupTeamMapping.kt:9`（`teamFromRows`） | — | 仅测试引用；生产分支树另有实现 |
| `harness/.../mcp/oauth/OAuthPkce.kt:34`（`OAuthRedirectPolicy`） | — | 仅测试引用；`McpOAuthCoordinator.kt:54-81` 手写 scheme/host/path 比对，抽出的值对象没被采用 |
| `runtime/browser/.../cdp/CdpTransport.kt:76`（`TcpCdpTransport`） | — | 仅测试引用；若作为远程 CDP 通道则缺入口 |

---

## 二、架构违规（门禁现状 + 实际例外）

### P1-9 门禁全绿 ≠ 无违规：DAO 直连豁免覆盖 tools/app
- 事实：复算 `architectureCheck` 四条规则（依赖白名单 / 无环 / import 黑名单 / 尺寸棘轮）→ **0 violations**，38 个模块依赖图无环，无横向 feature 依赖（除设计允许的 `feature/navigation` 与 `feature/chat → feature:a2uipoc`）。
- 例外：`architecture-policy.json:69` 自述「tools 与 app 当前为存量豁免」，实际命中 6 处——`tools/.../ToolRepository.kt:3`、`InstallTaskRepository.kt:3`、`InstallLogRepository.kt:3`、`RuntimeRepository.kt:3` + `app` 2 处。
- 影响：ARCHITECTURE_RULES §2 的「严禁直接注入 Room DAO」对最需要约束的工具层并不生效。
- 建议：按 policy 注释的迁移路径把这 6 处收敛到 `PersistenceRepositories`，然后把它俩并入 `dao-direct-access`。

### P1-10 命名与门禁规则错位，违规扫描易漏
- 事实：`ChatViewModel.kt:132/133/142` 的 `sessionDao` / `aiModelDao` / `agentContextDao` 类型实为 `HarnessSessionRepository` / `AiModelRepository` / `AgentContextRepository`。
- 结论：命名看着像 DAO、类型不是；反之 `importBans` 只匹配 `^import ...core.database.*Dao`，任何写成全限定名（`ChatViewModel.kt:168` 的 `top.tianyan.app.core.database.RedTeamFactEntity`）的引用都不被扫描。
- 建议：给变量改名 `...Repository`；黑名单补一条「源码行内出现 `core.database.<X>Dao`」的规则。

### P1-11 行数棘轮已从「棘轮」退化为「冻结」，并反噬可读性
- 事实：基线 103 条中 **91 条余量 < 20 行**，**77 条余量 = 0**（如 `RedTeamPanel.kt` 406/406、`McpManager.kt` 419/419、`AnsiTerminalBuffer.kt` 571/571）。
- 结论：任何一行新增都会失败；作者被迫把多条声明挤进一行——`ChatViewModel.kt:168` 一行同时定义 `redTeamFacts` + `bindRedTeamTarget` + `redTeamConsoleController`；`RedTeamWorkbenchSheet.kt:27` 的注释也自承「ChatScreen 已顶到棘轮上限」。
- 建议：对余量=0 的 77 个文件分批拆分（先处理死码文件，如 P0-2/P1-5 的删除会直接腾出 ~1400 行额度），再 `architectureBaselineSync` 下调。

### P2-12 断点双轨 + 跨模块同名类型
- `Dimens.kt:98` `Content.dualPaneMin = 720.dp` 零引用，而铁律断点是 600/840（`widthClassOf`）；`Dimens.kt:108` 的 `Breakpoints` 同样零引用——三套断点口径并存（600/840、720、死对象）。
- 同名异义：`core.datastore.BrowserPreferences`（`PreferenceFacades.kt:278`，偏好门面）与 `core.browser.BrowserPreferences`（`core/browser/BrowserPreferences.kt:13`，不可变快照）在 `BrowserMcpBootstrap.kt:19` 被迫 `as DataStoreBrowserPreferences` 别名。
- 建议：删 720/`Breakpoints` 两套口径；把 datastore 侧改名 `BrowserPreferenceFacade`。

---

## 三、技术债（职责与重复实现）

### P1-13 `RedTeamCoordinator` 2565 行，限值 6.4 倍，单类承担 5 类职责
- 位置：`harness/.../redteam/RedTeamCoordinator.kt`（2565 行，基线 2566，余量 1）。
- 职责混装：事实写入 `record/bind`（L92/L110）、工具侧命令式分发 `execute`（L222 起 ~240 行）、控制台十页签查询 `console*`（L467-L620，10+ 个方法）、资产/图谱/评分投影、报告与模板缓存（L66-67）。
- 影响：红队是产品主推能力，任何页签改动都要碰这个文件，且它已无行数余量（只能减不能加）。
- 建议：按 `FactStore` / `ConsoleQueryService` / `RedTeamToolDispatcher` / `ReportBuilder` 四分，先把 `console*` 一组（约 160 行）无状态查询抽出。

### P1-14 `ChatViewModel`：1555 行 + 39 个构造依赖
- 位置：`feature/chat/.../ChatViewModel.kt:124-167`（39 个注入项，含可选 6 个），文件 1555/1555（余量 0）。
- 结论：聊天页所有能力（会话、红队、工作流建议、BTW、子代理、模型切换、压缩、翻译、导航事件）都挂在同一个 ViewModel。
- 建议：至少拆出 `RedTeamChatBridge`（红队四件已由 `redTeamFactsSource`/`redTeamCoordinatorRef` 承接，可直接搬到独立类）与 `SubagentChatSection`。

### P2-15 同一能力的重复实现（3 组）
- Markdown：`feature/components/.../MarkdownText.kt:136` 为真实实现，`feature/chat/.../MarkdownText.kt:10` 是 12 行同名转发壳（注释自承 backward compatibility）；缓存清理也双份（`MarkdownParser.kt:294` / `MarkdownText.kt:794`）。
- 时长格式化：`feature/chat/.../ChatProcessTimer.kt:14` `formatProcessDuration` 仅测试用，实现就是转发 `components.formatRuntimeProcessDuration`。
- 终端渲染：见 P0-2（`AnsiTerminalBuffer` vs termux 后端）。
- 建议：删除转发壳（改 import），`feature:chat` 不再导出同名 `MarkdownText`；顺带清掉一个已死的 markdown 缓存入口。

### P2-16 设置搜索落点错误（可用性 bug，顺手记）
- `TianyanNavHost.kt:337-338`：`SettingsSearchTarget.WEB_CHAT` 与 `PRIVILEGE_MODE` 合并跳 `LinuxEnvSettingsDestination`。
- 影响：搜「网页对话/Web Chat」落到 Linux 环境页，属指向错误的死链。
- 建议：`WEB_CHAT` 独立落点（或在枚举里删掉该目标）。

---

## 最值得先修的 5 条

1. **P0-1 赞助页整链未接线**——文档说在用、代码无入口，是「已发布但用户看不见」的最典型破坏，修 1 处路由即可（`TianyanNavHost` 加 `entry<SponsorDestination>` + 一个入口）。
2. **P0-3 红队自动绑定目标**——`RedTeamTargetExtractor`（111 行，含误判防护）+ `autoBindNotice` UI 全死；接 2 行调用即恢复「一句话开练」，收益/成本比最高。
3. **P0-2 / P1-5 删除死码换棘轮额度**——`AnsiTerminalBuffer` 571 行 + 聊天工具 UI ~799 行 + 群聊三件套 762 行，删完直接给 77 个「余量 0 冻结文件」腾出重构空间（先拆 P1-13/P1-14）。
4. **P1-6/P1-7 死配置回接**——浏览器四项 + Agent 并发上限都是「读得通、写不进」，用户感知为"改了没用/根本没得改"，补 UI 或删偏好键（别留半条）。
5. **P1-9/P1-11 门禁动真格**——先按 policy 注释把 tools/app 的 6 处 DAO 直连收敛，再让余量=0 的文件随拆分解冻；否则「架构铁律」只剩文档效力。
