# 🛡️ 天衍 (Tianyan) — 架构铁律与 AI 编码避坑指南 (Architecture & UX/UI Rules)

---

## 1. 纯粹模型层隔离 (Model Layer Purity)

- `:core:model` **严禁** 引入 `android.*`、`androidx.*` 或 Compose 依赖，必须保持 Pure Kotlin。
- `:harness:core` 同样保持 Pure Kotlin，只承载轮次解释器、执行契约与存储无关的会话投影规则；不得依赖 Android、网络客户端、DI、数据库或运行时实现。工具副作用必须在意图提交完成后执行，结果提交完成后才能进入下一轮。
- `architectureCheck` 已接入 `app:preBuild` 验证阶段，会主动阻止模型层平台化、非法 feature 横向依赖和业务层直连 DAO。
- **治理策略即代码**：上述门禁的全部规则登记在根目录 [`architecture-policy.json`](../architecture-policy.json)（模块依赖白名单 `requires`、依赖环检查、`importBans` import 黑名单、`maxFileLines` 文件尺寸棘轮）。该文件是唯一事实源——**新增模块或调整模块依赖时必须同步登记**，否则门禁失败。
- 存量超限文件（>400 行）登记在 `.architecture-baseline.json` 棘轮基线：行数**只许缩减不许上涨**；新文件必须合规。缩减代码后运行 `architectureBaselineSync` 下调基线。
- PR 的 `architecture-baseline.yml` 使用可信目标 commit 检查基线新增/上涨、全局上限放宽及已缩减源码回涨；旧基线尚未同步也不能恢复已偿还技术债。只读任务 `architectureBaselineCheck` 提示可收紧条目，不自动写入基线；普通 Android 构建不新增 Python 依赖。

---

## 2. 持久化与偏好边界 (Persistence & Preference Boundaries)

- 业务层（feature / runtime / harness）**只能依赖** `core/database/PersistenceRepositories.kt` 暴露的 Repository 接口，严禁直接注入或操作 Room DAO。
- 偏好设置按领域统一通过 `core/datastore/PreferenceFacades.kt` 提供的领域分面接口读取或写入，严禁随意引入私人键值。

---

## 3. 🎨 M3 Expressive 与 UX/UI 设计系统与前端架构铁律

1. **统一设计系统组件（严禁 raw Material 组件散落）**：
   - 容器必须使用 `top.tianyan.app.ui.components.RuntimeCard`（支持 `containerColor`, `borderColor`, `contentPadding`, `onClick`），**严禁**使用原生 `androidx.compose.material3.Card` 或自定义 `Modifier.border/background` 拼接容器，确保 Material You（标准 M3）主题下表面色与微边框统一生效。
   - 按钮统一使用 `RuntimeButton`, `RuntimeFilledTonalButton`, `RuntimeOutlinedButton`, `RuntimeTextButton`, `RuntimeIconButton`。
   - 模型接入与档案主表单复用 `RuntimeProviderCard`（整卡选择预设）与 `RuntimeFilledTextField`（原生 Material3 填充式 TextField，保留浮动标签、焦点和错误指示）；连接信息合并分组，避免每个输入框外再独立套一张卡片。
   - 弹窗统一使用 `top.tianyan.app.ui.components.RuntimeAlertDialog`。
   - 开关与进度指示使用 `RuntimeSwitch`, `RuntimeLinearProgressIndicator`, `RuntimeCircularProgressIndicator`（后两者默认参数即 Expressive 波浪/形变加载实现；线性显式传 `progress` 时回落经典确定性样式，需要确定性波浪时传 `wavy = true`；圆环显式传 `strokeWidth`/`trackColor` 时回落经典确定性样式）。
2. **弹窗与表单软键盘安全（IME 防遮挡）**：
   - 所有包含 `OutlinedTextField` 或列表的 Dialog `text` 内容区域，**严禁**设置死锁的固定高宽或外层硬编码 `offset`，**必须**包裹 `modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())`，确保在移动端软键盘弹起时表单可顺畅滑动，操作按钮始终可见可达。
