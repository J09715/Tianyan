<p align="center">
  <img src="assets/logo/tianyan-logo.png" width="96" alt="天衍 Logo" />
</p>

<h1 align="center">天衍 · Tianyan</h1>

<p align="center"><strong>把完整的 Linux 环境和能自主执行任务的智能体装进一部无需 Root 的手机</strong></p>

<p align="center">
  Android 无 Root Linux Runtime · 智能体引擎 (Agent Harness) · 原生 PTY 终端 · 移动开发工作区 · 无线 ADB 诊断
</p>

<p align="center">
  <code>v0.14.0</code> · <code>Android 10+ (SDK 29+)</code> · <code>arm64-v8a</code> · <code>Kotlin · Jetpack Compose</code>
</p>


---



## ⚡ 核心能力全景

| 领域 | 核心能力与技术实现 |
| :--- | :--- |
| **Linux 沙箱** | 基于 **PRoot** 用户态运行 10 种 ARM64 发行版（Ubuntu 24.04 / Debian 12 / Kali / Arch / Fedora / Alpine 等）；通过 OCI Registry 拉取并校验 RootFS；支持多系统无缝切换、两阶段提交回滚、持久化目录绑定与 Android 共享存储挂载；国内镜像源自动探测与自愈。 |
| **Agent 智能体引擎** | 深度兼容 **OpenAI 兼容接口** 与 **Anthropic Messages API**；支持流式 SSE、思考链（`reasoning_content` / DeepSeek / Claude）、任务拆解进度卡（TaskPlanCard）、结构化分级工具审批与多厂商中转站配置。 |
| **对话回退与快照** | 基于 **SessionFork 会话树派生** 实现一键「撤回到此轮」（Rewind）；配套每轮对话前的 **Checkpoints 文件快照安全网** 并磁盘持久化，重大代码重构与指令随时可安全回滚。 |
| **语义记忆与子智能体** | Agent 记忆语义模型（冲突消解、版本 revision、置顶 pinned、新鲜度 recency）；支持多子智能体协同调度（文件写租约波式调度、结构化 facts pack 回传与超限分页落盘）。 |
| **Git 可视化工作台** | 提交拓扑图（泳道贝塞尔连线 / 分支标签 / 合并节点）、暂存与回退、分支 / 标签 / 远端管理、提交详情与行级 Diff、凭据加密托管与署名配置——手机上完整的 Git 体验。 |
| **原生 PTY 终端** | JNI `openpty`/`forkpty` 底层桥接（`libtianyan_pty.so`），提供真实 Linux 进程生命周期、控制终端、ANSI/VT100 增量解析、触觉反馈按键条与多会话后台持久化；原生不可用时自动回退至 `script` PTY。 |
| **内置浏览器与 CDP 调试** | 内置 WebView 多 Tab 池与 In-process 浏览器 MCP 服务；支持页面脚本**注入式 Hook 引擎**、**CDP 断点**与 **Worker 级 Fetch 拦截**，提供可视化的网络请求时间线面板与调试状态横幅。 |
| **无线 ADB 与日志工作台** | 独立常驻一级工作台入口；支持**通知栏免切屏输入配对码**秒级配对无线 ADB、mDNS 局域网调试服务自动发现、PRoot / Android 双端日志实时抓取、设备状态一键体检与系统 Intent 诊断。 |
| **移动工作区与构建** | 支持创建空项目、本地 ZIP 导入（防 Zip Slip 校验）与 **GitHub 仓库导入（带实时 clone 进度）**；提供代码浏览、可视化行级 Diff 比对、沙箱内 Gradle / Flutter 后台静默构建与 APK 签名安装。 |
| **工具生态与 MCP** | 离线插件包一键导入（Android / Flutter / 反编译三大开发环境）、内置/签名 Registry、Recipe 事务安装与依赖管理；集成 **Open-WebSearch** 联网搜索、CodeGraph 代码知识图谱，全面支持 Stdio / SSE / Streamable HTTP 协议 MCP 服务。 |
| **可视化工作流** | 将构建、诊断、逆向与 Git 操作编排成可观察、可暂停的 DAG 工作流；支持定时触发与 HUD 悬浮进度，一句指令即可驱动多步流水线。 |
| **端侧协作与全局助手** | **智枢全局桌面悬浮小窗**（支持跨应用前台协作）、局域网 WebChat、内置 FTP 文件传输服务、沙箱内 `llama.cpp` 本地 GGUF 离线运行，以及 FGS 唤醒锁与 Wi-Fi 锁后台保活。 |

---


## 🛠️ 从源码构建

### 环境要求

- **JDK**：Java 17 或 Java 21（推荐 Android Studio JBR）
- **Android SDK**：compileSdk 37 / targetSdk 37 / minSdk 29
- **Android NDK**：`30.0.15729638`
- **CMake**：`3.22.1`
- **构建系统**：Gradle 9.7.0 / AGP 9.3.1 / Kotlin 2.4.10

### 构建步骤

```powershell
# 1. 首次检出仓库后，准备 PRoot ARM64 原生运行时预编译包
.\tools\prepare-proot-runtime.ps1

# 2. 配置 JDK 路径并执行架构合规检查与单元测试
$env:JAVA_HOME="C:\Program Files\Android\Studio\jbr"
.\gradlew.bat architectureCheck --console=plain
.\gradlew.bat testDebugUnitTest --console=plain

# 3. 编译 Debug APK
.\gradlew.bat assembleDebug --console=plain
```

构建产物位于：`app/build/outputs/apk/debug/tianyan-v0.14.0-debug.apk`

> ☁️ **免本地环境**：本仓库已内置 GitHub Actions 工作流 `.github/workflows/android-apk.yml`——在仓库 **Actions** 页手动运行 `Android APK 构建`，几分钟后在产物（Artifacts）区直接下载未签名 Debug APK，无需本地装任何工具链。


---



## 🔗 上游

本项目 fork 自开源项目 **太墟 · TaiXu**，并在此之上持续演进（Git 可视化工作台、可视化工作流引擎、环境体检自愈、云控分发等）。感谢上游以 GPL-3.0 协议开源。

- 上游仓库：**[wkbin/taixu](https://github.com/wkbin/taixu)** —— 掌中归墟，天衍可期。

---

## 来源与许可

天衍（Tianyan）派生自开源项目 [万象 WanXiang](https://github.com/peakSee/Wanxiang)。
上游项目未附带任何开源许可证声明；本仓库对全部品牌标识、图标、文案与文档做了独立重写，
并在 [`NOTICE`](NOTICE) 中逐项列出了随包分发的第三方组件及其版权声明。

本仓库当前**尚未附带 LICENSE 文件**——在选择并声明许可证之前，默认保留全部权利。
