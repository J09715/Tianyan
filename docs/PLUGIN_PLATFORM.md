# 天衍插件平台方案与迁移路线

状态：P1–P4 已完成本地实现、单元测试与 Debug 打包；P5 已接入静态 Web 宿主、页面级授权、外部静态包和宿主注册的服务静态来源，范围见第 18–23 节。尚未做真机交互验证。
目标是让业务能力通过插件装配，逐步形成稳定内核与开放生态。P3 已接入 v1 目录适配与 v2 包协议；P4 接入编译时 Harness 扩展；2026-10-19 增加外部 PROCESS/MCP 工具、Provider 和工作流节点，详见 [补齐说明](PLUGIN_V2_COMPLETION.md)。P5 已接入静态 Web 宿主、页面级授权、外部静态包、受管理服务通用化（已安装工具服务与 PRoot 启动服务）、有界动态 API、外部分包服务声明、只读长期授权与开发者全权模式，范围见第 18–30 节。WebSocket 通道、真机验收与 P6 仍待实现。原生页面和 Harness 回调能力不会自动赋予 `.txplugin` 包。

## 1. 目标与当前事实

“一切皆插件”意味着聊天、终端、浏览器、工作流、Provider、执行后端都能通过公开扩展点接入。
内置功能和第三方功能使用相同契约；新插件不应要求修改顶层页面分发器或核心执行循环。
不以开放 Root 或任意 Shell 代替扩展 API。

当前实现证据：

| 位置 | 当前职责与限制 |
| --- | --- |
| [`ToolManifest`](../core/model/src/main/java/top/tianyan/app/core/model/ToolManifest.kt) | schema 1 兼容元数据；schema 2 增加 PROCESS 身份、SDK/依赖版本与资源/完整性声明，详见 [包协议](PLUGIN_PACKAGE_V2.md) |
| [`GenericRecipeInstaller`](../tools/src/main/java/top/tianyan/app/runtime/tools/GenericRecipeInstaller.kt) | 执行安装/卸载脚本；`offlineOnly` 跳过自动依赖获取，不阻止网络访问 |
| [`ToolManifestValidator`](../tools/src/main/java/top/tianyan/app/core/tools/ToolManifestValidator.kt) | 四种权限字符串是校验/展示数据，不是脚本隔离机制 |
| [`InstallTransactionManager`](../tools/src/main/java/top/tianyan/app/core/tools/InstallTransactionManager.kt) | 快照仅覆盖插件程序目录，不覆盖全部全局资源 |
| [`TianyanNavHost`](../feature/navigation/src/main/java/top/tianyan/app/ui/navigation/TianyanNavHost.kt) | 终端/浏览器页面及聊天嵌入面板经插件宿主装配，导航状态归 Navigation3 |
| [`NativePluginPlatform`](../feature/navigation/src/main/java/top/tianyan/app/ui/navigation/NativePluginPlatform.kt) | 编译时插件集合、页面目录和生命周期控制器；Koin 仍由 navigationModule 集中装配 |
| [`AgentPlugin`](../core/model/src/main/java/top/tianyan/app/core/model/AgentPlugin.kt) | 既有插件展示模型；不视为可执行插件加载协议 |

安全事实以 [`SECURITY_SURFACE.md`](SECURITY_SURFACE.md) 为准：PRoot 是兼容层，同 uid 代码没有恶意代码隔离；
沙箱可读的 HostBridge key 不是插件独立身份，也不能作为未来插件级授权凭据。

## 2. 内核保留什么

内核持有插件身份、兼容性校验、依赖图、扩展注册、授权代理、凭证代理、资源归属、状态恢复与安装事务。
Android Application/Activity、导航容器、最小启动与恢复界面也属于宿主基础设施。
业务功能不进入内核；内核通过接口调用执行、持久化和界面适配器，不反向依赖业务 feature。

现有工具审批、PLAN 硬门禁、会话固定执行目标、子任务资源归属、输出脱敏继续生效。
插件不能通过事件、检查点、上下文贡献或自报权限绕过这些规则。

目标依赖方向：

```text
app / feature:navigation（装配）
    -> 业务插件（终端、浏览器、工作流……）
    -> plugin:ui（原生 UI 适配） -> plugin:api（平台无关契约与目录）
    -> plugin:runtime（生命周期、资源作用域；授权代理待实现） -> plugin:api
业务插件 -> plugin:api / 对应宿主适配器；插件间通过服务契约交互
```

UI、进程执行和授权机制分层；不把 Compose、Koin、Android Context 或 Room DAO 放入纯 Kotlin SDK。
P1 的目录在 `plugin:api` 中实现无副作用的注册校验；P2 已拆出纯 Kotlin `plugin:runtime`。

## 3. 三种执行形态

| 形态 | 交付与用途 | 信任边界 |
| --- | --- | --- |
| 进程插件 | 保留 `.txplugin` v1；运行 CLI、MCP、语言服务与开发工具链 | 实际权限由执行账号/Android uid 决定；不能宣称 manifest 限制了任意脚本 |
| Web 插件 | 静态资源或受管理服务，提供面板、编辑器与设置页 | 独立 WebView 策略、固定来源、逐请求能力代理；禁止暴露通用 JS 执行桥 |
| 原生插件 | P1 编译时打包 Compose 功能，后续评估独立 APK/IPC；提供深度 Android 集成 | 同进程原生代码属于受信任代码，SDK 授权无法隔离反射或直接平台调用 |

运行时 Dex/任意 Kotlin 热加载不进入首批范围。后续需单独决策签名、ABI、依赖冲突、商店发布约束与恢复策略。
需要隔离的第三方代码优先使用独立进程/独立 uid 与 IPC，不能只给同 uid 的脚本增加权限字段。

## 4. 统一身份、扩展与版本

- 插件 ID 使用稳定的小写点分命名，例如 `tianyan.terminal`；保留 `tianyan.*` 给内置装配来源。
- 扩展 ID 在插件内唯一，例如 `main`。全局引用为 `(pluginId, extensionId)`，不使用可碰撞的显示名称。
- SDK 版本与插件版本分开。SDK 先采用整数 API level 区间；插件版本是独立发布字符串，不用字符串排序判断升级。
- 安装来源和信任等级由宿主验证，插件清单不能自称“内置”或“全权”。
- 依赖缺失、依赖环、重复 ID、API 不兼容或扩展类型不匹配时拒绝发布目录；构建完成前不暴露半注册状态。
- P1 为不可变的编译时目录，不支持热启停或热替换，也不执行插件安装、命令和模型调用。
- P1 原生注册对象是应用编译时装配的受信任代码，不接受外部 JSON 注册；保留命名空间在后续导入边界强制校验。

