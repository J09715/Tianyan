<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/tianyan_ic_launcher.png" width="96" alt="天衍 Logo" />
</p>

<h1 align="center">天衍 · Tianyan</h1>

<p align="center"><strong>掌中归墟，万象可期。</strong></p>

<p align="center">
  Android 无 Root Linux Runtime · AI Agent Harness · 可视化自动化工作流 · 原生 PTY 终端 · 移动开发工作区
</p>

<p align="center">
  <code>源码 v1.5.0</code> · <code>Android 10+ (API 29+)</code> · <code>arm64-v8a</code> · <code>Kotlin 2.4.21</code> · <code>Jetpack Compose</code>
</p>

<p align="center">
  <a href="https://github.com/J09715/Tianyan/releases">Releases</a> ·
  <a href="https://github.com/J09715/Tianyan/issues">Issues</a> ·
  <strong>简体中文</strong> · <a href="README_EN.md">English</a>
</p>

---

## 🌌 何为天衍

《列子·汤问》云：“渤海之东……其中有大壑焉，实惟无底之谷，其下无底，名曰归墟。八纮九野之水，天汉之流，莫不注之，而无增无减焉。”

**天衍**取意于此：在 Android 的应用沙盒与权限边界内，构筑一方**可运行、可观测、可恢复、可演进**的 Linux 与 AI 自动化环境。

天衍让 Agent、MCP 工具、Linux 进程、原生 PTY、浏览器、Git 与项目工作区协同执行任务；可视化工作流则把一次对话扩展为可持久化、可审批、可定时的自动化任务。你可以从一句自然语言意图出发，最终得到真实文件、运行中的进程、经过验证的代码或可安装的 Android / Flutter 构建产物。

> 于天衍中立极，于方寸间创世。

```text
人的意图 ─→ Agent 规划 ─→ Linux / 工具 / MCP / 浏览器 / 宿主能力 ─→ 验证与交付
                  ↑                                                   ↓
                  └──────────── 未完成则继续，必要时审批 ──────────────┘

持久化工作流 ─→ 条件分支 / 并行节点 / 定时计划 / 后台运行 / 人工审批
```

---

## ⚡ 核心能力