3. **顶部与主导航规范**：
   - **聊天背景**：在“外观与终端”中使用系统照片选择器更换或移除图片，预览与聊天页共用 `RuntimeChatBackground`；聊天页由 `ChatPageSurface` 在内容内边距之外统一绘制全屏背景，覆盖顶栏和系统栏区域，不在消息面板重复绘制；有背景时使用主题提供的半透明表面承托顶栏、输入区与 AI 正文，并关闭消息列表的纯色渐变遮罩；可见度与模糊参数通过 `ChatBackgroundPreferences` 持久化。图片复制到 App 私有目录，仅作为界面背景，不自动附加到模型请求；终端和浏览器面板不使用聊天背景。
   - 顶部导航统一使用 `RuntimeTopBar`（支持 `title`, `statusText`, `onBack`）。设置首页、外观、智能体、Linux 与诊断子页使用带 `scrollBehavior` 的大标题重载，经 `PersonalizationScaffold` 委托共用 `RuntimeSettingsScaffold` 连接滚动；工坊、环境变量、SSH/FTP、本地模型、Linux 系统管理、存储、关于、赞助支持、应用管理、权限向导及工具配置详情复用同一布局，统一宽屏居中与 IME 避让；状态摘要、工具栏操作与 Snackbar 通过同一布局保留；详情分组使用 `SettingsDetailCard`，端口编辑使用 `SettingsPortEditor` 让输入与操作分行，避免窄屏挤压；设置分组由 `PreferenceSurface` 复用 RuntimeCard，采用主题色面、28dp 外圆角与 4dp 内圆角；通用 SettingsRow / ToggleRow 的默认密度由 SettingsDensity 管理：80dp 最小行高、14dp 垂直内边距、24dp 图标、16sp 标题与 12sp/18sp 行高的说明；导航说明保持简短，普通说明最多两行。当前值以 12sp 放在标题旁，列表默认不放尾部箭头，避免状态独占一行和挤压说明；大字体仍允许自然增高。模型档案、插件工具、快捷短语、Agent 参数、MCP 服务与挂载列表也使用该大标题重载；模型编辑保留紧凑顶栏，表单消费 Scaffold Insets 并使用 imePadding，确保键盘弹出后仍可滚动到保存操作；工作流仅在目录态展开大标题，编辑和运行态保留紧凑工具栏。模型激活状态使用色面表达，卡片操作区采用可换行布局。系统概览使用同一大标题重载，资源卡片通过 FlowRow 与按字体缩放的最小宽度自然换行；聊天和终端保持各自的信息密度。
   - 首次引导依次为欢迎、免责声明与使用须知、沙箱初始化、可选权限引导、模型接入；使用须知须主动勾选确认后继续，运行时恢复不得跳过该步骤；同一说明可从设置中的「免责声明与使用须知」重看；欢迎与设置插图共用 RuntimeIllustration，使用原创矢量与主题角色色；权限页提供通知、悬浮窗、文件管理、自启动和电池优化入口，所有权限均不阻塞继续，仅用户点击时请求授权。首次引导完成后默认进入智枢聊天；主页面不显示底部导航栏。会话与主功能统一通过对话页的菜单键打开左侧抽屉；项目、系统概览与设置均进入当前导航栈，使用与其他子页面一致的覆盖转场及返回行为；设置子页面和通知深链也共用该栈，顶栏返回与系统返回均执行出栈。侧栏底部提供设置入口，功能列表不重复显示对话与设置。
   - 聊天顶栏采用悬浮圆形菜单与右侧胶囊操作区，会话标题与模型名称在侧栏查看；消息可滚到顶栏下方，纯色背景使用顶部渐变淡出，不为顶栏单独保留整行占位。新建入口位于侧栏搜索栏旁。审批模式在会话输入框工具栏展示与切换，全部放行状态始终可见；工作台控制按需展开。抽屉通过滑动或点击主页面遮罩关闭，不放关闭按钮；仅聊天根页面允许滑动打开抽屉，设置及其他覆盖页面禁用该手势，返回聊天后恢复。抽屉、键盘和系统导航栏均使用实际窗口 Insets，不保留旧底部栏占位。
   - **窗口三态（WindowSizeClass）**：断点统一读取 `LocalRuntimeWidthClass` / `widthClassOf()`（600dp / 840dp），**严禁**再写死 `maxWidth >= xxx.dp` 之类的散落断点。紧凑态沿用推拉抽屉；中间态左侧常驻 `RuntimeNavigationRail`，侧栏以浮层滑出；展开态侧栏常驻停靠、可收起为 Rail。导航常驻时 `LocalAppNavigation` 为空，顶栏不显示菜单键。
   - **形态切换不硬切**：跨断点（折叠/展开、分屏改宽）时，页面、侧栏、终端与浏览器面板必须以 `movableContentOf` 迁移而非重建；面板宽度用 `RuntimeAdaptiveMotion` 动画过渡；形态相关的选择状态（侧栏收起、伴随面板显隐与比例、终端/浏览器选择）上提为 `rememberSaveable`。双栏判定以“可用宽度”为准，空间不足时退回滑出面板。
