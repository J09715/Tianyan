# 第三方组件与许可证清单

本文档记录当前工程直接使用、在线下载或随 APK 携带的第三方组件。它是分发前的审查清单，不构成法律意见；发布前应重新核对每个版本的上游许可证、NOTICE 和再分发条件。

| 组件 | 用途 | 来源/版本 | 许可证与分发备注 |
|---|---|---|---|
| Material Symbols Rounded | 全局单色功能图标，300 字重 / 24px 光学尺寸，SVG 转 Android VectorDrawable | [Google 官方仓库](https://github.com/google/material-design-icons)，固定修订 `49d4db35df873165d6bd6ba09b063c7dafbac2f4`；映射与转换见 `tools/sync-material-symbols.py` | Apache-2.0；许可证随 APK 置于 `feature/components/src/main/assets/licenses/material-symbols-LICENSE`，每个矢量资源保留来源与转换说明 |
| Debian / Ubuntu ARM64 rootfs | Android Linux 用户态系统 | Kotlin 安装流程对齐 `proot-distro 5.10.0` OCI 机制从 Docker/OCI Registry 拉取 | 各发行版及其中的软件包分别遵循各自许可证；不内置 APK，发布前需要提供对应版权与许可证获取地址 |
| Termux PRoot | Android ARM64 PRoot 启动器 | Termux 主仓库二进制包，版本和 SHA-256 见 `ProotInstaller` | 必须随对应二进制包核对许可证、NOTICE 和源码获取地址；APK 仅携带当前 ARM64 构建 |
| `libandroid-shmem.so` / `libtalloc.so` | PRoot 动态库依赖 | 与 PRoot 构建匹配的 Termux 包 | 不能只因为文件是 `.so` 就假定可自由再分发；发布前核对上游包的许可证文本 |
| `zstd-jni` | Android ARM64 `.tar.zst` 解压 | Maven Central，`1.5.5-4` AAR | 按上游 zstd-jni 的许可证和 NOTICE 分发 |
| RTK | Agent 安全前台命令的输出精简 | `rtk-ai/rtk` `v0.47.0`，随 APK 携带 `aarch64-unknown-linux-gnu` 可执行文件；归档 SHA-256 `960ceb5f1f5f0b0939b32b5b1d41dec6d9a7113137b0703c68dca0d169a260fc` | Apache License 2.0；完整许可证随 APK 置于 `assets/licenses/rtk-LICENSE`。仅在 Linux/glibc ARM64 沙箱中使用，无法运行或不支持的命令会回退原始命令 |
| Node.js ARM64 Runtime | OpenClaw 需要的 Node 22 fallback | Node.js 官方 `v22.22.3` Linux ARM64 tar.xz，SHA-256 固定在 `RuntimeBinaryInstaller` | Node.js 官方许可证/NOTICE；在线下载，不内置 APK；发布前保留对应版权和源码获取信息 |
| lzhiyong Android SDK Tools | ARM64 `aapt`、`aapt2`、`aidl`、`zipalign` | `35.0.2` 静态 AArch64 归档，URL 与 SHA-256 固定在 `setup_android_core.sh` | 在线下载、不内置 APK；发布前核对上游 Apache/AOSP LICENSE、NOTICE 与再分发要求 |
| lzhiyong/termux-ndk | 沙箱内 Android/Flutter 项目的 Linux AArch64 NDK 主机工具链 | r29 / NDK `29.0.14206865`，`android-ndk-r29-aarch64.tar.xz`，SHA-256 固定在 `setup_termux_ndk.sh` | 在线下载、不内置 APK；上游基于 AOSP LLVM/NDK，发布前随固定版本核对 LICENSE、NOTICE、源码与修改说明 |
| OkHttp | HTTPS 下载与 Registry 请求 | Maven Central，`4.12.0` | Apache License 2.0；发布包应包含 Apache 版权与 NOTICE 要求的文本 |
| AndroidX / Jetpack Compose Material 3 | Android UI 与系统集成 | Google Maven，版本见 `gradle/libs.versions.toml` | 按各 AndroidX/Jetpack 组件的许可证与 NOTICE 要求分发 |
| Ktor Client | HTTP 客户端（Agent 流式）与下载 | Maven Central，版本见 `gradle/libs.versions.toml` | Apache License 2.0 |
| Kotlin / Kotlinx Serialization / Coroutines | 应用基础运行库 | Maven Central，版本见 `gradle/libs.versions.toml` | 按各组件上游许可证与 NOTICE 要求分发 |
| Koin | 依赖注入 | Maven Central，版本见 `gradle/libs.versions.toml` | Apache-2.0 |
| Room | 本地数据库 | Google Maven，版本见 `gradle/libs.versions.toml` | 按上游许可证与 NOTICE 要求分发 |
| Agency Agents（软件研发精选目录） | 内置子智能体人格与专业工作流；按 9 个研发部门提供 136 个角色 | `msitarzewski/agency-agents`，固定提交 `3c9588880b7cafaec325a104899fd8bbe27e7d72` | MIT；原始 Markdown 与完整许可证随 APK 置于 `agency_agents/` assets，应用仅精选软件研发相关角色 |
| Android Skills（Google 官方 Android 技能目录） | `android-skills` 本地插件携带的 33 个官方技能（SKILL.md + references），落到 `/attachments/skills/android-skills` 供 Agent 按需加载 | `android/skills`，固定提交 `95ea931a1ad8f9b2be132ae0cfb2aad964d71118`（上游 `android-skills` 插件版本 `1.0.16`）；由 `tools/vendor-android-skills-plugin.py` 生成，逐文件 SHA-256 见插件 `payload/checksums/SHA256SUMS` | Apache-2.0；原文按 LF 入库（`assets/plugins/android-skills/payload/skills/`），完整许可证随插件置于 `payload/licenses/android-skills-LICENSE.txt` |
| Android CLI（`android` / `android-cli`） | `android-skills` 本地插件内置的 Linux ARM64 命令行工具（引导器 + 真身，自带 JRE），用于工程创建、SDK 管理、设备 UI dump/截图与文档检索 | `dl.google.com/android/cli/1.0.16625370/linux_arm64/{android,android-cli}`；SHA-256 固定在 `tools/vendor-android-skills-plugin.py`，不入 git（`.gitignore` 排除 `assets/plugins/android-skills/payload/bin/`） | Google 发布物，随 Android SDK 条款与隐私说明分发；不内置 APK，由用户在导入插件时按固定版本获取；CLI 遥测默认由插件包装层以 `--no-metrics` 关闭 |
| Termux `terminal-emulator`（fork） | 终端 VT100 模拟 + PTY JNI（`libtermux.so`），供本地与远程 SSH 终端渲染复用 | `termux/termux-app` tag `v0.118.0`（[上游](https://github.com/termux/termux-app)）；源码 vendored 至 `termux-terminal-emulator/`；`libtermux.so` 从 `repo/com/termux/terminal-emulator/0.118.0/terminal-emulator-0.118.0.aar` 提取（arm64-v8a，SHA-256 `8ed70880d8fd4d708d564c0d1dea61379668ac899a88cee0f54ad17a5344e917`，记录于 `termux-native.properties`） | GPL-3.0；本仓库新增 `TerminalSessionBackend` 并补充远程生命周期处理；原有公共 API 保持兼容，完整 GPL-3.0 文本见模块 `COPYING`；发布时随 GPL-3.0 分发并保留源码获取地址 |

## 工具安装器的上游来源

OpenAI Codex、OpenClaw 和 Hermes Agent 默认由用户在初始化 Linux 后主动点击安装，安装器通过 HTTPS 调用各项目官方安装入口；它们不是当前 APK 内置的二进制。对应上游链接和安装参数记录在：

- `docs/tools/codex.md`
- `docs/tools/openclaw.md`
- `docs/tools/hermes.md`

因此，工具的许可证、商标、服务条款和网络安装脚本变更应在每次发布前重新审查。应用不允许远程 Registry 直接下发任意 shell 命令；Registry 只提供数据，实际安装行为由 APK 内置且受 allow-list 约束的 Adapter 执行。

## 远程 SSH 依赖

| 组件 | 用途 | 来源/版本 | 许可证与分发备注 |
|---|---|---|---|
| JSch（mwiede fork） | 远程 SSH exec / PTY / SFTP，密码与私钥认证 | `com.github.mwiede:jsch:2.28.0`，[上游](https://github.com/mwiede/jsch) | BSD-style；发布时保留上游 LICENSE.txt / LICENSE.JZlib.txt / LICENSE.jBCrypt.txt |
| Bouncy Castle | Android 上的 SSH Ed25519 / 曲线及密钥算法实现 | `org.bouncycastle:bcprov-jdk18on:1.84`，[上游](https://www.bouncycastle.org/download/bouncy-castle-java/) | Bouncy Castle License（MIT-style）；保留 JAR 内许可证与版权说明 |
| Apache MINA SSHD | 仅 JVM 测试中的本机 SSH / SFTP 服务 | `org.apache.sshd:sshd-core/sshd-sftp:2.16.0` | Apache-2.0；仅 testImplementation，不进入 APK |

## 发布前检查

- 锁定并记录 APK 中每个 AAR/JAR/native 文件的准确版本、SHA-256、许可证和 NOTICE。
- 为可选发行版 rootfs、PRoot 和动态库保留源码/许可证获取地址及对应版本。
- 确认在线下载的 rootfs 和工具不会被误打包进 APK，也不会绕过上游许可证要求。
- 在商店或公开分发前补充 APK 内可访问的 “第三方许可证” 页面或完整文本包。