| 领域 | 能力概览 |
| :--- | :--- |
| **Linux Runtime** | 基于 **PRoot** 在无 Root Android 上运行 ARM64 Linux；从 OCI Registry 安装并校验 RootFS，支持多发行版共存、切换、回滚、持久化目录绑定与 Android 共享存储挂载。 |
| **Agent Harness** | 支持 OpenAI-compatible Chat Completions、OpenAI Responses API 与 Anthropic Messages API；统一处理流式正文、reasoning、函数工具调用、视觉输入、token usage 与上下文压缩。 |
| **可靠性与恢复** | 持久化会话、任务计划、语义记忆、自动/手动上下文压缩、大工具输出落盘，以及中断后的恢复；支持 SessionFork 对话回退、文件 checkpoint、外部修改冲突检测与最近一次 rewind 撤销。 |
| **子智能体协作** | 支持多子智能体调度、结构化结果裁定、token 预算、写路径租约和超限结果分页落盘，降低并行写入冲突与上下文膨胀。 |
| **规划与执行协同** | 双智能体模式将 Planner 与 Executor 分开运行，支持依赖感知调度与步骤状态展示；AI 圆桌会议提供多视角并行评审与汇总报告。 |
| **能力按需发现** | 宿主能力与 MCP 工具可通过 `use_capability` 按需查询、调用或用 JavaScript 编排；内层调用仍接受参数校验、权限策略与审批检查，并有超时、取消和有界审计记录。 |
| **可视化工作流** | 持久化 Kotlin DAG、条件边与并行节点；可组合 Bash、后台进程、Agent、Subagent、构建、宿主动作和人工审批，并支持运行历史、后台执行、通知审批及 WorkManager 一次性/间隔/每日计划。 |
| **原生 PTY 终端** | JNI 后端通过 `openpty + fork + setsid + TIOCSCTTY + execve` 创建真实控制终端，产物为 `libpty_native.so`；配合 Termux terminal-emulator / TerminalView 支持作业控制、Ctrl+C、窗口尺寸变化和多会话，原生后端不可用时回退至 `script` 通道。 |
| **移动工作区与构建** | 创建空项目、导入本地 ZIP（含 Zip Slip 防护）或克隆 GitHub 仓库；提供代码树、行级 Diff、沙箱内 Gradle / Flutter 后台构建、APK 签名与安装。 |
| **端侧 Git 工作台** | 基于 JGit 直接管理宿主工作区，支持改动、暂存、提交、分支、提交图、标签、push/pull、推送预览、AI 提交说明与加密 HTTPS Token。 |
| **浏览器与 Web 自动化** | 内置多 Tab WebView 池与 Browser MCP；支持页面 Hook、CDP 断点、Worker 级 Fetch 拦截、网络时间线与调试状态展示。 |
| **MCP 与工具生态** | 内置 Browser、SQLite、Git、APK 审计、CodeGraph、WebSearch 等预设；支持 STDIO、常用 Streamable HTTP 请求-响应与 legacy SSE，远程服务可使用 OAuth Authorization Code + PKCE。 |
| **插件平台** | 原生页面生命周期与编译时 Harness 扩展、兼容 v1 的 `.txplugin` v2 PROCESS 包、可导入的静态 WEB 包；Web 页面支持逐项能力审批和页面自有的 PRoot 静态服务。 |
| **无线 ADB 与宿主自动化** | mDNS 发现、通知栏配对码输入、Android / PRoot 日志、设备体检与 Intent 诊断；经用户授权后，可通过无线 ADB、Shizuku、Root 或无障碍通道执行不同级别的宿主与 GUI 自动化，也可使用虚拟屏工具链。 |
| **端侧协作** | 提供全局悬浮助手、带临时 PIN 的局域网 WebChat、FTP 文件传输，以及 FGS、WakeLock 与 Wi-Fi Lock 长任务保活能力。 |
| **原生交互界面** | Agent 可通过 `render_surface` 输出 A2UI 声明式界面，在 Compose 中呈现表单等交互内容；提供最近界面查看与示例入口。 |
| **备份与配置迁移** | 本地 ZIP 备份涵盖会话、记忆、计划、技能资源、附件与可移植偏好；模型档案另支持 JSON 导入预览、复制或更新、导出与系统分享。 |