4. **响应式与小屏防截断/防折行**：
   - 消息气泡（如 `UserBubble`）必须采用响应式宽度（`Modifier.widthIn(max = 560.dp)`），避免在平板/折叠屏/大屏上被限制过窄。
   - 列表/设置类滚动页的水平内边距使用 `runtimeContentPadding(horizontal = 16.dp, ...)`：宽屏下内容最大 840dp 居中，滚动手势仍覆盖整页；同质卡片列表（如工作区项目）使用 `GridCells.Adaptive` + `fullWidthItem` 自适应列数。
   - 单行文本、路径或标签（如文件路径、短语标题、状态标签）必须显式声明 `maxLines = 1, overflow = TextOverflow.Ellipsis`，严禁长字符串将同行的操作按钮挤出屏幕。
   - 横向多按钮行（如操作栏、卡片底部动作）必须配置适当紧凑的 `contentPadding` 与 `labelSmall` 单行字号，杜绝 360dp 窄屏宽度下文字断行造成按钮高度参差不齐。
5. **层级与多层材质防冲突**：
   - 在 `RuntimeCard` 内嵌的日志区、终端代码块或次级子项中，应使用 `Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest/Highest)`，避免多层嵌套 Card 导致色调与阴影层级混乱。
6. **M3 Expressive 主题体系（TianyanTheme 单一装配入口）**：
   - 主题装配唯一入口为 `feature/theme` 的 `TianyanTheme`：内部使用 `MaterialExpressiveTheme` + `MotionScheme.expressive()`；**严禁**任何模块自建 `MaterialTheme(...)` 组件树或直接调用 `dynamicLightColorScheme` / `dynamicDarkColorScheme`。读取 token 仍用 `MaterialTheme.colorScheme/typography/shapes` 与 `semanticColors()`。
   - 动态取色为「系统 Monet 优先 + HCT 兜底」：API 31+ 且系统色板未被 ROM 锁死时直接采用系统 `ColorScheme`；否则（API<31、ROM 锁死预置色、无壁纸种子）经 `rememberWallpaperDynamicColorScheme` 用 material-color-utilities 的 `SchemeTonalSpot`（SPEC_2025）从壁纸种子派生，与系统 Monet 同一算法族。
   - 外观设置的手动色盘与配色风格共用 `AppearancePreferences` 持久化；选中色盘时原子关闭壁纸取色，重新启用时保留手动选择。预设种子与 HCT 风格生成集中于 `feature/theme`，经 `TianyanTheme` 应用到全局；默认保留玄同色板与系统 Monet 行为。
   - 排版回归 M3 规范字重：`AppTypography = Typography()` 仅保留字体族/字号微调，**严禁**在主题层对 label/title 等角色全局加粗；局部强调在调用点用 `fontWeight` 覆盖。
   - 语义色（success / warning / info / agent / 图表色板）由主题装配时 `buildSemanticColors(colorScheme)` 用 `Blend.harmonize` 向当前主色谐调（保持 tone/chroma，色相旋转 ≤15°），经 `LocalSemanticColors` 下发；页面**严禁**硬编码色值，中性功能色（code/terminal）保持固定不参与谐调。
   - 圆角使用扩展 `Shapes` token（largeIncreased=20 / extraLargeIncreased=32 / extraExtraLarge=48dp）；**严禁**散落硬编码圆角。
   - 视觉 token（颜色、排版、形状、语义色、聊天背景偏好）只能在 `feature/theme` 定义，其他模块只消费不定义。

