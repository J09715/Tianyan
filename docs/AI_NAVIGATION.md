# 🧭 天衍 (Tianyan / LinuxAIRuntime) — AI 语义导航总览 (AI Master Navigation)

> **专供 AI Coding Assistant (Antigravity / Cursor / Claude / GPT / Copilot) 全局感知与按需读取**  
> 本文档为轻量级语义导航中心。详细架构、调用链、文件速查、铁律与命令已全面模块化拆分，请点击对应文档按需深入：

---

## 🎯 任务直达与文档索引 (Semantic Route Map)

| 你的目标 / 想要了解的内容 | 目标专业文档 | 文档概要 |
| :--- | :--- | :--- |
| **系统架构、技术栈与模块拓扑** | [`docs/ARCHITECTURE.md`](ARCHITECTURE.md) | 定位、Android/PRoot/PTY/Harness 技术栈、模块依赖拓扑与跨模块原则 |
| **数据流转、时序与核心调用链** | [`docs/EXECUTION_TRACES.md`](EXECUTION_TRACES.md) | Agent 循环、存储挂载、PTY 终端、双栏分屏、进程托管与构建链路 |
| **执行环境替换与资源清理** | [`docs/EXECUTION_ENVIRONMENTS.md`](EXECUTION_ENVIRONMENTS.md) | 文件/命令/MCP/PTY 绑定、父子资源归属、关闭排空与扩展回滚 |
| **定位特定类、文件与核心组件** | [`docs/FILE_INDEX.md`](FILE_INDEX.md) | Harness、Runtime、Tools、UI 各领域关键文件路径与职责索引 |
| **架构红线、UX/UI 设计系统与避坑铁律** | [`docs/ARCHITECTURE_RULES.md`](ARCHITECTURE_RULES.md) | Pure Kotlin 隔离、`RuntimeCard`/`RuntimeAlertDialog` 规范、IME 防遮挡 |
| **构建、测试、打包与调试指令** | [`docs/COMMANDS.md`](COMMANDS.md) | Gradle 单元测试、APK 打包、架构合规检查、ADB 部署与日志调试 |
| **GitFlow 分支与发布流程** | [`docs/GIT_WORKFLOW.md`](GIT_WORKFLOW.md) | main/develop、feature/release/hotfix 起点、合并方向与本地配置 |
| **本地工具与生态插件开发** | [`docs/PLUGIN_DEVELOPMENT_GUIDELINES.md`](PLUGIN_DEVELOPMENT_GUIDELINES.md) | 插件 Manifest 结构、Recipe 安装事务与环境变量注入规范 |
| **一切皆插件：平台方案与迁移** | [`docs/PLUGIN_PLATFORM.md`](PLUGIN_PLATFORM.md) | 内核边界、插件生命周期、页面/菜单贡献、授权设计与 P1–P6 验收路线 |
| **v2 进程插件包** | [`docs/PLUGIN_PACKAGE_V2.md`](PLUGIN_PACKAGE_V2.md) | 严格包格式、SemVer、完整性、安装收据与资源声明边界 |
| **智能体与工作流扩展** | [`docs/PLUGIN_HARNESS_EXTENSIONS.md`](PLUGIN_HARNESS_EXTENSIONS.md) | 编译时工具、背景资料、Provider 与自定义节点，以及审批和恢复边界 |
| **Web 插件与页面授权** | [`docs/PLUGIN_WEB_EXTENSIONS.md`](PLUGIN_WEB_EXTENSIONS.md) | 静态 Web 宿主、外部包、结构化能力、页面级限时授权与独立宿主凭据 |
| **项目模板制作与变量协议** | [`docs/PROJECT_TEMPLATE_SPEC.md`](PROJECT_TEMPLATE_SPEC.md) | template.json 规范、变量表单、动态模板替换与生命周期 hooks |
| **ARM64 Android 离线构建套件** | [`docs/ANDROID_OFFLINE_PLUGIN.md`](ANDROID_OFFLINE_PLUGIN.md) | 沙箱内内置 JDK/Android SDK/NDK/Flutter 工具链与移动端 Gradle 策略 |
| **Android CLI 与官方技能插件** | [`docs/ANDROID_SKILLS_PLUGIN.md`](ANDROID_SKILLS_PLUGIN.md) | `android` CLI（离线内置引导器 + 真身）与 33 个 Google 官方 Android Skills 的接入方式、铁律与卸载边界 |
| **内置浏览器设计决策（含 Hook/CDP 引擎）** | [`docs/BROWSER_DESIGN.md`](BROWSER_DESIGN.md) | 模块拓扑、工具协议、风险矩阵、Hook 引擎与 CDP 调试 ADR |
| **沙箱执行后端选型（为何不用 chroot）** | [`docs/ADR_SANDBOX_BACKEND.md`](ADR_SANDBOX_BACKEND.md) | PRoot vs chroot/namespace 决策、Android SELinux/seccomp/namespace 约束 |
| **工作流定义、调度语义与安全边界** | [`docs/WORKFLOW.md`](WORKFLOW.md) | DAG 模型、并发调度、审批、执行器、入口与当前能力边界 |
| **团队模板与审议制衡** | [`docs/GROUP_TEAM_GOVERNANCE.md`](GROUP_TEAM_GOVERNANCE.md) | 团队草稿与发布、按群升级、实时架构图、版本门禁、驳回与主控裁决 |
| **让步总账（技术债台账）** | [`docs/CONCESSIONS_LEDGER.md`](CONCESSIONS_LEDGER.md) | 为宿主平台/既有架构登记的临时妥协、原因与偿还路径；偿还后移除，新增让步须登记 |
| **Bot 群聊与可靠分发** | [`docs/BOT_COLLABORATION.md`](BOT_COLLABORATION.md) | 持久收件、独立任务续办、回复前复核、记忆编辑与可追溯恢复 |
| **已知问题与避坑指南** | [`docs/KNOWN_ISSUES.md`](KNOWN_ISSUES.md) | PRoot 沙箱环境已知限制、架构设计历史考量与规避方案 |
| **安全边界与凭证暴露面** | [`docs/SECURITY_SURFACE.md`](SECURITY_SURFACE.md) | 权限域总览、密钥流与脱敏边界、PRoot 挂载清单、HostBridge 信任模型与风险登记 |
| **存储分类与清理边界** | [`docs/STORAGE_MANAGEMENT.md`](STORAGE_MANAGEMENT.md) | 六类空间归属、清理计划、运行时互斥与后续管理建议 |

---

## 💡 核心模块拓扑极简速览 (Topology At a Glance)

```text
app (壳/装配/JNI)
 ├── feature/*          (components·theme·home·chat·terminal·workspace·settings·developer·navigation)
 ├── harness            (Agent 循环·内置工具分派·MCP·子智能体·in-process Browser MCP Server·Bootstrap)
 ├── tools              (插件 Registry·安装事务·Provider 安全仓储)
 ├── runtime            (PRoot 沙箱·PTY 终端·工作区构建引擎)
├── runtime/browser    (WebView 池·Snapshot/Screenshot/Network/Storage/JS·注入式 Hook 引擎·CDP 断点/Worker 级 Fetch 拦截·MCP tool handlers)
 ├── project-template   (模板 Manifest·变量协议·物化引擎)
 └── core/*             (model·common·database·datastore·network·security)
```