P1 契约只落地原生页面扩展，避免为尚未验证的工具/Provider/后端提前制造空接口。
未来每个扩展点都有宿主定义的类型、输入输出、版本与资源生命周期，不用任意 `Map<String, Any>` 作为执行协议。
页面路由参数仅传可持久化的小型非敏感数据（如项目名）；凭证、文件正文和大对象通过宿主代理或存储引用取得。

## 5. 扩展点逐步开放

| 扩展点 | 宿主负责 | 插件提供 |
| --- | --- | --- |
| 页面/面板 | 导航栈、ViewModel 与保存状态的 owner、返回动作、主题与窗口 Insets | 页面内容与入口描述，参数使用约定键，不持有导航容器 |
| 设置/导航菜单 | 排序、可见性、搜索和恢复 | 设置分组、菜单描述与页面引用 |
| Agent 工具 | 名称映射、Schema 校验、审批、PLAN、执行环境绑定、脱敏与持久化屏障 | 工具 Schema 与受管理执行器 |
| 上下文/检查点 | 容量预算、来源标注、脱敏与低权威投影 | 上下文数据或否决意见；不授予权限、不替换执行结果 |
| Provider | 凭证引用、传输策略、取消和诊断 | 协议适配、模型能力描述；默认不接触其他 Provider 的密钥 |
| 执行后端 | 会话固定目标、能力探测、资源排空 | 文件/命令/PTY/下载能力适配，不支持项明确失败 |
| 工作流 | 定义校验、调度、审批、恢复和未知副作用处理 | 节点类型、编辑字段、触发器与执行器 |
| 宿主能力 | Android 权限、目标约束、授权、审计和撤销 | 结构化请求，例如安装指定 APK、读取指定应用日志 |

## 6. 权限与全权模式

后续采用“声明请求 + 用户授权 + 宿主执行时校验”。权限至少绑定插件、动作、目标、执行环境和有效期。
一次性授权与持续授权分开；升级新增能力重新确认，撤销后阻止新请求并按资源所有者停止可回收资源。
Android 系统权限是独立前提，插件授权不能提升 App 本身的权限。

开放开发者全权模式：用户明确授予指定插件和执行目标，清晰展示任意命令/文件/宿主操作的实际范围，允许撤销。
全权不代表绕过 Android、跨会话执行目标、访问所有插件凭证或改变 PLAN 模式。
恢复、重启和升级不自动扩大授权；授权存档损坏时拒绝推断为允许。

HostBridge 迁移前不得声称具备插件级隔离。迁移时将共享 key 与任意 shell 路径拆开，
使用宿主签发、可撤销的短期凭据和结构化动作；任意 shell 单列为全权能力。
如果代码仍能读取共享 key 或直达同 uid 数据，只能称为应用层代理门控，不能声称强隔离。

## 7. 生命周期与资源（目标；P2 已实现部分见第 13 节）

期望状态：发现 -> 校验 -> 已就绪 -> 启动 -> 运行 -> 排空 -> 停止；失败保留可诊断状态。
依赖按拓扑启动、逆序停止；单插件失败不把无关插件标记为失败。
每次激活拥有独立代次、协程作用域和资源账本，登记进程、MCP 连接、端口、订阅和临时文件。
停止先拒绝新调用，等待在途调用或取消，再回收资源；无法确认退出的资源不能标记为已释放。
进程死亡恢复只重建状态与重新授权所需信息，不能自动重放安装、发消息或未知结果的副作用。

目录、凭证引用、授权与插件业务数据分别存储；应用配置通过现有 PreferenceFacades，业务存储通过 Repository。
插件私有数据带命名空间和 Schema 版本，迁移失败保留旧数据，默认卸载保留用户数据。
全局 SDK/共享依赖声明所有权与引用关系，只有最后一个使用者释放后才允许删除。

## 8. 包格式兼容

`.txplugin` v1 的导入与安装行为保持兼容，不把四个权限标签解释成已强制执行的隔离策略。
后续 v2 添加 SDK 区间、执行形态、扩展声明、插件依赖和能力请求，使用显式版本解析器。
v1 经适配器进入统一目录时保留原 ID 映射，不用新命名规则直接拒绝已有包。
v2 不兼容版本明确拒绝；无静默降级、无同 ID 覆盖内置插件。升级先校验完整性、兼容性和权限差异，再提交事务。

## 9. 实施阶段与验收

| 阶段 | 交付 | 验收门槛 |
| --- | --- | --- |
| P1 已实现 | 纯 Kotlin 描述/页面引用/目录校验；Compose 页面注册和宿主；终端页面及聊天终端面板接入 | 真实导航路径通过注册表渲染；旧路由与 ViewModel owner 保留；重复/缺失/环/API/声明实现不一致拒绝测试；缺失页面可返回 |
| P2 已实现 | 生命周期与资源作用域；浏览器第二个界面插件；菜单/设置贡献和统一插件管理页 | 插件启停与依赖失败可见；页面入口检查可用性；在途租约排空、假资源回收失败及启用选择冷启动恢复测试；真实进程与真机待验收 |
| P3 已接入，真机待验收 | v1 目录适配、v2 PROCESS 包协议、依赖版本与资源归属声明 | 离线校验与安装测试；保留工具目录事务；不宣称全局副作用回滚 |
| P4 本地验收通过 | 工具/上下文/Provider/工作流扩展 | 假 Provider 完整链路：输入 -> 响应 -> 工具 -> 审批 -> 恢复 -> Web 完成；无 Key 通过 |
| P5 部分接入 | 已接入静态 Web 宿主、结构化能力代理、页面级授权、外部静态包、受管理服务通用化、有界动态 API、外部分包服务声明、只读长期授权与开发者全权模式；WebSocket 通道与真机验证待完成 | 来源校验、授权撤销、越权拒绝、包导入/升级/移除本地测试；WebView 真机验证待完成 |
| P6 | 后端插件化、外部原生 IPC、SDK 示例与兼容矩阵 | 第三方无需修改核心分发器完成示例；跨版本协议与故障恢复验收 |

阶段按可交付切片推进，不在 P1 同时重写 Agent、导航栈、安装器和权限体系。
终端 P1 是“页面扩展接入”，其 PTY 引擎、设置和菜单仍由原模块提供，不能称为终端已完整可卸载。
全部功能插件化是路线终点，不是本轮完成声明。

## 10. P1 初始开发约定与验证（历史）

