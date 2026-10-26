# 🏗️ 天衍 (Tianyan) — 系统架构与模块拓扑 (Architecture & Modules)

---

## 1. 🚀 项目定位与技术栈摘要 (Tech Stack Snapshot)

- **核心定位**：在 **Android 无 Root 用户态** 下，基于 **PRoot** 运行完整的 **Linux 沙箱**，并深度集成 **AI Agent Harness 智能体引擎**、**原生 PTY 终端**、**工作区文件管理** 与 **Material 3 Expressive UI**。
- **Android / UI 层**：
  - **主工程构建链**：Gradle 9.7.0 / AGP 9.3.1 / Kotlin 2.4.10 / compileSdk 37.1 / targetSdk 37 / NDK 30.0.15729638
  - **语言**：Kotlin（100% Kotlin + Jetpack Compose）
  - **UI 风格**：Material 3 Expressive (M3 动态表面、Haptic 触觉反馈、双栏自适应 Dual-Pane)
  - **依赖注入**：Koin 4.2.2（显式 Kotlin DSL）
  - **状态与异步**：Kotlin Coroutines, StateFlow, SharedFlow
  - **本地存储**：Jetpack DataStore (Preferences), Room Database (SQLite)
- **底层运行时 (Runtime & Native)**：
  - **C/C++ JNI**：`app/src/main/cpp/pty.c` (openpty, fork, exec, ioctl 终端 PTY 桥接，编译为 `libtianyan_pty.so`, `arm64-v8a`)
  - **Linux 容器**：内置 PRoot 引擎，启动 Debian rootfs
  - **宿主存储映射**：PRoot `-b <host>:<guest>` 原生绑定机制（Download, Documents, /sdcard）
- **AI 智能体引擎 (Agent Harness)**：
  - **协议兼容**：OpenAI Chat Completion API 标准（支持流式 SSE、`reasoning_content` 思考过程、函数工具调用 `tool_calls`）
  - **内置工具**：`read` / `write` / `edit` / `base` / `process`，并可扩展 MCP 与子智能体工具
  - **命令生命周期**：`base` 用于有界前台命令，默认超时由用户配置；`process` 通过 Runtime 进程注册表托管跨工具调用持续运行的命令
  - **本地端侧模型**：沙箱内原生支持 `llama.cpp` 与 `Ollama`，无需 API Key
- **沙箱 Android 开发套件（不要与主工程构建链混淆）**：Gradle 8.14.2 / AGP 8.11.1 / Kotlin 2.2.20 / compileSdk & targetSdk 34 / NDK 29.0.14206865，仅面向 ARM64 沙箱内的 Android/Flutter APK 构建。

---

## 2. 🗺️ 模块拓扑与职责 (Module Topology)

```text
LinuxAIRuntime/
├── app/                  # 应用壳工程：MainActivity、Koin 初始化、JNI C 代码、前台保活 Service
├── core/
│   ├── model/           # 纯 Kotlin 数据模型 (不含 Android SDK 依赖)
│   ├── common/          # 协程调度器、日志、通用工具类
│   ├── database/        # Room 数据库：Harness 对话记录、会话、工具执行历史
│   ├── datastore/       # Jetpack DataStore：用户偏好、存储挂载、激活模型、Agent 命令超时
│   ├── network/         # OkHttp 网络客户端、SSE 流式解析器、超时策略
│   └── security/        # API Key 本地安全加解密
├── runtime/              # Linux 沙箱与 PRoot 核心：命令/进程注册、PTY、工作区导入导出与构建
├── project-template/     # 标准化项目模板：manifest、变量表单协议、导入导出、物化与内置模板资产
├── harness/              # Agent 智能体核心：Agent 循环、流式推理、内置工具/审批、MCP
│   └── core/            # 纯 Kotlin 轮次核心与模型能力契约；不依赖 Android/Room/网络/PRoot
├── tools/                # 工具生态中心：Registry、本地插件、安装事务、批量组件安装、Provider 安全
├── plugin/
│   ├── packages/        # 纯 Kotlin v1/v2 包协议、SemVer、安装依赖与资源声明检查
│   ├── api/             # 纯 Kotlin 插件描述、扩展引用与不可变目录校验
│   ├── runtime/         # 纯 Kotlin 插件状态机、代次、使用租约与资源排空
│   └── ui/              # 原生页面贡献、Compose 宿主与受限静态 Web 页面适配
└── feature/              # Compose UI 业务特性层
    ├── components/      # 天衍 M3 Expressive 设计规范、通用组件 (RuntimeCard, TopBar, Icons)
    ├── theme/           # Material 3 调色板、字体、主题配置
    ├── home/            # 系统概览：Linux 状态、内存/磁盘/进程实时监控，从侧边导航进入
    ├── chat/            # 默认入口：智枢对话、共享会话抽屉、任务拆解卡片、宽屏双栏布局
    ├── terminal/        # 终端 Compose UI 与触觉按键条；会话/VT100 状态机位于 runtime
    ├── workspace/       # 工作区管理器：创建/ZIP/GitHub 导入、导出、代码浏览、后台构建
    ├── workflow/        # 工作流目录、DAG 画布、执行时间线与人工审批界面
    ├── settings/        # 设置中心：模型档案、Agent 超时、工具/本地插件、存储挂载、外观与诊断
    ├── developer/       # 开发者原生沙箱与诊断面板
    ├── onboarding/      # 首次启动引导与 RootFS 解压就绪流程
    ├── custom_iteration/ # 自定义迭代（AI 改应用）入口
    └── navigation/      # 顶层导航路由与 NavHost 调度
```

