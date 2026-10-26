# Android CLI 与官方技能本地插件（`android-skills`）

本文说明把 **Google Android CLI** 与 **`android/skills` 官方 Android 技能**接入天衍沙箱的方式、铁律与边界。插件源码位于 `assets/plugins/android-skills/`，产物为 `dist/plugins/android-skills-<version>-arm64.txplugin`。

## 1. 这个插件做什么

| 交付物 | 落位（沙箱 guest 视角） | 说明 |
| --- | --- | --- |
| `android` 命令 | `/opt/tianyan/bin/android` → `$TIANYAN_TOOL_DIR/bin/android`（包装层） → `$TIANYAN_TOOL_DIR/lib/android-launcher` | 由安装框架生成入口，包装层负责固定 `ANDROID_USER_HOME` 并默认注入 `--no-metrics` |
| CLI 真身 | `${ANDROID_USER_HOME}/bin/android-cli`（默认 `/opt/tianyan/data/android-skills/android-user-home/`） | 引导器只在缺它时才联网下载；预置后全程离线 |
| 首次解包缓存 | `${ANDROID_USER_HOME}/cli/bundles/<sha>/`（`main.jar` + 自带 JRE + `libos_trust.so`，约 70 MB） | 安装期主动触发一次，避免把耗时算进 60 秒超时的 `verifyCommand` |
| 33 个官方技能 | `/attachments/skills/android-skills/<技能名>/` | 天衍技能自动发现的扫描根之一，随应用启动与设置页扫描自动入库 |

插件 `id` 为 `android-skills`，`schemaVersion=1`、`source=LOCAL`、`offlineOnly=true`、`architectures=["ARM64"]`，贡献单一命令入口 `android`。

## 2. 为什么必须“双件内置”

实测结论（WSL Ubuntu + `unshare -rn` 真断网，x86_64 与 arm64 同一套引导代码）：

| 事实 | 证据 |
| --- | --- |
| `linux_arm64/android`（3.97 MiB）只是引导器 | 它内部只有下载/落盘逻辑：`ANDROID_CLI_DOWNLOAD_URL`、`ANDROID_USER_HOME`、`Failed to create bin dir` |
| 缺真身时会联网下载 85.63 MiB | payload 缺失 + 假下载地址 + 断网 → `Error: Failed to download from ...`（exit 1） |
| 预置真身后完全不联网 | payload 就位 + 假下载地址 + 断网 → `android --version` / `android info` 均正常（exit 0） |
| 首次解包离线可行 | 断网首次运行就地解包 `main.jar`+JRE 到 `${ANDROID_USER_HOME}/cli/bundles/<sha>/` |
| ToS 不阻塞非交互调用 | 首次运行打印一次提示并写 `${ANDROID_USER_HOME}/cli/tos_displayed`，退出码 0 |
| `android skills list` 离线只告警 | 输出两行 `Warning: Could not download ...`，退出码 0；技能已本地随插件下发 |

因此只内置 4 MiB 引导器等于“装完不可用”，插件把两者一起放进 `payload/bin/`，装配期完成就位与解包。

体积账：插件包约 90.8 MiB（引导器 3.97 + 真身 85.63 + 技能 4.12 + 脚本/清单），首次运行后在沙箱内额外占用约 70 MB 解包缓存。

## 3. 铁律

1. **不要 `curl ... install.sh | bash` 重装**。包装层与 `/opt/tianyan/bin/android` 是唯一受管入口；重装会写 `~/.local/bin` 并再拉一份 85 MiB。
2. **不要改写 `ANDROID_USER_HOME`**。它默认指向插件自有目录，避免与 adb 的 `~/.android`（adb 密钥、AVD、许可证状态）互相污染；用户显式设置时包装层会尊重该值。
3. **遥测默认关闭**。包装层注入 `--no-metrics`；除非用户明确要求上行使用数据，否则不要移除。
4. **构建仍走天衍工具链**。构建 APK 使用 `tianyan-build doctor <工作区>` + `tianyan-build android <工作区> assembleDebug`；不要用 `android sdk` 或 Gradle 自动下载覆盖 ARM64 AAPT2/NDK/JDK。
5. **技能不许重复拉取**。33 个技能随插件下发到扫描根；`android skills add` 需要联网，离线时只应作为可选补充。