- `plugin:api`：无 Android/Compose/Koin/数据库/执行依赖；目录只校验受信任编译时贡献。
- `plugin:ui`：原生页面契约和安全的缺失页面呈现；不会通过 JSON 加载任意代码。
- `feature:terminal`：提供原生页面插件工厂，不访问其他 feature。
- `feature:navigation`：唯一业务装配入口，构造页面目录；终端旧路由适配到通用页面宿主。
- 此阶段不提供启停按钮和持久化授权，避免 UI 承诺运行时并不存在的能力。
- 单元测试覆盖目录原子校验、确定性顺序、依赖错误、页面实现与声明的一致性、参数/返回动作传递。
- 编译终端与导航；修改 DI 后执行依赖图验证、完整单元测试及 Debug 打包；保留未做真机验证的说明。

建议命令（PowerShell，先按 [`COMMANDS.md`](COMMANDS.md) 设置 JDK）：

```powershell
.\gradlew.bat :plugin:api:test :plugin:ui:testDebugUnitTest :feature:terminal:testDebugUnitTest :feature:navigation:compileDebugKotlin architectureCheck --console=plain
.\gradlew.bat test :app:assembleDebug --console=plain
```

## 11. P1 原生页面接入示例

在业务 feature 中依赖 `:plugin:ui`，提供工厂（不创建 PTY、网络连接或其他副作用）：

```kotlin
fun notesPlugin() = NativePagePlugin(
    descriptor = PluginDescriptor(
        id = "example.notes",
        version = "1.0.0",
        extensions = listOf(PluginExtension("main", PluginExtensionPoint.PAGE)),
    ),
    pages = listOf(NativePluginPage("main") { request ->
        NotesScreen(project = request.arguments["project"].orEmpty(), onBack = request.onBack)
    }),
)
```

上例类型分别来自 `top.tianyan.app.plugin.api` 与 `top.tianyan.app.plugin.ui`；`NotesScreen` 是插件自身页面。
在 `NativePluginPlatform.plugins` 集合中加入该工厂，按架构策略登记模块依赖。
宿主使用 `PluginPageDestination("example.notes", "main", mapOf("project" to project))` 打开页面，
无需增加一个 `entry<NotesDestination>` 分支。P2 可在工厂的 `entries` 中声明菜单或设置入口，见下节。
未知页面引用呈现“此功能暂不可用”，独立页面提供返回动作；外部元数据不会触发类加载或命令执行。

## 12. P1 验收记录（2026-10-15）

- 新增 19 项测试通过：目录 10 项、原生页面注册表 5 项、Compose 页面宿主 1 项、导航/装配/序列化 3 项。
- 专项命令通过：`:plugin:api:test :plugin:ui:testDebugUnitTest :feature:navigation:testDebugUnitTest :feature:terminal:testDebugUnitTest architectureCheck`。终端模块自身无独立测试源，其贡献与真实装配由导航测试覆盖。
- `architectureCheck` 对当前 35 个模块的依赖、无环、import 与尺寸棘轮检查通过；导航文件拆出路由声明后同步收紧其尺寸基线。
- `test :app:assembleDebug --continue` 全库单元测试与 Debug 打包通过，包含应用依赖图回归；未调用真实模型 API。
- 方案文档本地链接与本轮改动的 `git diff --check` 通过。
- 未进行真机页面切换、进程死亡恢复或 PTY 交互验证；目录测试与 Compose 宿主测试不替代这些验收。

## 13. P2 当前实现与边界

新增的 [`PluginRuntime`](../plugin/runtime/src/main/kotlin/top/tianyan/app/plugin/runtime/PluginRuntime.kt) 统一管理编译时插件，
状态为 `READY / STARTING / RUNNING / DRAINING / STOPPED / BLOCKED / FAILED`。
目录保持不可变；运行代次、页面租约与资源账本位于独立控制器，不在静态目录上附加可变开关。

- 初始化按目录拓扑顺序激活；禁用依赖会逆序停止依赖它的插件，但保留这些插件自己的启用选择。
- 每代 [`PluginActivation`](../plugin/runtime/src/main/kotlin/top/tianyan/app/plugin/runtime/PluginActivation.kt) 提供独立协程 scope 与 `own(id, release)` 资源登记。资源释放函数须幂等，并只在确认释放后返回 true。
- 停用先关闭准入、令页面退出，等待已有租约归还；随后取消并等待代次任务退出，再逆序释放资源。一个资源释放失败时保留它及更早的资源；未退出的依赖者会阻止其依赖释放。
- 默认每个插件启动和单次资源排空各使用 10 秒协作式超时；超时保留失败状态和未确认资源。用户可重试，不能用“请求已取消”替代真实退出确认。生命周期/释放回调须使用可取消的挂起接口，自行将阻塞 IO 切到适当 dispatcher；同进程阻塞或忽略取消的回调不保证按时返回。
- 启动与后台任务失败不向状态复制原始异常。后台失败立即关闭该代准入，再串行处理清理及依赖阻塞；无关插件继续可用。失败的初始化不会因其他插件操作而自动重放。
- 用户选择先写入 [`PluginPreferences`](../core/datastore/src/main/java/top/tianyan/app/core/datastore/PluginPreferences.kt)，再完成有界生命周期转换；UI 取消不能把已提交选择与转换拆开。读取失败不假定默认启用，写入失败不改变当前选择；管理页显示重试入口。
- 冷启动只恢复插件启用选择，不保存或重放旧命令、安装动作、页面参数、资源释放脚本和运行结果。代次与租约只在进程内有效；不据此推断进程死亡前的外部进程已退出。

[`TerminalPlugin`](../feature/terminal/src/main/java/top/tianyan/app/ui/terminal/TerminalPlugin.kt) 与
[`BrowserPlugin`](../feature/browser/src/main/java/top/tianyan/app/ui/browser/BrowserPlugin.kt) 提供受信任的界面插件。
通用页面、旧终端/浏览器路由、聊天双栏面板均经过生命周期准入；页面退出会释放租约，重新启用使用新代次。
浏览器仍复用 Activity 级 ViewModel，嵌入面板原先没有退出按钮时继续隐藏该按钮。

**插件当前拥有的是界面作用域，不是整个 PTY/WebView/MCP 引擎。** 禁用后界面不可用，
已有终端进程、浏览器自动化和 MCP 服务继续由原资源所有者管理；不调用共享引擎 shutdown 冒充页面停用。
本轮资源失败测试使用假释放器和真实协程任务，没有新增原生进程终止后端。
原生代码仍是同进程受信任代码；准入与资源账本不是恶意插件隔离，也不扩大 HostBridge、Root 或审批权限。

## 14. P2 管理与贡献入口

设置根页新增“应用功能插件”，打开 [`PluginManagerScreen`](../plugin/ui/src/main/java/top/tianyan/app/plugin/ui/PluginManagerScreen.kt)，
展示选择、有效状态、失败重试和页面入口。插件停止或依赖不可用时，贡献链接隐藏；旧路由若仍可达则呈现不可用页面。

