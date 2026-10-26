# 天衍（Tianyan）自定义迭代与 TianyanDev 构建模块 (`feature:custom_iteration`)

本模块为 **天衍 · Tianyan** 量身打造的标准、无降级自迭代系统。让用户在手机上使用天衍的自主 AI Agent 进行天衍自身的二次开发、测试、云端 CI 构建，并向开源社区贡献代码。

---

## 一、模块架构与核心组件

```
feature/custom_iteration/
├── build.gradle.kts                        # Gradle 多模块依赖配置（对齐天衍规范）
├── README.md                               # 完整模块架构与接入文档
└── src/
    ├── main/
    │   ├── assets/
    │   │   ├── skills/
    │   │   │   └── tianyan-custom-iteration/
    │   │   │       └── SKILL.md            # Agent 专有开发与 PR 规范 Skill
    │   │   └── templates/workflows/
    │   │       └── tianyandev-build.yml      # GitHub Actions 云端构建 CI 模板（与 .github/ 逐字节一致）
    │   └── java/top/tianyan/app/
    │       ├── di/feature/custom/iteration/
    │       │   └── KoinModule.kt                 # Koin 依赖装配（Coordinator + ViewModel）
    │       ├── iteration/engine/
    │       │   ├── CustomIterationBootstrap.kt   # 自举引擎（工作区初始化/资产落盘/引导 Prompt 生成）
    │       │   ├── CustomIterationPaths.kt       # 运行时路径契约与 assets 读取抽象
    │       │   ├── TianyanDevBuildCli.kt           # gh 命令模板与远程 URL 解析
    │       │   └── TianyanDevBuildCoordinator.kt   # CI 调度与 APK 下载校验协调器
    │       └── ui/iteration/
    │           ├── CustomIterationScreen.kt      # Jetpack Compose UI 界面
    │           └── CustomIterationViewModel.kt   # Koin 状态管理 ViewModel
    └── test/java/top/tianyan/app/
        ├── iteration/engine/
        │   ├── CustomIterationBootstrapTest.kt   # 自举：落点/幂等/失败关闭
        │   └── TianyanDevBuildCoordinatorTest.kt   # 调度：远程解析/轮询/校验/注入防护
        └── ui/iteration/
            └── CustomIterationViewModelTest.kt   # 状态机与真实路径契约
```

---

## 二、核心特性与设计规范（零降级）

1. **工作区物理隔离**：
   * 工作区宿主路径取 `RuntimePathManager.homeDir/custom_tianyan`，该目录经 PRoot 绑定为 guest 的
     `/root`，因此沙盒内视角即 `~/custom_tianyan`；开发行为完全不破坏运行中的宿主系统。
   * 自举只 `mkdir` 这个空目录。克隆前不写入 `.github` 或 `.agents`，否则
     `git clone <fork> ~/custom_tianyan` 会因目录非空失败。
2. **专属 Agent Skill 约束 (`SKILL.md`)**：
   * 指导 AI Agent 严格遵守天衍的多模块 Kotlin DSL 规范、Material3 Compose 规范、协程设计及凭证安全规则。
   * Skill 随模块 assets 打包。自举时只落盘到两个真实扫描根，无需用户手工复制：
     `<attachmentsDir>/skills/`（guest `/attachments/skills`）、
     `<workspaceDir>/skills/`（guest `/workspace/skills`）。
   * `AgentSkillRepository.standardScanRoots` 还会扫描 **workspaceDir 树下** 的 `.agents/skills`。
     `~/custom_tianyan` 不在这棵树里，因此 `~/custom_tianyan/.agents/skills` 不是扫描根，自举也不会在克隆前写入它。
   * 任一资产缺失或写不进去时**显式报错**，绝不给出「工作区就绪」的假象。
3. **GitHub Actions 云端 CI 构建 (`tianyandev-build.yml`)**：
   * 免去手机端部署 Android SDK/NDK 的庞大体积开销；
   * 云端 Runner（`ubuntu-24.04` + Temurin JDK 17 + `platform-tools`）执行
     `TIANYAN_DEV_BUILD=1 ./gradlew assembleDebug`，产物改名 `TianyanDev-arm64-debug.apk` 并附
     `.sha256`，通过 `gh run watch --exit-status` / `gh run download -n tianyandev-apk` 回传手机。
   * 工作流模板与仓库根 `.github/workflows/tianyandev-build.yml` 逐字节一致，缓存在
     guest `/attachments/templates/workflows/tianyandev-build.yml`（克隆目标之外）。
   * 构建目标仓库**运行时从迭代工作区的 `origin` remote 解析**：Fork 后自动指向主人账户，
     避免写死官方仓库导致 `workflow_dispatch` 403；URL 无法解析为 `owner/name` 时直接失败关闭。
4. **双包共存（Dual-Flavor）**：
   * 编译产物包名自动重命名为 `top.tianyan.app.dev`，应用名显示为 `TianyanDev`；
   * 测试版与手机中的正式版天衍共存运行、互不覆盖。
5. **预置 Agent 引导 Prompt**：
   * 自举时生成 6 步引导 Prompt（含设备码认证、免本地 SDK 策略、双包校验），交给 Chat 会话执行。
   * 云端构建目标与 Coordinator 一致：在 `~/custom_tianyan` 执行 `git remote get-url origin`，
     再用同一套 GitHub `owner/name` 解析。尚未克隆或无法解析时写入占位符，要求 Agent 先读
     `git remote`，禁止对 `J09715/Tianyan` 执行 `workflow_dispatch`。
6. **开源 PR 交付闭环**：
   * 本地真机体验通过后，Agent 协助生成标准格式 PR 提交到 `J09715/Tianyan:main`。
7. **克隆之后才写入 Fork 仓库**（自举阶段不执行）：
   * 工作流：官方 main 已自带 `.github/workflows/tianyandev-build.yml`。若 Fork 缺少或过旧，再执行
     `mkdir -p ~/custom_tianyan/.github/workflows && cp /attachments/templates/workflows/tianyandev-build.yml ~/custom_tianyan/.github/workflows/tianyandev-build.yml`。
   * 项目级 Skill 只在准备提交进 Fork 时复制。它不会被本应用自动扫描：
     `mkdir -p ~/custom_tianyan/.agents/skills && cp -a /attachments/skills/tianyan-custom-iteration ~/custom_tianyan/.agents/skills/`。

---

## 三、快速接入指南（集成到天衍主工程）

### 1. 注册 Gradle 子模块
在 `settings.gradle.kts` 中添加：
```kotlin
include(":feature:custom_iteration")
```

### 2. 添加导航路由
在 `TianyanNavHost.kt` 中添加路由项：
```kotlin
composable("custom_iteration") {
    CustomIterationScreen(
        onBack = { navController.popBackStack() },
        onNavigateToChat = { prompt ->
            navController.navigate("chat?prompt=${Uri.encode(prompt)}")
        }
    )
}
```

### 3. 在设置或开发者中心添加入口
在 `SettingsScreen.kt` 或 `DeveloperScreen.kt` 中添加跳转入口按钮即可！