> 本文按当前源码 `v1.5.0` 更新。已发布版本及其安装包请以 [Releases](https://github.com/J09715/Tianyan/releases) 页面为准。

---

## 🧠 Agent 可靠性与安全

天衍把 Agent 视为会长期运行并真实修改工作区的执行系统，而不只是一次性的问答接口：

- **分级工具审批**：会话授权与全局策略分离，Plan 只读模式约束工具调用；MCP 注解参与风险判断，按需发现和脚本编排中的调用同样受检查。
- **可恢复会话**：执行意图、工具结果与操作状态持久化，为中断恢复提供依据；待审批操作保留具体调用，恢复时继续检查权限。
- **分支一致性**：上下文从当前会话分支投影，回退后不把废弃分支混入模型历史。
- **上下文治理**：支持自动/手动压缩、溢出重放、token 预算及大型工具输出落盘。
- **请求上下文诊断**：从聊天上下文用量面板查看最近构造的主会话模型请求，支持脱敏预览、搜索与复制；诊断只在内存中保留。
- **安全回退**：每轮前创建文件 checkpoint；rewind 时检测会话外修改并报告冲突，满足条件时可撤销最近一次文件恢复。
- **受控并行**：子智能体通过写路径租约、结构化结果与调度约束降低覆盖彼此改动的风险。
- **凭据边界**：模型密钥、Git Token 与 OAuth Token 使用应用侧安全存储；敏感信息不应写入工作区或提交到仓库。

脚本执行遇到需要审批的内层调用时会停止，审批针对该次调用；余下步骤由 Agent 后续继续推进。文件 checkpoint 的恢复会报告冲突，具体边界见[安全与凭证暴露面](docs/SECURITY_SURFACE.md)。

---

## 🚀 快速上手

### 1. 安装

在 **ARM64、Android 10+**（推荐 Android 12+）设备上安装 [最新 Release APK](https://github.com/J09715/Tianyan/releases)。Release 页面同时提供构建产物与 SHA-256，建议安装前校验。

### 2. 初始化 Linux

首次启动后跟随「启程向导」安装 RootFS。默认推荐 Ubuntu 24.04 LTS，也可以选择其他受支持发行版。RootFS 不内置于 APK，首次安装需要网络连接与足够的存储空间。

### 3. 配置模型与工具

在设置中添加模型服务与 API Key。可连接 DeepSeek、Claude、OpenAI、SiliconFlow 等云端服务，也可连接沙箱或局域网内的 OpenAI-compatible 本地推理端点，例如 llama.cpp / Ollama 类型服务。

按需启用 MCP、无线 ADB、悬浮窗、通知、安装 APK、Shizuku 或无障碍权限；未使用相关能力时无需授予。

### 4. 创建工作区

在工坊中新建项目，或从 GitHub / 本地 ZIP 导入已有工程。随后可在智枢中描述目标，让 Agent 读写代码、调用工具、运行测试并交付构建产物；也可以把重复任务编排为可视化工作流。

### 5. 常用入口

- **智枢**：Agent 对话、计划、工具调用、子智能体与代码变更。
- **终端**：进入当前 Linux 发行版的真实 PTY 会话。
- **工坊**：项目管理、源码浏览、Diff、构建与 APK 交付。
- **工作流**：搭建 DAG、运行后台任务、设置审批与计划。
- **Git**：管理改动、分支、提交、标签和远程同步。
- **浏览器**：网页访问、调试、Hook 与 Browser MCP 自动化。
- **无线 ADB**：配对设备、查看日志并执行授权范围内的诊断。
- **乾坤 → 特色功能**：一句话做 App、AI 圆桌会议、晨报哨兵、WebChat 电脑大屏协作与 A2UI 界面。
- **设置中的备份与模型档案**：导出或恢复本地数据，迁移模型配置。
- **设置 → 应用功能插件 → 外部 Web 插件**：导入、打开和移除 `.txplugin` Web 页面包。

> 长时间运行 Agent、构建或工作流时，建议允许通知并将天衍加入系统电池优化白名单。Android 的后台限制仍可能影响任务时效。

### 6. 备份与迁移

本地备份恢复前可预览将新增的数据，已有会话不覆盖。备份不包含 RootFS、工作区实际文件、API Key 或自定义请求头；Linux 与项目文件需另行备份。模型档案 JSON 默认也不包含凭据，显式选择包含凭据时会以明文导出，请妥善保管。详见[本地数据备份](docs/LOCAL_BACKUP.md)和[模型档案迁移](docs/MODEL_PROFILE_TRANSFER.md)。

---

## 🐧 支持的 Linux 发行版

当前发行版目录提供以下 10 种配置（版本按仓库配置列出）：

- Ubuntu 24.04 LTS（默认）
- Debian 12
- Kali Rolling
- Arch Rolling
- Fedora 40
- Alpine 3.19
- AlmaLinux 9
- Rocky Linux 9
- openSUSE Tumbleweed
- Manjaro Rolling

RootFS 优先通过 OCI 镜像获取，部分发行版在自动路由下支持 LXC 镜像回退，不随 APK 分发。镜像能否安装及软件兼容性取决于上游 ARM64 产物、网络与 PRoot 用户态环境。

---

## 🔌 模型与工具协议

### 模型 API

- OpenAI-compatible Chat Completions
- OpenAI Responses API
- Anthropic Messages API
- SSE 流式正文与 reasoning
- Native function calling 与 JSON 文本工具协议
- 视觉输入、usage 统计、模型级上下文与压缩预算

### MCP

- STDIO JSON-RPC
- 常用 Streamable HTTP 请求-响应
- Legacy SSE
- OAuth Authorization Code + PKCE、Token 刷新与回调
- 内置 Browser、SQLite、Git、APK 审计、CodeGraph、WebSearch 预设
- 能力按需发现与延迟连接
- 工具注解与审批策略联动；内置与远程能力共用调用检查

---

## 🧩 插件平台与 Web 页面

| 形态 | 当前支持 | 使用边界 |
| :--- | :--- | :--- |
| **原生插件** | 编译时装配的 Compose 页面、生命周期管理与 Harness 扩展 | 受信任代码随 APK 发布，不加载外部 Dex 或 Kotlin。 |
| **PROCESS 包** | `.txplugin` v1 兼容与 v2 身份、API/依赖版本、资源声明、SHA-256 校验和安装收据 | 在 Linux 中运行；安装脚本沿用既有 PRoot 权限，能力声明不隔离任意脚本。 |
| **WEB 包** | 导入、打开、升级与移除静态 HTML/CSS/JS 页面；固定入口 `index.html` | 独立 WebView 来源；只接受已实现的宿主能力声明，不开放任意网络、Shell 或文件操作。 |

Web 包从 **设置 → 应用功能插件 → 外部 Web 插件** 导入。默认 `PACKAGE` 来源无需 Linux；页面最多 63 个文件、合计 2 MiB，导入和打开时校验完整性，版本目录不可覆盖。升级或移除使旧页面与授权失效。

目前可声明 **APP_INFO**（应用基本信息）和 **CLIPBOARD_WRITE**（写入纯文本）。导入不会授予能力；调用由原生界面确认，剪贴板写入每次展示具体文本并确认，不读取原内容。宿主调用凭据绑定当前文档且短期、单次有效，不交给 JavaScript。

在仓库根目录生成可导入的示例：

```powershell
.\tools\package-web-example.ps1                    # build/examples/web-info.txplugin
.\tools\package-web-example.ps1 -Clipboard         # build/examples/web-clipboard.txplugin
.\tools\package-web-example.ps1 -Proot             # build/examples/web-info-proot.txplugin
```

`PROOT_STATIC` 来源在打开页面时启动宿主固定的 Python 静态服务，退出时回收该实例。需要已就绪的本地 Linux 和 `/usr/bin/python3`，不会自动安装依赖；启动数据总计最多 4 KiB，预览和导入时预检，因此适用于小型静态页面。也可组合 `-Clipboard -Proot` 生成复制示例。

插件平台仍在开发：任意 PROCESS 服务页面、动态 API/WebSocket、旧共享 HostBridge 密钥迁移与开发者全权模式尚未完成；本轮实现已通过本地测试和 Debug 构建，真机 PRoot/WebView 交互仍待验收。详见 [平台进度](docs/PLUGIN_PLATFORM.md)、[v2 包协议](docs/PLUGIN_PACKAGE_V2.md)和 [Web 扩展指南](docs/PLUGIN_WEB_EXTENSIONS.md)。

---

## 🛠️ 从源码构建

### 标准 APK 构建要求

- **JDK 17**（项目与 CI 基线，推荐 Android Studio JBR）
- **Android SDK**：compileSdk 37.1 / targetSdk 37 / minSdk 29
- **Gradle Wrapper**：9.8.1
- **Android Gradle Plugin**：9.4.1
- **Kotlin**：2.4.21；Compose BOM：2026.09.00
- 完整 Git checkout，包括仓库内的本地 Maven AAR 与预编译 ARM64 原生库

普通 APK 构建直接使用仓库内的预编译 native 制品；只有重新编译 PTY 等原生组件时，才额外需要 **Android NDK 30.0.15729638、CMake 3.22.1 与 Ninja**。

### Windows

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"

.\gradlew.bat architectureCheck --console=plain
.\gradlew.bat test --console=plain
.\gradlew.bat assembleDebug --console=plain
```

### macOS / Linux

```bash
export JAVA_HOME="/path/to/jdk-17"

./gradlew architectureCheck --console=plain
./gradlew test --console=plain
./gradlew assembleDebug --console=plain
```

Debug APK 默认输出到：

```text
app/build/outputs/apk/debug/tianyan-v1.5.0-debug.apk
```

### 可选步骤

正常完整检出后无需手动准备 PRoot。仅当 `app/src/main/jniLibs/arm64-v8a/libproot*.so` 缺失、校验失败，或需要刷新官方 PRoot 制品时运行：

```powershell
.\tools\prepare-proot-runtime.ps1
```

设置 `TIANYAN_DEV_BUILD=1` 可构建包名为 `top.tianyan.app.dev` 的 **TianyanDev** 预览版，与正式包 `top.tianyan.app` 独立共存：

```powershell
$env:TIANYAN_DEV_BUILD="1"
.\gradlew.bat assembleDebug --console=plain
```

未设置该变量时，普通 Debug 包名为 `top.tianyan.app.debug`。局域网 WebChat 前端位于 `webchat/`，使用 React、TypeScript 与 Vite；在该目录使用 pnpm 10.28.0 执行 `pnpm install --frozen-lockfile`，随后可运行 `pnpm dev`、`pnpm test` 或 `pnpm build`。

更多构建、测试、签名与诊断命令见 [docs/COMMANDS.md](docs/COMMANDS.md)。

---

## 📐 模块拓扑

```text
Tianyan/
├── app/                  # 宿主壳、Koin 装配、Manifest、JNI 与前台服务
├── baselineprofile/      # Baseline Profile 与启动性能基准
├── core/
│   ├── model/            # 纯 Kotlin 领域模型
│   ├── common/           # 调度器、日志与公共基础设施
│   ├── database/         # Room：会话、消息、记忆、工作流、OAuth、执行历史
│   ├── datastore/        # DataStore：用户偏好、外观、挂载与引导状态
│   ├── network/          # OkHttp、SSE 与网络策略
│   ├── security/         # 密钥、Token 与安全存储
│   └── browser/          # 浏览器共享模型与协议
├── runtime/              # PRoot、RootFS、PTY、进程、存储与工作区运行时
│   └── browser/          # WebView 池、Hook、CDP、网络时间线与 Browser MCP
├── project-template/     # 项目模板、动态表单与物化引擎
├── harness/              # Agent 循环、Provider、工具、审批、子智能体与 MCP
│   └── core/             # 纯 Kotlin 回合解释器与执行协议
├── tools/                # Registry、Recipe、安装事务、依赖与 Provider 仓储
├── plugin/
│   ├── api/              # 纯 Kotlin 插件描述、扩展契约与目录
│   ├── runtime/          # 生命周期、代次、资源租约与页面能力授权
│   ├── packages/         # v1/v2 包协议、版本、依赖与资源校验
│   └── ui/               # Compose 页面宿主与受限 WebView 适配
├── showerclient/         # 虚拟屏客户端与画面显示
├── webchat/              # 局域网协作前端（React / TypeScript / Vite）
└── feature/              # Jetpack Compose 业务特性
    ├── theme/            # 主题与视觉系统
    ├── components/       # 通用组件、图标与 Spotlight 引导
    ├── navigation/       # Navigation3 路由与入口
    ├── home/             # 首页状态与诊断
    ├── chat/             # 智枢对话、计划、Diff 与悬浮助手
    ├── terminal/         # 终端 UI 与多会话
    ├── workspace/        # 工坊、源码浏览、构建与交付
    ├── browser/          # 内置浏览器 UI
    ├── workflow/         # DAG 编辑器、运行、审批与计划
    ├── git/              # JGit 工作台
    ├── a2uipoc/          # A2UI 原生界面渲染与交互
    ├── settings/         # 模型、MCP、ADB、插件与系统设置
    ├── developer/        # 开发者沙箱与底层诊断
    ├── custom_iteration/ # 自定义迭代能力
    └── onboarding/       # 首次启动与 RootFS 初始化
```

Android 应用业务层以 Kotlin 与 Jetpack Compose 为主；底层还包含 C/JNI、Python、JavaScript、Shell 与 TypeScript 组件。

---

## 📚 文档导航

- 🧭 [AI 语义导航总览](docs/AI_NAVIGATION.md)
- 🏗️ [系统架构与模块拓扑](docs/ARCHITECTURE.md)
- ⏱️ [主要运行时调用链](docs/EXECUTION_TRACES.md)
- 📜 [架构与 UI 设计铁律](docs/ARCHITECTURE_RULES.md)
- 🔀 [可视化工作流](docs/WORKFLOW.md)
- 🌐 [内置浏览器与 Hook 引擎](docs/BROWSER_DESIGN.md)
- 🔌 [插件生态开发指南](docs/PLUGIN_DEVELOPMENT_GUIDELINES.md)
- 🧩 [插件平台方案与实现进度](docs/PLUGIN_PLATFORM.md)
- 📦 [`.txplugin` v2 包协议](docs/PLUGIN_PACKAGE_V2.md)
- 🌐 [Web 插件扩展、能力授权与示例](docs/PLUGIN_WEB_EXTENSIONS.md)
- 📦 [Android 离线插件说明](docs/ANDROID_OFFLINE_PLUGIN.md)
- 🤖 [Android CLI 与官方技能插件](docs/ANDROID_SKILLS_PLUGIN.md)
- 💾 [存储管理](docs/STORAGE_MANAGEMENT.md)
- 🗃️ [本地数据备份与恢复](docs/LOCAL_BACKUP.md)
- 🔑 [模型档案备份与恢复](docs/MODEL_PROFILE_TRANSFER.md)
- 🔍 [请求上下文诊断](docs/CONTEXT_DIAGNOSTICS.md)
- 🛡️ [安全边界与凭证暴露面](docs/SECURITY_SURFACE.md)
- 🧱 [沙箱后端 ADR](docs/ADR_SANDBOX_BACKEND.md)
- 🩺 [已知问题](docs/KNOWN_ISSUES.md)
- 🧪 [构建、测试与调试命令](docs/COMMANDS.md)

---

## ⚠️ 权限、边界与已知限制

- **仅支持 ARM64**：当前只适配 `arm64-v8a`，其他 ABI 不在支持范围内。
- **PRoot 权限边界**：它通过用户态系统调用拦截与路径重写提供 Linux 兼容环境；guest 进程实际使用 App uid，并共享宿主网络，不具备 KVM、Root 特权或内核模块加载能力。PRoot 不提供对恶意代码的安全隔离，持有 HostBridge 密钥的 guest 代码可触发当前已授权的宿主能力。
- **首次安装依赖网络**：RootFS 不随 APK 打包，安装时需要下载 OCI 镜像并占用额外存储空间。
- **宿主能力取决于授权**：无线 ADB、Shizuku、Root 与无障碍提供的权限等级不同；高级宿主动作不会在未授权时自动获得能力。
- **后台任务受 Android 限制**：前台服务与 WorkManager 可提高可靠性，但 Doze、厂商省电策略及新版 Android 的前台服务时限仍可能造成延迟或中断。
- **局域网服务需谨慎开启**：WebChat 与 FTP 只应在可信网络中主动启用，并在使用后关闭。
- **兼容性因设备而异**：复杂 TUI、特定软键盘组合键和重型 C/C++ 交叉编译会受到设备性能、内存与 PRoot 兼容性的影响。
- **保护凭据**：请妥善保存模型 API Key、Git Token 与远程服务凭据，切勿提交到公开仓库。

---

## 🤝 参与贡献

欢迎提交 [Issue](https://github.com/J09715/Tianyan/issues)、Pull Request，或分享你的真机使用记录。开始修改前建议先阅读 [AI_NAVIGATION.md](docs/AI_NAVIGATION.md) 与对应模块文档，并运行架构检查和相关测试。

---

## 📜 注脚

> 须弥纳于芥子，天衍纳于掌中。

限制从未真正消失；但自由可以来自身处限制之中，仍有能力去构筑、去验证属于自己的世界。