---

## 4. 安全与网络策略 (Security & Network Policy)

- 本地模型（`LOCAL` 分组）允许 `http://127.0.0.1:*` 或 `http://localhost:*`，且 `apiKeyOptional = true`。
- 外部模型（`OFFICIAL` / `CHINA` / `AGGREGATOR`）强制要求 HTTPS。

---

## 5. PRoot 挂载与命令执行安全 (PRoot Sandbox Security)

- 挂载路径必须经过 `ProotCommandBuilder` 正规化处理，避免 Shell 注入与危险的系统根目录穿越。
- `base` 只用于有界前台命令；默认 10 分钟，可由用户配置为 1–60 分钟，也可用 `timeout_seconds` 对单次调用覆盖 1–3600 秒。
- 不要在 `base` 中用 `nohup` / `setsid` / `&` 模拟守护进程。长任务必须调用 `process(action="start", ...)`，后续用 `status` / `logs` / `list` / `stop` 管理。
- `process` 的外部 ID 会加 `agent-process:` 命名空间，禁止与工具中心服务进程混用。

---

## 6. 移动端 Gradle 资源策略 (Mobile Sandbox Toolchain Strategy)

- 沙箱 Android/Flutter 构建固定 `--no-daemon --max-workers=2`，全局配置保持 `org.gradle.daemon=false`、`org.gradle.parallel=false`、`org.gradle.workers.max=2`。
- NDK 只允许由 init policy 的 `android.ndkPath` 注入。构建脚本可清理旧 `local.properties` 的 `ndk.dir`，不得同时重新写入 `ndk.dir` 或在项目中再声明 `ndkPath`，否则 AGP 会报双 locator 冲突。

---

## 7. 工作区与插件安全 (Workspace & Plugins Safety)

- ZIP 导入必须使用 `WorkspaceManager.importProjectArchive()`，禁止直接解压不受信路径；Git HTTP(S) 与 SSH URL 必须按用户选择的 transport 分别校验。
- 导入类型写入 `.tianyan-project.properties`，不能只靠文件扩展名或目录内容重新猜测用户选择。
- 同一插件 ID + version 已存在时返回“已导入”，不得递归覆盖现有 payload；安装框架应先保证 `$toolDir/bin` 存在。

---

## 8. 无线 ADB 与 PRoot 守护进程约束 (Wireless ADB & PRoot Daemon Strategy)

- **PRoot ADB 进程隔离**：PRoot 沙箱内无持久 init/systemd 守护进程，后台 `adb fork-server` 无法跨独立命令存活，每次调用后都会销毁。命令行 ADB 操作需在单条执行中完成 `connect + 操作`（沙箱 `/opt/tianyan/bin/adb` 封装脚本已内置自动 connect），或优先通过 `HostBridge` (`logcat-grabber` / `tianyan-host logcat`) 经由宿主常驻 Kadb 连接。
- **日志抓取免特权**：抓取日志（logcat）通过内置无线 ADB 协议与 HostBridge 桥接，**完全不依赖 Shizuku 或 Root 权限**。AI Agent 在遇到抓取日志/排查崩溃任务时，应直接使用 `host(action="logcat", ...)` 或 `logcat-grabber <包名> [-P 端口]`，严禁要求用户先开启 Shizuku/Root 或做多余权限排查。