---

## 3. 🔗 跨模块交互原则 (Cross-Module Rules)

1. **单向依赖流**：
   - 依赖方向严格保持 `feature/*` → `core/*` / `runtime` / `harness` / `tools`。
   - 业务 feature 之间**严禁**互相横向依赖，仅可依赖共享 UI `feature/components` 与 `feature/theme`，顶层由 `feature/navigation` 负责页面装配与路由调度。
2. **持久化隔离**：
   - feature / runtime / harness 只能依赖 `core/database/PersistenceRepositories.kt` 暴露的 Repository 接口，严禁直接注入或操作 Room DAO。
   - 偏好配置统一通过 `core/datastore/PreferenceFacades.kt` 提供的领域分面接口读取或写入。
3. **纯模型层隔离**：
   - `:core:model` 必须保持 Pure Kotlin，**严禁**引入 `android.*`、`androidx.*` 或 Compose 依赖。
   - `architectureCheck` 任务已接入 `app:preBuild` 验证阶段，严密阻断模型层平台化与违规依赖。
   - `:harness:core` 的 `SessionProjectionBuilder` 处理存储无关的上下文贡献。Android 层 `SessionContextProjector` 负责捕获叶子、读取分支窗口和解码；`CompactionManager.inspect()` 提供来源与诊断，`project()` 从同一结果生成模型上下文。压缩后的查询继续避免加载已折叠消息 Blob，检查接口不创建或移动 Lane。
   - 多入口依赖 `SessionControl` / `InteractiveSessionControl`，均由同一 `HarnessLoop` singleton 实现。`SessionInputController` 在会话锁内校验、记录任务并提交输入；Web REST 等待接收回执，执行结果沿状态流报告。启动恢复保留具体实现入口，子任务保留独立 Lane 调度。
4. **命令执行边界**：
   - 短时、有明确退出状态的前台命令调用 `LinuxRuntime.execute()` / Harness `base`。
   - 跨调用持续运行的服务或长任务调用 `LinuxRuntime.startBackground()` / Harness `process`，统一由 `ProcessRegistry` 托管 PID、生命周期与环形日志缓冲。
5. **工作区操作边界**：
   - 项目生命周期与物理目录交互统一通过 `WorkspaceManager` 与 `WorkspaceFileService`；`runtime/ProjectType.kt` 定义项目类型，包括 `TIANYAN_PLUGIN` 本地进程插件开发。
   - ZIP 导入必须执行路径穿越防范（Zip Slip Check）、文件大小上限与条目数校验；Git clone 必须在沙箱内隔离执行。
6. **构建调度与保活边界**：
   - 工作区构建由单例 `WorkspaceBuildTaskCoordinator` 全局持有，UI 页面旋转或重建不中断构建任务；底层构建执行与环境预检由 `WorkspaceBuildRunner` 承载。

## 4. 依赖装配（Koin）

