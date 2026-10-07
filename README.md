<p align="center">
  <img src="assets/logo/tianyan-logo.png" width="96" alt="天衍 Logo" />
</p>

<h1 align="center">天衍 · Tianyan</h1>

<p align="center"><strong>面向 Android 的随身智能体工作台：在一部无需 Root 的设备上运行 Linux、管理代码项目，并让智能体协助完成实际开发任务。</strong></p>

<p align="center">Linux 运行环境 · 智能体引擎 · 终端与工作区 · 浏览器调试 · 设备诊断</p>

<p align="center">
  <code>v0.16.0</code> · <code>Android 10+ (SDK 29+)</code> · <code>arm64-v8a</code> · <code>Kotlin · Jetpack Compose</code>
</p>

<p align="center">
  <a href="https://github.com/J09715/Tianyan/releases/tag/v0.16.0">下载 v0.16.0</a>
  ·
  <a href="https://github.com/J09715/Tianyan/actions">构建与测试</a>
</p>

---

## 天衍是什么

天衍把开发环境、任务执行和设备工具整合到 Android 手机上的一个工作台。它不依赖 Root 权限：Linux 用户空间通过 PRoot 运行，智能体通过可配置的模型接口理解任务、调用工具并持续报告执行状态。代码、终端、浏览器、Git、工作流和设备诊断可以在同一项目上下文中协作。

天衍面向需要移动开发、远程排障和自动化任务执行的开发者。你可以在设备上准备 Linux 环境和项目目录，用终端或图形工具编辑、构建与检查项目，也可以交给智能体执行跨工具任务，并通过审批、运行状态和会话历史掌握执行过程。

## 能力概览

| 工作区 | 能力 |
| :--- | :--- |
| **智能体** | OpenAI 兼容接口与 Anthropic Messages API；流式对话、模型配置、工具调用与审批、任务计划、多智能体调度、会话分支与恢复。新建会话可选择红队模式，目标、范围、阶段与红队状态按会话隔离。 |
| **Linux 环境** | 基于 PRoot 的 ARM64 用户空间；支持多个 Linux 发行版、RootFS 导入与校验、持久目录挂载、镜像源配置及环境诊断。 |
| **终端与项目** | 原生 PTY、多终端会话、项目导入与浏览、Git 操作、代码差异查看，以及沙箱中的后台构建。 |
| **浏览器调试** | 多标签 WebView、CDP 调试、断点与网络请求拦截、页面 Hook 和请求记录。 |
| **工作流与工具** | 可视化 DAG 工作流、定时任务、MCP 服务、开发工具环境安装与依赖管理。 |
| **设备诊断** | 无线 ADB 配对与发现、设备状态检查、Android 与 Linux 侧日志查看。 |
| **文件与协作** | 共享工作区、局域网 WebChat、FTP 文件传输，以及可选的端侧本地模型运行能力。 |

## 红队模式

红队模式是天衍的会话级 Agent 类型。新建会话时选择红队模式，进入当前对话后绑定目标与授权范围；目标、阶段与作战数据只存在于当前会话，切换会话不会看到其他会话的目标或记录。子智能体继续使用天衍原生子代理，不引入第二套编排实现。

工作区由原生 Compose 渲染，按上游 Web 控制台的维度分组展示：资产、漏洞、凭据、访问会话、WebShell、隧道、攻击链、评分、攻击文件、知识库与技能。

Agent 侧通过统一的 `redteam` 工具工作，始终作用于调用它的那个会话：

| 能力 | action |
| :--- | :--- |
| 会话与预检 | `session_info`、`session_check`、`sessions`、`preflight`、`roles` |
| 角色提示词 | `role_prompt`、`role_prompt_reset`（会话级覆盖，未设置时回落内置职责） |
| 并发闸门 | `agent_slot`（`status`/`acquire`/`release`，上限 3，按会话隔离） |
| 事实写入 | `asset_add`、`vuln_add`、`credential_add`、`access_add`、`webshell_add`、`tunnel_add`、`chain_add`、`attack_file_add`、`score_hit`、`poc_add`、`http_evidence_add`、`knowledge_add`、`skill_add` |
| 事实更新 | `asset_update`、`vuln_update`、`credential_update`、`access_update`、`webshell_update`、`tunnel_update`、`poc_update`（必须带 `id`） |
| 资产图谱 | `asset_link`（写关系边）、`asset_graph`（按 C 段取子图，节点上限默认 300 / 硬上限 1000）、`domain_index`、`asset_stats`、`asset_timeline`、`web_list` |
| 攻击路径 | `attack_path`、`chain`、`attack_chain` |
| 单条读取 | `asset_get`、`vuln_get`、`poc_get` |
| PoC 使用 | `poc_use`（使用计数写回记录） |
| 事实查询 | `fact_query`、`asset_query`、`vuln_query`、`credential_list`、`webshell_list`、`tunnel_list`、`attack_file_list`、`score_list`、`poc_list`、`poc_search` |
| 报告 | `report`、`score_report`、`report_targets` |
| 评估登记 | `asset_assess`、`asset_test`（只登记结论，主动探测仍走已审批的 `base`/`process`） |