插件可在 `NativePagePlugin` 中提供本地化的 `title` 和 `entries`：

```kotlin
entries = listOf(
    NativePluginEntry("open", "main", PluginEntryLocation.MENU) { "笔记" },
    NativePluginEntry("settings", "main", PluginEntryLocation.SETTINGS) { "笔记设置入口" },
)
```

entry ID 在插件内唯一，目标必须是同一插件已声明并实现的页面。MENU 注入共享导航抽屉，SETTINGS 注入设置根页，
feature/components 与 feature/settings 只消费宿主提供的 Compose slot，不依赖插件实现。
title 工厂应使用所属模块的本地化资源；参数与页面内容仍由插件定义。
生命周期的 `activate` 只用于初始化、恢复元数据和登记资源；安装、发消息等外部动作必须走明确操作，不能借冷启动自动重放。

P2 专项命令：

```powershell
.\gradlew.bat :plugin:runtime:test :plugin:ui:testDebugUnitTest :feature:navigation:testDebugUnitTest :core:datastore:testDebugUnitTest architectureCheck --console=plain
.\gradlew.bat test :app:assembleDebug --continue --console=plain
```

## 15. P2 验收记录（2026-10-15）

- 本阶段新增 20 项测试：生命周期 16 项、偏好持久化 1 项、页面租约重组 1 项、贡献入口校验 1 项、管理路由恢复 1 项；专项测试已通过。
- 生命周期测试覆盖在途排空、资源释放失败/超时、依赖保留、后台异常、未协作退出的任务、失败后禁止自动重放，以及用户取消不拆开已提交选择与状态转换。
- Compose 测试确认停用触发页面租约归还、重新启用得到新代次；导航测试确认终端/浏览器均经真实装配注册，贡献入口指向有效页面。
- 36 个模块的架构门禁、全库单元测试、应用依赖图与 Debug 打包通过，包含并发准入修正后的最终回归。管理页开关补充了插件名称的无障碍标签。
- 文档本地链接与改动格式检查通过；未调用真实模型 API，尚未做真机交互、真实进程资源回收或进程死亡恢复验收。

## 16. P3 接入范围

包协议与开发示例见 [`PLUGIN_PACKAGE_V2.md`](PLUGIN_PACKAGE_V2.md)。本阶段新增纯 Kotlin `plugin:packages`，把旧工具映射为 `legacy.tool-*` 目录条目，并支持具有独立身份、SDK 区间、SemVer 依赖、资源及 payload 完整性声明的 v2 PROCESS 包。

统一管理页呈现页面插件与当前发行版的进程插件/兼容工具，后者继续通过工具详情管理安装、启动和卸载。v2 导入确认展示新增能力、插件依赖和资源声明；确认清单、安装版本与目标发行版固定。哈希、路径或同版本冲突失败时不切换选中包。包目录与进程安装是两个提交阶段，安装失败可能保留新包供重试。

安装和卸载使用实际安装版本的最小收据进行引用检查，拒绝破坏既有依赖者的升级和有依赖者的卸载。共享资源按 key/path/hash 相容检查，工具/数据/命令入口自动计入独占资源；声明全局路径保留。既有安装从“安装”入口被替换时也保留工具目录快照，并按更新任务记录，供中断恢复使用。

不在本阶段提供插件独立权限代理、任意全局资源回滚、动态原生加载、外部 Web 插件执行或版本切换 UI。既有目录事务包含尽力清理逻辑，不能据此承诺所有文件系统故障下必然恢复。真机 PRoot、安装脚本实际全局副作用和进程死亡窗口仍待验收。

验证命令（无需 API Key）：

```powershell
.\gradlew.bat :plugin:packages:test :tools:testDebugUnitTest architectureCheck --console=plain
.\gradlew.bat test :app:assembleDebug architectureCheck --continue --console=plain
```

P3 本地验收记录（2026-10-15）：新增 27 项测试全部通过（协议/策略 11、包存储/收据 10、安装与目录恢复集成 6）。覆盖 v1 兼容、v2 严格字段与 API 拒绝、SemVer、依赖升级/卸载/循环、共享及命令资源冲突、哈希集合、路径与重复条目、预览绑定、不可覆盖版本、损坏选择指针、取消、收据隔离及不保存环境秘密、发行版切换、严格验证/命令入口失败，以及程序快照恢复并保留数据和全局副作用。全库 `test :app:assembleDebug architectureCheck --continue` 最终通过，包含应用 DI 图与 37 模块架构门禁；未调用真实模型 API。真机安装、交互与进程死亡窗口仍未验收。

## 17. P4 实现与边界

契约、装配与示例见 [`PLUGIN_HARNESS_EXTENSIONS.md`](PLUGIN_HARNESS_EXTENSIONS.md)。新增 `HarnessPluginRegistry` 对工具、上下文、检查点、Provider 和工作流贡献进行类型/身份/Schema 校验；`.txplugin` 不参与回调注册。

- 工具复用 `use_capability`，以 `plugin.<id>` 为能力域；直接调用和脚本嵌套均保留审批、PLAN、后台交接及结果屏障。插件只返回成功/失败与文字。
- 背景资料采用有来源标注的合成 user 消息，先按当前环境脱敏，再按项/总量限额，计入请求预算；不能修改 system 权限。
- 三种现有模型协议通过插件工厂装配；可按协议与 Provider ID 精确增加专属适配器。传输、取消、Key 轮换和诊断继续由现有请求边界负责。
- 等待节点迁入工厂；通用 `PLUGIN_ACTION` 支持注册自定义节点并在编辑器配置字面 JSON 参数。节点每次调用前经新令牌确认，调度、持久化与恢复审查仍归宿主；动态扩图不能新增插件动作。
- 管理页显示编译时 Harness 贡献；其启停/卸载不接入 P2 页面开关。没有外部 Kotlin/原生代码加载，也不提供同 uid 恶意代码隔离。

工作流节点主题、元数据与默认配置从原大文件拆出，收紧对应尺寸基线。验证包含严格嵌套参数、预算与取消、Provider 选择、审批交接/脚本重入、带令牌节点恢复及真实 Web 输入/完成通道。没有调用真实付费模型 API；真机界面、PRoot 和进程死亡窗口仍待验收。