## 4. Agent 侧如何命中技能

- 技能扫描根：`/attachments/skills`、`/workspace/skills`、工作区 `.agents/skills`；插件使用第一个。
- 技能目录常驻“名称 + 一句话描述”，正文由 `load_skill(name, path)` 按需拉取；目录**不再设条目上限**（`renderSkillCatalog`），因此 33 个官方技能与既有内置技能可以同时被模型自主匹配，单条成本仅由 100 字符描述截断约束。
- 官方 `description` 是普通多行标量（无 `>-` 指示符）；解析器已支持该形态，避免描述被截断成首行。语料回归见 `runtime/src/test/java/top/tianyan/app/runtime/AndroidSkillsPluginAssetTest.kt`。
- 内置技能 `android_cli`（`/android`）已同步为“天衍环境内的 CLI 使用与官方技能索引”，并明确禁止联网重装。

## 5. 生成、刷新与打包

```powershell
# 技能目录可读可 diff，随仓库入库；两个二进制被 .gitignore 排除，由固定 URL 复现
python tools/vendor-android-skills-plugin.py --force     # 重新拉取固定版本并重算 SHA256SUMS
python tools/vendor-android-skills-plugin.py --no-pack   # 只准备 payload
python tools/vendor-android-skills-plugin.py             # 打包 dist/plugins/android-skills-1.0.0-arm64.txplugin
```

升级上游：改 `ANDROID_SKILLS_COMMIT`（`git ls-remote https://github.com/android/skills refs/heads/main`）或 `ANDROID_CLI_VERSION` + 两个 SHA-256；脚本在哈希不匹配时会拒绝写入并打印实际值。**每次刷新都必须同步 `manifest.json` 的 `version`**，否则同版本包会被导入器拒绝。上游来源与许可证见插件内 `UPSTREAM.md` 与 [`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md)。

## 6. 安装、验证与卸载

1. 设置 → 插件与工具中心 → 导入 `android-skills-<version>-arm64.txplugin`。
2. 安装脚本按序执行：校验 `checksums/SHA256SUMS` → 部署包装层与引导器 → 预置真身 → 首次解包 → 技能落位 `/attachments/skills/android-skills` → 运行 `verify-android-skills.sh` → 释放沙箱内 90 MB 载荷暂存。
3. 安装后自检：`command -v android && android info`、`ls /attachments/skills/android-skills`。
4. 卸载：框架先移除命令入口，再执行 `uninstall-android-skills.sh` —— 只删除本插件的包装层、引导器与 `/attachments/skills/android-skills/`，**不触碰用户其它技能目录**；`$TIANYAN_TOOL_DATA` 下的解包缓存默认保留（约 70 MB），需要释放时手动删除。

已知残留：技能入库条目由目录扫描产生（id 形如 `custom_xxxxxxxx`）。卸载只移除文件，数据库中对应的技能条目需要用户在技能页删除，否则会留下指向已删除目录的条目。

## 7. 验证边界（重要）

- 已在 WSL Linux 上以 x86_64 复现并确认引导器行为（预置真身 → 断网可用、缺失真身 → 联网失败、首次解包离线可行、ToS 不阻塞）。
- **尚未在真机 PRoot 内验收**：Android 侧 ARM64 glibc 需 ≥ 2.25（`android` 的 `PT_INTERP=/lib/ld-linux-aarch64.so.1`，最高引用的 GLIBC 符号为 2.25）；首次解包约 70 MB，真机耗时与 `verifyCommand` 的 60 秒超时需要在设备上确认。
- `android layout` / `screen capture` / `install` / `run` 依赖设备侧 adb（可用内置无线 ADB），未在本轮验证。