- 插件平台：`plugin:api` 与 `plugin:runtime` 均保持纯 Kotlin，`plugin:ui` 适配 Compose。`navigationModule` 装配 `NativePluginPlatform`、单例 `PluginPages` 与 `PluginController`；终端/浏览器的独立页与聊天面板共享带代次准入的页面宿主。`PluginPreferences` 只保存启用选择；状态机按依赖启停并保留未确认释放资源。菜单/设置贡献通过共享 slot 注入，管理页由通用宿主装配。页面插件不拥有全部终端/浏览器引擎，也没有外部原生代码加载或授权隔离，详见 [`PLUGIN_PLATFORM.md`](PLUGIN_PLATFORM.md)。
- P5 由 `WebPagePlugin` 将静态页面接入 PAGE 目录；独立 WebView 使用随机来源和结构化消息，`PluginCapabilitySession` 按文档绑定身份、代次、动作、目标及本地 Android 环境，提供原生单次/限时确认和撤销。`WebPluginRepository` 复用 v2 包目录提供外部静态 WEB 导入、完整性快照和移除；settings 装配宿主白名单能力，WEB 不进入 Linux 工具目录和进程安装收据，不改变旧 HostBridge 权限。详见 [`PLUGIN_WEB_EXTENSIONS.md`](PLUGIN_WEB_EXTENSIONS.md)。
- Provider 边界由 `harness/di/harness/ProviderModule.kt` 装配：`ProviderModelResolver` 管理档案、凭证与偏好，`ProviderTransport` 管理请求策略，`LlmApiRegistry` 按模型的 API 选择协议实现。兼容门面 `ProviderClient` 保留已有调用入口；纯 `ModelDescriptor` 不含密钥或端点。
- P4 的 `HarnessPluginRegistry` 装配受信任的编译时工具、背景资料、检查点、Provider 工厂和工作流节点。工具通过 `use_capability` 的 `plugin.<id>` 域沿用审批/PLAN；背景资料脱敏后以有来源标注的合成 user 消息计入预算。三种协议与等待节点已迁入插件工厂；`PLUGIN_ACTION` 是新节点的通用路由，执行前通过工作流 broker 进行带令牌的确认。详见 [`PLUGIN_HARNESS_EXTENSIONS.md`](PLUGIN_HARNESS_EXTENSIONS.md)。
- 工具边界由 `harness/di/harness/ToolBackendModule.kt` 装配：纯 JVM `ToolBackend` / `ToolCheckpoints` 定义可等待契约；`WorkspaceToolBackend` 通过 `WorkspaceToolOperations` 使用受限工作区访问与写入快照。`ToolExecutor` 保留审批、PLAN、宿主权限和其他工具分派；检查点只允许否决或追加经脱敏的说明，不能授予权限或替换结果状态。默认检查点为空，应用代码可注入受信任实现。
- 各模块的 `src/main/java/top/tianyan/app/di/**/KoinModule.kt` 声明本模块构造器与接口绑定；`app/di/TianyanModules.kt` 汇总完整依赖图，Application 启动时注册应用 Context，并禁止定义覆盖。
- 业务类使用普通构造器；仅 Android 创建的 Application、Activity、Service、Receiver 和 Compose ViewModel 入口访问 Koin。新增构造依赖时同步更新所属模块注册，并运行 `:app:testDebugUnitTest` 的依赖图测试。
- 原进程单例使用 `single`，无作用域对象使用 `factory`，ViewModel 使用 `viewModel`，保留 Navigation3 的 ViewModelStoreOwner 归属。
- Kotlin `Lazy` 保留 Runtime、仓储与 MCP Server 的延迟创建；子代理通过 `() -> ToolExecutor` 打断依赖环。ToolExecutor 的偏好通过构造器显式装配。
- 工作流执行器与工具适配器集合使用不同 qualifier，避免泛型擦除后的 Set 定义冲突。
- WorkManager 保留 `Configuration.Provider` 按需初始化，使用 `KoinWorkerFactory` 创建 Worker；WorkerParameters 来自系统，Context 来自应用容器。
- DI 使用经典 DSL，不引入 Koin 编译插件；KSP 仅用于 Room。依赖完整性由图测试保障，不能把 Kotlin 编译通过等同于 DI 图验证通过。

P5 受管理静态来源由 navigation 装配 runtime 的 loopback 资源服务与 plugin/ui 的页面租约；navigation 显式依赖 runtime，已登记架构依赖白名单。服务不在注册或启用阶段启动，外部包不能声明此来源。