P4 本地验收记录（2026-10-15）：新增 22 项测试通过（注册/上下文/Provider 10、严格 Schema 3、工具策略 3、节点确认 4、工作流恢复 1、Web 全链路 1）。Web 用例通过独立 Koin 插件贡献进入实际 Harness：无 API Key、禁止网络、假 LinuxRuntime，验证用户输入受理、背景资料低权威投影、插件 Provider 响应、审批前零执行、批准后执行一次及 Web 网关完成状态；节点恢复验证成功节点不重放、旧令牌不可批准新请求。最终全库 `test :app:assembleDebug architectureCheck --continue` 通过，含应用 DI 图和 37 模块架构检查。文档链接与改动格式检查通过；未做真机验收。

## 18. P5 静态 Web 宿主与授权首片（2026-10-18）

实现和接入示例见 [`PLUGIN_WEB_EXTENSIONS.md`](PLUGIN_WEB_EXTENSIONS.md)。受信任的宿主代码使用 `WebPagePlugin` 注册静态资源与结构化操作，复用现有 PAGE 目录和 P2 生命周期；不开放外部 `.txplugin` 的 Web 执行形态。

- 每个页面实例分配独立 HTTPS 来源，单独创建 WebView；只响应有界静态资源快照，拒绝外部网络、文件、Content URI、跳转、iframe、Worker、下载及系统权限请求。消息通过支持来源校验的 AndroidX WebKit 通道传递，不使用通用 JavaScriptInterface 或 JS 求值执行桥。
- 宿主绑定插件、版本、运行代次、来源和 `android:local` 环境；JS 只能提交请求 ID、动作和目标。只有宿主登记的精确动作/目标能够进入原生授权弹窗。
- 支持单次允许、当前页面最多 5 分钟允许、拒绝和撤销。授权不落盘；页面退出、重建、刷新、插件停用或新代次均不能沿用。撤销取消协作式在途任务，不能回滚已发生的副作用。
- 设置的“应用功能插件”中提供“Web 插件示例”，唯一操作是读取应用包名、Android API 与插件 API 版本。它不持有共享 HostBridge key，不调用模型、PRoot、Shell、文件或凭证仓库。

本片是应用层代理门控，不能替代 Android 权限或同 uid 隔离；旧进程插件的共享 HostBridge 路径保持原有边界。P5 后续仍需外部包加载协议、受管理服务来源、更多结构化宿主操作与全权模式，不能把本片称为 P5 全部完成。

本片本地验收：新增 25 项测试通过（授权会话 11、页面适配 2、消息与确认集成 5、来源策略 4、静态资源 3）。最终 `:plugin:runtime:test :plugin:ui:testDebugUnitTest :feature:navigation:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug architectureCheck --continue` 通过，含应用 DI 图与 36 模块架构检查；中英文示例脚本交互冒烟、文档本地链接及改动格式检查通过。全库 `test` 尝试因既有 DataStore API 29 用例的 Robolectric 镜像下载阻塞而中止，不记作通过；未做真机 WebView 验收，未调用真实模型 API。

## 19. P5 外部静态 Web 包（2026-10-19）

v2 增加受限 `WEB` 执行形态，只允许固定 `main PAGE`、自包含静态文件与宿主白名单中的 APP_INFO 能力。工具与 Web 包共用身份冲突、不可覆盖版本、预览绑定、SHA-256 及原子选择指针；工具目录过滤 WEB，Web 包不进入 Linux 安装与进程收据。独立管理页提供 SAF 导入、打开和移除，不要求 Linux 就绪。

打开时固定工具 ID、插件身份和版本，并再次校验文件集合和全部内容，WebView 只接收有界字节快照。升级与移除使旧版本页面和授权失效；失败导入保留原版本，不允许降级、同版本覆盖或切换执行形态。移除不跟随符号链接，删除本包的全部导入版本，不清理其它包与全局资源。外部包不能通过清单注册原生回调、安装脚本、Shell、网络和文件权限，也不能复用旧 HostBridge key。

协议和可导入示例见 [`PLUGIN_WEB_EXTENSIONS.md`](PLUGIN_WEB_EXTENSIONS.md)。本片不覆盖受管理本地服务、共享 HostBridge 迁移或开发者全权模式；尚未做真实 Chromium/WebView 与设备交互验收。

本片本地验收：新增 19 项测试通过（静态包策略 5、导入/完整性/升级/移除 12、路由恢复 1、宿主主题边界 1）。最终全库 `test :app:assembleDebug architectureCheck --continue` 通过，含应用 DI 图、旧 PROCESS 回归与 36 模块架构门禁。已补齐并按 Maven Central 官方 SHA-512 核验 API 29 Robolectric 镜像，解除上轮回归的依赖下载阻塞。示例生成器输出与全部 payload 哈希、文档本地链接及改动格式检查通过；未调用真实模型 API，未做真机验收。

## 20. P5 独立宿主调用入口（2026-10-19）

外部 WEB 的 APP_INFO 已接入 `PluginHostGateway`，由原生批准后的执行器签发文档身份绑定、最长 10 秒且一次性使用的内存凭据。独立 HTTP 入口不接受旧共享密钥，也不提供签发/续期接口；当前只读应用信息，未开放 Shell、文件或全权模式。原生撤销、包准入关闭、桥接停止和到期均有执行前后校验，凭据不暴露给 JS 或沙箱。调用协议和测试命令见 [Web 契约](PLUGIN_WEB_EXTENSIONS.md)。

P5 仍为部分完成：受管理服务通用化、更多结构化宿主动作、外部分包服务声明、只读长期授权与开发者全权模式已于第 27–30 节完成；真实 WebView/设备验收与 WebSocket 通道仍待执行。（旧共享 HostBridge key 迁移已于第 26 节关闭。）

## 21. P5 剪贴板写入能力（2026-10-19）

外部 WEB 可声明 `CLIPBOARD_WRITE/clipboard`，每次提交最多 1024 字符的纯文本，由原生弹窗展示具体内容并逐次确认，不允许 5 分钟授权。完整文本和文档身份绑定到短期单次凭据；不能替换已确认内容、读取剪贴板、使用任意参数或调用旧 Shell 接口。原生写入设置敏感预览标记，成功回复不回显文本，撤销不能恢复被覆盖的旧内容。导入确认按能力逐项显示说明。

`tools/package-web-example.ps1 -Clipboard` 生成独立离线示例 `example.web-clipboard`，支持中英文、请求快照、回复关联、拒绝和超时。内置示例及原应用信息包保持原功能。边界与示例协议见 [Web 契约](PLUGIN_WEB_EXTENSIONS.md)。受管理服务、全权模式和真机验收仍待完成。

## 22. P5 受管理服务资源首片（2026-10-19）

新增宿主注册的 `ManagedWebAssets` 与页面资源租约。打开“受管理 Web 服务示例”时才启动独立 loopback 服务，宿主按固定路径、预期哈希及容量读取静态快照，再交给既有独立来源 WebView；认证头和目标端口不进入页面。重定向、代理、压缩、Cookie、越界资源和内容不符均拒绝。页面退出和打开期间取消会回收该实例，服务死亡关闭准入和页面授权，失败释放保留代次清理记录。

