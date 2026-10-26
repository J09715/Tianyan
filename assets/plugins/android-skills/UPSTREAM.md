# 上游来源与固定版本（android-skills 插件）

本目录内的三方内容与固定输入，全部由 `tools/vendor-android-skills-plugin.py` 生成。

| 内容 | 来源 | 固定版本 | 许可证 |
| --- | --- | --- | --- |
| 官方 Android Skills（33 个技能目录） | https://github.com/android/skills | 提交 `95ea931a1ad8f9b2be132ae0cfb2aad964d71118`（上游 `android-skills` 插件版本 `1.0.16`） | Apache-2.0，原文见 `payload/licenses/android-skills-LICENSE.txt` |
| Android CLI 引导器 | `https://dl.google.com/android/cli/1.0.16625370/linux_arm64/android` | 版本 `1.0.16625370`，SHA-256 `c56de0230dbbea470f13f1bf8f4d983584248c401eb5365bbbde6555949954cf` | Google 发布物（随 Android SDK 条款） |
| Android CLI 真身（`android-cli`） | `https://dl.google.com/android/cli/1.0.16625370/linux_arm64/android-cli` | 版本 `1.0.16625370`，SHA-256 `72d8ab31a3d7bab92793c37c7a72b5c854c09027c7975120e2c1413df983bcb4` | 同上 |

## 为什么必须两件一起内置

实测（WSL Ubuntu + `unshare -rn` 真断网）：

1. `linux_arm64/android`（约 3.97 MiB）只是引导器；缺少 `$ANDROID_USER_HOME/bin/android-cli`
   时会去 `dl.google.com` 下载约 85.63 MiB 的真身。
2. 只要把 `android-cli` 预置到 `$ANDROID_USER_HOME/bin/android-cli`，**断网下
   `android --version` / `android info` 均正常**，引导器不再联网。
3. 首次运行会就地把内嵌安装解包到 `$ANDROID_USER_HOME/cli/bundles/<sha>/`
   （`main.jar` 约 61 MiB + 自带 JRE + `libos_trust.so`，合计约 70 MiB），**该解包不联网**。
4. ToS 只打印一次提示并写 `$ANDROID_USER_HOME/cli/tos_displayed`，不阻塞非交互调用；
   `android skills list` 离线时只输出告警并正常退出。

因此插件把引导器与 `android-cli` 都放进 `payload/bin/`，由安装脚本在装配期完成
解包与技能落位，做到「导入即离线可用」。

## 刷新方式

```powershell
# 只更新技能（技能目录可读可 diff，随仓库入库）
python tools/vendor-android-skills-plugin.py --force

# 只重打包（payload 已就绪时）
python tools/vendor-android-skills-plugin.py --no-pack   # 准备 payload
python tools/vendor-android-skills-plugin.py             # 打包 dist/plugins/*.txplugin
```

升级上游技能：改 `ANDROID_SKILLS_COMMIT`（用 `git ls-remote https://github.com/android/skills refs/heads/main` 取新值）。
升级 CLI：改 `ANDROID_CLI_VERSION`，并同步 `ANDROID_CLI_*_SHA256`（脚本在不匹配时会拒绝写入并打印实际值）。
每次刷新后必须同步 `manifest.json` 的 `version`，否则同版本包会被导入器拒绝。