事实记录保留上游的原始字段（IP、端口、服务、指纹、来源 provenance、关系 src/dst/relation 等）并以 JSON 存入会话事实库；结构化写入使用稳定 ID，可重复提交覆盖同一条记录。工具 action 清单与协调器实现由 `RedTeamToolSchema` 单一来源驱动，并有契约测试断言「宣告的动作必须已实现」，防止再次出现枚举宣告但无分支的情况。真正的探测与验证动作仍通过天衍既有的 `base`/`process`/`MCP` 执行，并遵循当前会话的审批模式与 scope。

## 日志与崩溃排查

开发者控制台提供「运行日志」区块：

| 操作 | 作用 |
| :--- | :--- |
| 查看运行日志 | 等宽字体弹窗展示 `runtime.log` 尾部（默认 10 万字符） |
| 查看崩溃报告 | 展示最近一次未捕获异常，含版本、机型、线程与完整栈 |
| 复制运行日志 / 复制崩溃报告 | 一键进剪贴板，便于直接反馈 |
| 清空运行日志 / 清空崩溃报告 | 复现前清零，避免旧记录干扰 |

日志优先写入公共 `Download/Tianyan`，无「所有文件访问」权限时回退应用私有目录，保证不静默丢失；崩溃报告会在下次启动后导出到 `Download/Tianyan/crash-reports`。

## 获取应用

最新稳定版本：**[天衍 v0.16.0](https://github.com/J09715/Tianyan/releases/tag/v0.16.0)**

- APK：[`Tianyan-v0.16.0-debug.apk`](https://github.com/J09715/Tianyan/releases/download/v0.16.0/Tianyan-v0.16.0-debug.apk)
- SHA-256：[`Tianyan-v0.16.0-debug.apk.sha256`](https://github.com/J09715/Tianyan/releases/download/v0.16.0/Tianyan-v0.16.0-debug.apk.sha256)
- 包名：`top.tianyan.app.debug`
- 签名：由项目稳定密钥签名；校验和及证书 SHA-256 指纹见 Release 说明。

该 APK 与先前使用相同包名和签名证书的版本兼容，可直接覆盖安装。不要使用第三方工具重新签名，否则会改变证书并导致 Android 拒绝覆盖更新。

## 从源码构建

### 环境要求

- JDK 21
- Android SDK：compileSdk 37、targetSdk 37、minSdk 29
- Android NDK：`30.0.15729638`
- CMake：`3.22.1`
- Gradle Wrapper：以仓库配置为准

### 构建步骤

```bash
# 首次检出后准备 PRoot ARM64 运行时
bash tools/prepare-proot-runtime.sh

# 执行架构检查和单元测试
./gradlew architectureCheck --console=plain
./gradlew testDebugUnitTest --console=plain

# 编译 Debug APK
./gradlew :app:assembleDebug --console=plain
```

APK 输出目录：`app/build/outputs/apk/debug/`。Release workflow 负责准备 ARM64 原生 PTY 库、使用项目稳定签名密钥构建，并验证 APK 的包名、对齐和签名指纹。GitHub Actions 会运行全量 JVM 单元测试和 APK 构建；工作流与运行结果见 [Actions](https://github.com/J09715/Tianyan/actions)。

## 项目与许可

天衍以独立产品和品牌持续开发。项目中包含基于第三方开源软件构建或分发的组件，其来源、版权与适用条款记录在 [`NOTICE`](NOTICE) 中。仓库当前没有根目录 `LICENSE` 文件；在正式增加许可证声明前，除 NOTICE 中明确许可的第三方内容外，不应推定本项目整体采用某一开源许可证。