这是受管理服务的静态资源来源首片；尚未接入现有 PROCESS 工具服务、PRoot 服务启动、动态 API/WebSocket 或外部包服务声明，不影响已有工具服务。开发者全权模式与真实设备验收仍待完成。协议、入口和验证命令见 [Web 契约](PLUGIN_WEB_EXTENSIONS.md)。

## 23. P5 PRoot 进程静态来源（2026-10-19）

新增“PRoot Web 服务示例”：打开页面后，在已就绪的本地 Linux 中运行宿主固定的 Python 标准库资源服务。先登记准备态租约，再分配独立进程；发行版和实际进程句柄固定，切换系统会使来源失效。凭据和资源通过有界压缩标准输入传递，固定命令清空 guest 环境，不接入 Provider 环境或包脚本。

注册表新增按实际实例停止接口，锁内匹配句柄，防止旧/复制句柄停止同名替代进程；未实现此接口的后端拒绝创建来源。资源校验、独立 WebView 来源与原生页面授权保持原规则。启动配置、依赖前提和边界见 [Web 契约](PLUGIN_WEB_EXTENSIONS.md)。

当前只支持宿主注册的静态资源服务，不是现有任意 PROCESS 工具或外部包服务声明的通用接入。动态 API/服务协议、开发者全权模式和真机验收仍待完成。

## 24. P5 外部 WEB 固定 PRoot 来源（2026-10-19）

WEB 包新增可选 `plugin.webSource`：默认 `PACKAGE` 直接读取资源，`PROOT_STATIC` 选择宿主固定 Python 服务。导入确认和列表展示来源及本地 Linux/Python 前提；打开时按选中不可变版本重验字节，再通过页面资源租约启动独立进程。来源选择绑定预览及版本，升级/移除关闭旧页面与能力准入，并按原实例回收，不使用 PROCESS 安装收据。

只服务小型静态页面，不接受包提供的命令、端口、URL、环境或 Python 程序；压缩配置仍受 4 KiB 上限约束。`tools/package-web-example.ps1 -Proot` 与 `-Clipboard -Proot` 提供可导入示例。详情见 [Web 契约](PLUGIN_WEB_EXTENSIONS.md)。任意 PROCESS 工具服务、动态 API/WebSocket、全权模式和真机验收仍待完成，P5 保持部分完成。

## 25. PRoot 包导入前的启动容量校验（2026-10-19）

`PROOT_STATIC` 预览现完整验证有界归档、文件集合与 SHA-256，并使用与启动相同的 `ProotWebBootstrap` 编码器检查 4 KiB 容量；导入提交前再次检查，失败不发布候选版本。定长身份/认证头与按路径排序的资源压缩数据分开，容量与随机认证值、ZIP 文件顺序无关。超限显示专门的中英文原因；原有 PACKAGE 页面不受该启动容量限制。

预览不分配 Linux 进程或持久化认证信息；临时目录在成功、拒绝及取消时清理。二次读取的清单必须与首读相同，确认后的正式导入仍完整复核。既有运行时启动前检查保留；旧版已导入的超限包不会因此自动修复。真机和 P5 其余待办保持原状。

## 26. P5 收尾：共享 HostBridge key 迁移（2026-10-19）

`HostBridge` 不再于构造时生成共享 UUID，也不再比较 `Bearer <固定 key>`；沙箱 `/opt/tianyan/.bridge-key` 文件（及历史残留）被移除，`tianyan-host`、`logcat-grabber`、`adb` 改为从环境读取宿主签发的 `TIANYAN_BRIDGE_TOKEN`。

统一权威 `HostCredentialAuthority` 持有全部票据：签发只发生在原生代码路径，校验唯一入口是 `authorize(token, 所需动作)`，一次性票据原子消费、可复用票据在有效期内可反复校验，过期/显式撤销/代次切换/归属不可用一律拒绝，上限 256 张（插件票据与沙箱凭据共用）。`PluginHostGateway` 降为该权威之上的薄适配层，`/api/plugin/call` 语义不变。

沙箱侧由 `SandboxCredentialStore` 按发行版签发一枚 8 小时、可复用、可撤销的凭据，经 `ProotCommandBuilder.build()/buildInteractive()` 的 `hostCredential` 参数在 Provider 环境之后叠加注入（凭据覆盖 Provider 同名键），由 `LinuxRuntimeImpl` 在 `execute` / 交互会话 / 后台进程构建命令时提供；发行版卸载、重置或桥接停止时回收，代次推进后缓存层自动重签。

动作目录：`host.app-info`、`host.clipboard-write`（插件侧）与 `host.install-apk`、`host.logcat`、`host.shell`（沙箱侧）分列；端点按绑定动作放行，未知路径先过"持有有效凭据"再 404。写入端点不再接受沙箱传入的自由端口，端口固定由内置无线 ADB 管理器持有。

定性：PRoot guest 仍以 App uid 运行，凭据可被同 uid 进程读取，本机制是应用层代理门控（短寿命、收敛动作、可撤销、统一权威），不是对同 uid 恶意代码的强隔离。`host.shell` 仍随沙箱凭据下发，仅动作目录单列；"开发者模式 + 每次会话确认"的全权收紧与受管理服务通用化仍在后续片。

本片本地验收：`:runtime:testDebugUnitTest` 通过（新增 `HostCredentialAuthorityTest` 12 项、`ProotCommandBuilderTest` 增 3 项）；`:app:assembleDebug architectureCheck :plugin:ui:testDebugUnitTest :app:testDebugUnitTest` 全绿，36 模块架构门禁 0 违规。全部使用 fake/Mock，无 API Key；未做真机 PRoot 验收。

## 27. P5 受管理服务通用化（一）：已安装工具服务作为页面来源（2026-10-19）

新增 `IndexedManagedWebAssets`（按需固定哈希的活服务来源）与 `ToolServiceWebSource`（原生描述 + 本地后端门控）。与 `ManagedWebAssets` 的"先声明哈希再快照"不同，活服务的文件集合事先未知：宿主只先取入口 `index.html` 建立文档，其余路径在 WebView 资源线程上按需、有界、阻塞地读取，并把**每个路径首次响应**的 SHA-256 钉死——同一路径后续返回不同字节、超过 64 个路径或总字节超过 2 MiB 即关闭整条来源。端口、基础路径、认证头和入口路径全部来自原生解析，不接受 Web 消息、页面参数或包字段；无重定向、无代理、身份编码、仅 `127.0.0.1`、仅 HTTP/1.1。

