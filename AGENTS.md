# 🧭 天衍 (Tianyan / LinuxAIRuntime) — AI 导航入口

> 本文件为导航入口，默认载入。全部细节（技术栈 / 模块拓扑 / 调用链路 / 文件索引 / 铁律 / 命令）在 [`docs/AI_NAVIGATION.md`](docs/AI_NAVIGATION.md)，按需读取。

## 是什么

Android 无 Root 下用 PRoot 跑 Linux 多发行版沙箱 + AI Agent Harness + 原生 PTY 终端。Kotlin 2.4 / Compose / Koin / Room / Navigation3，仅 `arm64-v8a`。

## 模块

`app`(壳/装配/JNI) · `core`(model·common·database·datastore·network·security) · `runtime`(PRoot/RootFS/PTY/工作区) · `tools`(Registry/安装事务/Provider安全) · `harness`(Agent循环/MCP/子智能体) · `feature`(components·theme·home·chat·terminal·workspace·settings·developer·onboarding·custom\_iteration·navigation)

## 动手前先读

| 要做什么                  | 推荐阅读文档                                                                |
| :-------------------- | :-------------------------------------------------------------------- |
| **快速把握全局**            | [`docs/AI_NAVIGATION.md`](docs/AI_NAVIGATION.md)（语义导航总览）              |
| **写代码 / 改模块 / 理拓扑**   | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)（架构与模块拓扑）               |
| **追踪数据流 / 调用链路**      | [`docs/EXECUTION_TRACES.md`](docs/EXECUTION_TRACES.md)（核心执行时序）        |
| **定位某个类 / 寻找文件**      | [`docs/FILE_INDEX.md`](docs/FILE_INDEX.md)（关键文件索引速查）                  |
| **UI/UX 设计系统与架构铁律**   | [`docs/ARCHITECTURE_RULES.md`](docs/ARCHITECTURE_RULES.md)（设计规范与避坑指南） |
| **构建 / 测试 / 打包 / 调试** | [`docs/COMMANDS.md`](docs/COMMANDS.md)（常用命令速查）                        |
| **安全边界 / 凭证暴露面**   | [`docs/SECURITY_SURFACE.md`](docs/SECURITY_SURFACE.md)（凭证流、PRoot 挂载、HostBridge、脱敏） |

## 协作与提交规则（多会话并行）

多个 AI 会话可能与用户在同一工作区并行修改不同文件。Git 操作不得波及本次会话改动之外的文件：

- 只提交本次会话负责的文件；`git add` 显式路径，禁止 `git add -A` / `git add .`。
- 提交前用 `git status` + `git diff --staged` 核对暂存区；同一文件混入多方改动时按改动块（hunk）拆分暂存，无法干净拆分时先征询用户。
- 提交信息遵循 conventional commits + 中文描述（如 `feat(harness): 统一工具目录`）。
- 用户未要求时不得提交；用户明确授权范围（如「所有改动都提交」）时按该授权范围执行。
- 禁止 `git reset --hard` / `git checkout .` / `git clean -fd` / `git stash` / `git commit --no-verify` / `push --force`；rebase 冲突只解决自己改过的文件，否则中止并询问。
- 提交身份仅做本仓库 repo 级配置，不改全局。

## 模型调用与测试规则

- 默认不调用真实付费模型 API：协议与链路测试一律使用假 Provider / MockWebServer，测试必须在无 API Key 的机器上全绿。
- 完整链路回归目标：输入受理 → 模型响应 → 工具执行 → 审批 → 恢复 → Web 完成事件。
- 涉及凭证、沙箱或宿主权限的问题，先读 [`docs/SECURITY_SURFACE.md`](docs/SECURITY_SURFACE.md)。