页面侧沿用既有 `WebPluginAssetLease` / `WebPluginSourceOwner`：`WebPluginAssetLease` 新增可选 `resolve(path)`，`WebPluginResources` 在固定快照之外仅通过宿主的解析器回答未知路径，因此**绝不回落到网络**，被关闭或已漂移的来源一律给出固定 403。感知不到回环地址或任何凭据，页面也未获得任何应用能力。

入口为 `PackagedWebPluginSources.openToolService(toolId)`：经 `ToolManager.serviceSpec(toolId)` 取已安装工具的**声明端口与路径**，并要求本地后端（远程后端在分配前拒绝）；它只读、不启动/停止/重启工具服务，关闭页面不影响工具中心既有服务。UI 入口在"工具详情 → Web 网关服务"卡片：网关运行时可"以插件页面打开"，路由到宿主注册页 `tianyan.tool-service`（无能力、无签名、只读）。

边界：该片仅宿主注册的内置来源；外部分包引用已安装工具服务的决策由第 30 节（C3）经显式 `serviceToolId` 放行（仍拒绝包内 `servicePort/servicePath/launchCommand/process`）。有界动态 API/流见第 29 节，开发者全权模式见第 30 节，P5 保持部分完成。

本片本地验收：`:runtime:testDebugUnitTest --tests "top.tianyan.app.runtime.service.*"` 通过（新增 `IndexedManagedWebAssetsTest` 7 项、`ToolServiceWebSourceTest` 4 项）；`:plugin:ui:testDebugUnitTest` 增 1 项（活来源只经宿主解析器应答）；`:app:assembleDebug architectureCheck :app:testDebugUnitTest` 全绿，36 模块架构门禁 0 违规。全部 fake/Mock，无 API Key。

## 28. P5 受管理服务通用化（二）：PRoot 启动服务作为页面来源（2026-10-19）

`PackagedWebPluginSources.openToolService(toolId)` 按原生运行状态择一：服务已在运行 → `ToolServiceWebSource`（只读、不自启）；未运行 → `ProotToolServiceSource`（页面持有并启动工具自持服务，关闭只回收本实例）。同前片，命令、环境、端口、路径全部来自原生解析，页面不接受命令/端口/URL/环境/脚本。

`ProotToolServiceSource` 参数化 `ProotWebAssetService` 的固定程序假设：**不复用 4 KiB bootstrap**（服务端由工具自身提供），改就绪探测——`127.0.0.1:port` 上有界 `Socket.connect` 轮询（同 `ToolServiceController.isPortOpen` / `LocalServiceLauncher.awaitPort` 语义），进程退出或环境变更即失败，整体最多 15 秒；启动走 `LinuxRuntime.startBackground(id, ShellCommand(commandLine, environment), toolId=ownerPluginId, type=SERVICE, distroId)`，停止只用 `stopBackgroundInstance(owned)` 精确回收本实例。资源读取复用 `IndexedManagedWebAssets`（首响应钉 SHA-256、漂移即整源关闭）。

**分配前拒绝**：仅当本地后端、运行时 `Ready`、发行版匹配且 `runtime.supportsBackgroundInstanceStop` 为真时允许构造；远程/未就绪/不支持单实例停止者在构造阶段 `check` 失败，不分配进程。`WebPluginPackagePolicy` 新增常量 `PROOT_SERVICE_SOURCE = "PROOT_SERVICE"`，但 `validate()` 仍不放行（外部分包引用服务留待 Phase C 显式 `serviceToolId`）。`ToolManager.serviceLaunch(toolId)` 经 `ToolRuntimeAdapter.serviceLaunch()` 从已安装版本清单取 `launchCommand` + Provider 环境，组装 `ProotServiceDescriptor`。

本片本地验收：新增 `ProotToolServiceSourceTest` 9 项（分配前拒绝、描述符越界、钉哈希快照与按路径解析、漂移整源关闭、进程退出/就绪超时、发行版切换失效、停止失败重试隔离、准备态关闭后不再启动）；`architectureCheck` 通过（36 模块，尺寸棘轮 0 违规，为守棘轮抽出 `ToolInstallEventMapping.kt`，`ToolManager.kt` 951 → 923）。全部 fake / MockWebServer，无 API Key 全绿；未做真机 PRoot/WebView 验收。有界动态 API/流见第 29 节（B3），开发者全权模式见第 30 节（C2）。

## 29. P5 受管理服务通用化（三）：有界动态读取与分块流（2026-10-19）

工具服务页面新增两个宿主登记能力 `host.service-request` / `host.service-stream`（`WebPluginServiceCapabilities` 工厂产出，`ToolServicePluginScreen` 登记）。`target` 固定为 `service`、相对子路径放在消息的 `text`：授权按 (动作, 目标) 缓存，同一服务多路径只需一次会话授权；若把路径写进 `target`，每个新路径都会被视为新能力而重复弹窗。子路径先过 `PluginCapabilityInput.validServicePath` 轻量校验，再由 `ManagedWebAssets.safePath` 逐段复核，未过者不发出任何请求。

**动态数据不直连 loopback**：CSP 保持 `connect-src 'none'`，一律走既有消息 → `PluginCapabilitySession` → 原生代理链。新增 `ManagedWebServiceClient`（`runtime/service`）承接有界读取：数字 `127.0.0.1`、原生基础路径与可选原生认证头，无代理/重定向/压缩/Cookie 复用，仅 HTTP/1.1 与 200 + identity 编码，固定 UTF-8 解码；单次读 ≤32768 字符（与 session `result_too_large` 同口径），分块流单块 ≤4096、单流 ≤65536 字符。`ToolServiceWebSource` / `ProotToolServiceSource` 各持一个客户端并在 `close()` 中一并关闭；`WebPluginAssetLease` 新增 `request`/`stream` 两个可选来源，未注入者固定返回 null/false，因此 `PROOT_STATIC` 与固定快照来源照旧得不到任何动态读取。

**分块流不是新的会话机制**：它是一次普通的单发能力调用，执行器经 `WebPluginStreamBridge` 在**同一请求 id** 下推送 `{id,success,text,more:true}` 分块，收尾仍用既有 `{id,success,text:""}` 信封。因此天然继承单页一个在途（`busy`）、单次/5 分钟授权、`revoke()`/`close()` 取消在途 Job 与 10 秒执行超时（= 单流时长上界），无需改动 `PluginCapabilitySession` 准入与其 11 项既有测试。`WebPluginView` 在流调用前 `attach`、结束后 `detach`，关闭时 `close` 桥；桥未绑定、已关闭或 WebView 已失效都会让 `push` 返回 false，native 读取随即停止。取消异常一律向上传播，不被吞掉。

**真 WebSocket 不做**：`connect-src` 仍为 `none`，端口、基础路径与凭据始终留在原生侧；分块流已覆盖持续获取的实际需要，开放 WebSocket 只会扩大攻击面。归类同为应用层代理门控，不隔离同 UID 的沙箱代码；外部分包引用服务与开发者全权模式见第 30 节。

本片本地验收：`:plugin:runtime:test :plugin:ui:testDebugUnitTest :runtime:testDebugUnitTest --tests "top.tianyan.app.runtime.service.*" :feature:settings:testDebugUnitTest` 通过（新增 `ManagedWebServiceClientTest` 7、`PluginCapabilityInputTest` 3、`WebPluginStreamBridgeTest` 5、`WebPluginServiceCapabilitiesTest` 2，`WebPluginAssetLeaseTest` 增 3、`WebPluginMessagesTest` 增 2）；全部 fake / MockWebServer，无 API Key 全绿；未做真机 PRoot/WebView 验收。

## 30. P5 Phase C 收尾：更多宿主动作、开发者全权模式、外部分包服务声明与只读长期授权（2026-10-19）

**C1 更多结构化宿主动作**：`host.install-apk` 与 `host.logcat` 登记为页面能力（`WebPluginPackagePolicy.INSTALL_APK/LOGCAT`），外部分包能力白名单显式枚举为 `APP_INFO / CLIPBOARD_WRITE / INSTALL_APK / LOGCAT`。四者都先经 `PluginCapabilitySession` 授权，写性质动作（`clipboard-write`、`install-apk`）由 `PluginCapabilityInput.ONE_SHOT` 固定为逐次确认。`install-apk` 的路径只做形状校验，宿主侧仍用与 guest 端点完全相同的 `HostBridgeApkInstall` 解析沙箱根、拒绝 `.apk` 之外的文件；插件 `logcat` 请求体由宿主固定为 `{"lines":200}`，页面无法加宽 tag/priority/行数。

**C2 开发者全权模式（历史记录，已由下述限时确认替代）**：新增 `FullAccessGrant`（`runtime/bridge`），只读 `AppearancePreferences.developerMode`，缺省 `MutableStateFlow(false)` fail-safe。沙箱凭据动作集默认收窄为 `SANDBOX_ACTIONS = host.install-apk, host.logcat`（移除 `host.shell`）；仅当持久开发者模式开启时经 `FULL_ACCESS_ACTIONS = SANDBOX_ACTIONS + host.shell` 升权，关闭后下次签发即降权。`SandboxCredentialStore` 改为以 `grantedActions` 供应商注入（`KoinModule` 中 `single<FullAccessGrant>{ FullAccessGrant(get<AppearancePreferences>().developerMode) }`）。开发者诊断模式设置项文案明确标注「允许沙箱内 Agent 通过 host.shell 请求宿主执行命令（应用层门控，非强隔离边界）」。宿主 bridge 的 `/api/shell` 动作本身与 guest 端点保持原样，仅凭据下发面随门控收紧；细节见 [`SECURITY_SURFACE.md`](SECURITY_SURFACE.md) §4/§7。

**C3 外部分包服务声明**：`PluginPackageSpec` 新增可选 `serviceToolId`。`WebPluginPackagePolicy.validate()` 放行 `webSource ∈ {TOOL_SERVICE, PROOT_SERVICE}` 且要求显式 `serviceToolId`（工具 ID 形状 `[a-z0-9][a-z0-9-]{1,63}`）；其它来源携带该字段、或 PROCESS 包声明来源/引用（`PluginPackageProtocol`）一律拒绝。两类服务来源都要求 `integrity` 为空——页面内容完全由被引用工具自述的本地服务提供，包内不接受地址、凭据、端口、命令或环境；端口、基础路径、启动命令与 Provider 环境在打开时由原生 `ToolManager.serviceSpec/serviceLaunch` 解析。`TOOL_SERVICE` 只读引用已运行服务、绝不自启；`PROOT_SERVICE` 允许页面在打开时启动该工具自述服务并在关闭时只回收本实例（`PackagedWebPluginSources.openService(toolId, allowStart)`，`openToolService` = `allowStart = true`）。来源与所引工具 ID 进入导入预览确认与列表展示，来源占用不可变版本比较（`isCurrent` 比较整份清单）。引用工具未安装或未声明服务时，打开即拒绝。

**C4 只读能力长期授权**：`PluginCapabilitySession` 新增 `longLivedGrantMillis`（默认 30 分钟、上界 1 小时）与 `longLivedCapabilities`（自动剔除 `ONE_SHOT` 动作）。只读能力（页面 `allowSession`）的会话授权按较长寿命缓存，写能力（`clipboard-write`、`install-apk`）仍逐次确认、寿命维持 `grantLifetimeMillis ≤5 分钟`。授权一律不落盘，页面/进程重建或新代次即 `invalidate()` 清空并重认；授权弹窗文案更新为「允许此页面（只读能力，最长 30 分钟）」。

**边界**：C1–C4 均为应用层代理门控（PRoot guest 与 App 同 UID），不构成强隔离；不开放 WebSocket，不接受外部分包的任意启动命令、地址、凭据、端口或环境。

本片本地验收：`test :app:assembleDebug architectureCheck --continue` 通过；新增/更新 `HostCredentialAuthorityTest`（13）、`FullAccessGrantTest`（1）、`WebPluginPackagePolicyTest`（含服务来源用例）、`WebPluginRepositoryTest`（服务来源空载荷导入与来源不可变）、`PluginCapabilitySessionTest`（只读长期授权与写能力上界）全部通过；为守尺寸棘轮把 APK 安装解析抽到 `HostBridgeApkInstall.kt`（`HostBridge.kt` 364 → 310）。全部 fake / MockWebServer，无 API Key 全绿；未做真机验收。


## 31. 外部 Harness 与限时确认（2026-10-19）

外部包的受限 `plugin.harness` 声明已接入安装收据目录、MCP stdio 工具、显式选择的 Provider 与逐次确认的工作流节点。发现不启动进程；工具域绑定安装版本，候选升级不改变运行配置。Provider 不继承全局模型 Key，流式调用为缓冲兼容模式。原生确认按系统/包身份/安装版本保存在内存，最长 30 分钟；开发者开关本身不授权，关闭、撤销和超时会立即拒绝旧令牌。详细边界、验证记录见 [PLUGIN_V2_COMPLETION.md](PLUGIN_V2_COMPLETION.md)，包契约见 [PLUGIN_PACKAGE_V2.md](PLUGIN_PACKAGE_V2.md)。
